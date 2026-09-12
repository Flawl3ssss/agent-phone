package dev.kimiterminal.agent

import dev.kimiterminal.acp.ACP_PROTOCOL_VERSION
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MockKimiMain — ACP-агент на Kotlin поверх NDJSON в stdin/stdout.
 *
 * Зачем отдельным процессом, а не фейком в памяти: чтобы приложение тестировалось на
 * **настоящем** транспорте (фрейминг, UTF-8, двусторонние запросы, таймауты), а не на
 * подделке. Запуск:
 *
 *   java -cp <classpath> dev.kimiterminal.agent.MockKimiMainKt
 *
 * Мимикрия под `kimi acp` (kimi-code 0.42.0): та же матрица capabilities, те же обратные
 * RPC, та же семантика ошибок. stdout — только протокол, логи — в stderr.
 */

private val J = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

private class MockState {
    val sessions = ConcurrentHashMap<String, MutableMap<String, String>>()
    val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    var nextId = 1000L
    var turn = 0
    var authenticated = false
}

/**
 * Ядро мока: читает из [stdin], пишет в [stdout]. Может работать
 *  - поверх реальных process-stdio (см. main()),
 *  - поверх in-memory канала внутри приложения (PipeTransport) — для APK без JVM.
 */
class MockAcpAgent(private val stdin: BufferedReader, private val stdout: java.io.Writer) {
    private val st = MockState()
    private val executor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "mock-handler").apply { isDaemon = true } }

    private fun send(o: JsonObject) = synchronized(stdout) { stdout.write(o.toString()); stdout.write("\n"); stdout.flush() }
    private fun reply(id: JsonElement?, r: JsonObject) = send(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: JsonPrimitive(0)); put("result", r)
    })
    private fun fail(id: JsonElement?, code: Int, msg: String) = send(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: JsonPrimitive(0))
        put("error", buildJsonObject { put("code", code); put("message", msg) })
    })
    private fun update(sessionId: String, u: JsonObject) = send(buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "session/update")
        put("params", buildJsonObject { put("sessionId", sessionId); put("update", u) })
    })

    private fun requestClient(method: String, params: JsonObject): JsonObject? {
        val id = st.nextId++
        val fut = CompletableFuture<JsonObject>()
        st.pending[id] = fut
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", JsonPrimitive(id)); put("method", method); put("params", params)
        })
        return try { fut.get(60, TimeUnit.SECONDS) } catch (e: Exception) { st.pending.remove(id); null }
    }

    private fun textChunk(kind: String, s: String) = buildJsonObject {
        put("sessionUpdate", kind); put("content", buildJsonObject { put("type", "text"); put("text", s) })
    }
    private fun toolCall(id: String, title: String, kind: String, status: String, path: String? = null) = buildJsonObject {
        put("sessionUpdate", "tool_call"); put("toolCallId", id); put("title", title); put("kind", kind); put("status", status)
        if (path != null) putJsonArray("locations") { addJsonObject { put("path", path) } }
    }
    private fun toolDone(id: String, status: String, content: JsonObject? = null) = buildJsonObject {
        put("sessionUpdate", "tool_call_update"); put("toolCallId", id); put("status", status)
        if (content != null) putJsonArray("content") { add(content) }
    }

    fun run() {
        System.err.println("[mock-kimi] готов; stdout — только протокол")
        while (true) {
            val line = stdin.readLine() ?: break
            if (line.isBlank()) continue
            val msg = runCatching { J.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val hasMethod = msg.containsKey("method")
            val id = msg["id"]

            // 1) ответ на НАШ обратный запрос — маршрутизируем в том же цикле
            if (!hasMethod && id != null) {
                val rid = runCatching { id.jsonPrimitive.long }.getOrDefault(-1L)
                st.pending.remove(rid)?.let { fut ->
                    if (msg.containsKey("error")) fut.completeExceptionally(RuntimeException(msg.toString()))
                    else fut.complete((msg["result"] as? JsonObject) ?: buildJsonObject {})
                }
                continue
            }
            if (!hasMethod) continue

            // 2) запрос/уведомление клиента — **в отдельном потоке**.
            //    Иначе prompt-обработчик, ждущий ответа на session/request_permission,
            //    блокирует единственный цикл чтения и этот ответ никогда не прочитается.
            //    Настоящий kimi acp асинхронный — мок обязан вести себя так же.
            val m = msg.getValue("method").jsonPrimitive.content
            val p = msg["params"] as? JsonObject ?: buildJsonObject {}
            executor.submit {
                try { handle(m, p, id) } catch (e: Exception) {
                    System.err.println("[mock-kimi] handle($m) упал: ${e.message}")
                    if (id != null) runCatching { fail(id, -32603, e.message ?: "mock error") }
                }
            }
        }
        System.err.println("[mock-kimi] stdin закрыт, выхожу")
        executor.shutdownNow()
    }

    private fun handle(method: String, params: JsonObject, id: JsonElement?) {
        when (method) {

            "initialize" -> reply(id, buildJsonObject {
                put("protocolVersion", ACP_PROTOCOL_VERSION)
                put("agentInfo", buildJsonObject { put("name", "Kimi Code CLI"); put("version", "0.42.0-mock-kotlin") })
                put("agentCapabilities", buildJsonObject {
                    put("loadSession", true)
                    put("promptCapabilities", buildJsonObject { put("image", true); put("audio", false); put("embeddedContext", true) })
                    put("mcpCapabilities", buildJsonObject { put("http", true); put("sse", true) })
                    put("sessionCapabilities", buildJsonObject {
                        put("list", buildJsonObject {}); put("resume", buildJsonObject {}); put("close", buildJsonObject {})
                        put("delete", buildJsonObject {}); put("fork", buildJsonObject {}); put("additionalDirectories", buildJsonObject {})
                    })
                    put("auth", buildJsonObject { put("logout", buildJsonObject {}) })
                })
                putJsonArray("authMethods") {
                    addJsonObject {
                        put("type", "terminal"); put("id", "login"); put("name", "Kimi Code Login")
                        putJsonArray("args") { add("--login") }; put("env", buildJsonObject {})
                    }
                }
            })

            "authenticate" -> { st.authenticated = true; reply(id, buildJsonObject {}) }
            "logout" -> { st.authenticated = false; reply(id, buildJsonObject {}) }

            "session/new" -> {
                val cwd = params["cwd"]?.jsonPrimitive?.content
                if (cwd.isNullOrBlank()) { fail(id, -32602, "cwd is required"); return }
                val sid = "session_%016x".format(System.nanoTime())
                st.sessions[sid] = mutableMapOf("cwd" to cwd)
                reply(id, buildJsonObject {
                    put("sessionId", sid)
                    put("modes", buildJsonObject {
                        put("currentModeId", "default")
                        putJsonArray("availableModes") {
                            addJsonObject { put("id", "default"); put("name", "Default") }
                            addJsonObject { put("id", "plan"); put("name", "Plan") }
                            addJsonObject { put("id", "yolo"); put("name", "YOLO"); put("description", "Auto-approve everything.") }
                        }
                    })
                    putJsonArray("configOptions") {
                        addJsonObject { put("id", "model"); put("name", "Model"); put("type", "select"); put("currentValue", "kimi-k2") }
                        addJsonObject { put("id", "mode"); put("name", "Mode"); put("type", "select"); put("currentValue", "default") }
                        addJsonObject { put("id", "thinking"); put("name", "Thinking"); put("type", "boolean"); put("currentValue", true) }
                    }
                })
            }

            "session/load", "session/resume" -> {
                if (params["cwd"]?.jsonPrimitive?.content.isNullOrBlank()) { fail(id, -32602, "cwd is required"); return }
                reply(id, buildJsonObject {
                    put("modes", buildJsonObject { put("currentModeId", "default"); putJsonArray("availableModes") {} })
                })
            }

            "session/list" -> reply(id, buildJsonObject {
                putJsonArray("sessions") {
                    st.sessions.forEach { (k, v) -> addJsonObject { put("sessionId", k); put("cwd", v["cwd"] ?: ""); put("title", "mock ${k.takeLast(4)}") } }
                }
            })

            "session/fork" -> {
                if (params["cwd"]?.jsonPrimitive?.content.isNullOrBlank()) { fail(id, -32602, "cwd is required"); return }
                val src = params["sessionId"]?.jsonPrimitive?.content
                val sid = "session_%016x".format(System.nanoTime())
                st.sessions[sid] = (st.sessions[src]?.toMutableMap() ?: mutableMapOf("cwd" to "/tmp"))
                reply(id, buildJsonObject { put("sessionId", sid) })
            }

            "session/close" -> { st.sessions.remove(params["sessionId"]?.jsonPrimitive?.content); reply(id, buildJsonObject {}) }
            "session/delete" -> {
                val sid = params["sessionId"]?.jsonPrimitive?.content
                if (!st.sessions.containsKey(sid)) { fail(id, -32602, "no such session"); return }
                st.sessions.remove(sid); reply(id, buildJsonObject {})
            }
            "session/set_mode", "session/set_config_option", "session/set_model" -> reply(id, buildJsonObject {})

            "session/prompt" -> {
                val sid = params["sessionId"]?.jsonPrimitive?.content ?: ""
                val cwd = st.sessions[sid]?.get("cwd") ?: "/tmp"
                st.turn++
                val path = "$cwd/config.json"

                update(sid, textChunk("agent_thought_chunk", "Разбираюсь в задаче…"))
                update(sid, buildJsonObject {
                    put("sessionUpdate", "plan")
                    putJsonArray("entries") {
                        addJsonObject { put("content", "Прочитать конфиг"); put("priority", "medium"); put("status", "in_progress") }
                        addJsonObject { put("content", "Внести правку"); put("priority", "high"); put("status", "pending") }
                        addJsonObject { put("content", "Собрать и проверить"); put("priority", "medium"); put("status", "pending") }
                    }
                })

                update(sid, toolCall("c1", "Read $path", "read", "in_progress", path))
                val readResp = requestClient("fs/read_text_file", buildJsonObject { put("sessionId", sid); put("path", path) })
                val oldText = readResp?.get("content")?.jsonPrimitive?.content ?: "(клиент отказал или fs недоступен)"
                update(sid, toolDone("c1", "completed", buildJsonObject {
                    put("type", "content"); put("content", buildJsonObject { put("type", "text"); put("text", oldText.take(400)) })
                }))

                update(sid, toolCall("c2", "Edit $path", "edit", "pending", path))
                val perm = requestClient("session/request_permission", buildJsonObject {
                    put("sessionId", sid)
                    put("toolCall", buildJsonObject { put("toolCallId", "c2"); put("title", "Edit $path"); put("kind", "edit"); put("status", "pending") })
                    putJsonArray("options") {
                        addJsonObject { put("optionId", "allow_once"); put("name", "Разрешить"); put("kind", "allow_once") }
                        addJsonObject { put("optionId", "allow_always"); put("name", "Всегда для папки"); put("kind", "allow_always") }
                        addJsonObject { put("optionId", "reject_once"); put("name", "Отклонить"); put("kind", "reject_once") }
                    }
                })
                val chosen = perm?.get("outcome")?.jsonObject?.get("optionId")?.jsonPrimitive?.content
                val newText = "{\n  \"editedBy\": \"mock-kotlin\",\n  \"turn\": ${st.turn}\n}"
                if (chosen == "allow_once" || chosen == "allow_always") {
                    requestClient("fs/write_text_file", buildJsonObject { put("sessionId", sid); put("path", path); put("content", newText) })
                    update(sid, toolDone("c2", "completed", buildJsonObject {
                        put("type", "diff"); put("path", path); put("oldText", oldText); put("newText", newText)
                    }))
                } else {
                    update(sid, toolDone("c2", "failed"))
                }

                update(sid, toolCall("c3", "Run `node --version`", "execute", "in_progress"))
                val term = requestClient("terminal/create", buildJsonObject {
                    put("sessionId", sid); put("command", "node"); putJsonArray("args") { add("--version") }; put("cwd", cwd); putJsonArray("env") {}
                })
                val tid = term?.get("terminalId")?.jsonPrimitive?.content
                if (tid != null) {
                    requestClient("terminal/wait_for_exit", buildJsonObject { put("sessionId", sid); put("terminalId", tid) })
                    val out = requestClient("terminal/output", buildJsonObject { put("sessionId", sid); put("terminalId", tid) })
                    requestClient("terminal/release", buildJsonObject { put("sessionId", sid); put("terminalId", tid) })
                    update(sid, toolDone("c3", "completed", buildJsonObject {
                        put("type", "content"); put("content", buildJsonObject { put("type", "text"); put("text", (out?.get("output")?.jsonPrimitive?.content ?: "").trim()) })
                    }))
                } else {
                    update(sid, toolDone("c3", "failed"))
                }

                // Два апдейта, без которых тест не видел бы 8 из 11 вариантов SessionUpdate:
                // real Kimi шлёт их на каждом turn (реестр слэш-команд и смена конфигурации).
                update(sid, buildJsonObject {
                    put("sessionUpdate", "available_commands_update")
                    putJsonArray("availableCommands") {
                        addJsonObject { put("name", "build"); put("description", "Собрать проект") }
                        addJsonObject { put("name", "deploy"); put("description", "Выложить на itch") }
                    }
                })
                update(sid, buildJsonObject {
                    put("sessionUpdate", "config_option_update")
                    putJsonArray("configOptions") {
                        addJsonObject {
                            put("id", "model"); put("name", "Модель"); put("type", "select")
                            put("currentValue", "kimi-code")
                            putJsonArray("options") {
                                addJsonObject { put("value", "kimi-code"); put("name", "Kimi Code") }
                            }
                        }
                    }
                })
                update(sid, buildJsonObject {
                    put("sessionUpdate", "usage_update"); put("used", 4213L); put("size", 200000L)
                    put("cost", buildJsonObject { put("amount", 0.037); put("currency", "USD") })
                })

                //elicitation/create: клиент обязан ответить, а не подвесить ход
                val elicit = requestClient("elicitation/create", buildJsonObject {
                    put("sessionId", sid)
                    put("message", "Деплоить на itch сейчас?")
                    put("requestedSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("deploy") { put("type", "boolean"); put("description", "Публикация") }
                        }
                        putJsonArray("required") { add("deploy") }
                    })
                })
                val accepted = elicit?.get("action")?.jsonPrimitive?.content == "accept"
                update(sid, textChunk("agent_message_chunk",
                    if (accepted) "Готово. Ход №${st.turn} завершён — конфиг обновлён, терминал отработал, спрашивал про деплой."
                    else "Готово. Ход №${st.turn} завершён; на деплой ответа не было."))
                reply(id, buildJsonObject { put("stopReason", "end_turn") })
            }

            else -> fail(id, -32601, "method not found: $method")
        }
    }
}

fun main() {
    MockAcpAgent(
        InputStreamReader(System.`in`, Charsets.UTF_8).buffered(),
        OutputStreamWriter(System.out, Charsets.UTF_8).buffered(),
    ).run()
}
