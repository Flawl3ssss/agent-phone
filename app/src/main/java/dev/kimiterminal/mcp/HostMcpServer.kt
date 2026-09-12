package dev.kimiterminal.mcp

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * HTTP MCP-сервер приложения. Слушает **только** 127.0.0.1, требует bearer-токен.
 *
 * Реализован на голых сокетах, а не на NanoHTTPD: минус зависимость от стороннего
 * кода в APK, плюс полный контроль над тем, что уходит в сеть. Протокол MCP тут
 * нужен в самом консервативном подмножестве — initialize / tools/list / tools/call.
 */
class HostMcpServer(
    private val registry: ToolRegistry,
    /** 0 — ядро выдаст свободный порт; тогда фактический порт в [port]. */
    requestedPort: Int = 0,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "host-mcp").apply { isDaemon = true } }
    private val token: String = randomToken()

    var port: Int = requestedPort
        private set

    /** URL, который отдаём в session/new. */
    fun url(): String = "http://127.0.0.1:$port/mcp"

    fun headers(): List<Pair<String, String>> = listOf("Authorization" to "Bearer $token")

    /** Проверка, что запрос пришёл от нашего же агента. */
    fun authorized(headerValue: String?): Boolean =
        headerValue != null && headerValue.removePrefix("Bearer ").trim() == token

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress("127.0.0.1", port))
        port = s.localPort
        server = s
        thread(isDaemon = true, name = "host-mcp-accept") {
            while (running.get()) {
                val sock = try { s.accept() } catch (e: IOException) { break }
                pool.execute { handle(sock) }
            }
            runCatching { s.close() }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    // ── разбор HTTP ──────────────────────────────────────────────────────────

    private fun handle(sock: java.net.Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 60_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val l = reader.readLine() ?: break
                    if (l.isEmpty()) break
                    val ix = l.indexOf(':')
                    if (ix > 0) headers[l.substring(0, ix).lowercase()] = l.substring(ix + 1).trim()
                }

                val out = s.getOutputStream()
                if (method == "GET" && path == "/health") {
                    if (!authorized(headers["authorization"])) return respond(out, 401, "unauthorized")
                    return respond(out, 200, "application/json", buildJsonObject {
                        put("ok", true); put("tools", registry.all().size)
                    }.toString())
                }
                if (method != "POST" || !path.startsWith("/mcp")) return respond(out, 404, "not found")
                if (!authorized(headers["authorization"])) return respond(out, 401, "unauthorized")

                val len = headers["content-length"]?.toIntOrNull() ?: return respond(out, 411, "length required")
                if (len > 8 * 1024 * 1024) return respond(out, 413, "too large")
                val body = CharArray(len)
                var read = 0
                while (read < len) {
                    val n = reader.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val text = String(body, 0, read)
                val reply = process(text)
                respond(out, 200, "application/json", reply)
            } catch (e: Exception) {
                runCatching { respond(s.getOutputStream(), 500, "error: ${e.message}") }
            }
        }
    }

    private fun respond(out: OutputStream, status: Int, body: String) = respond(out, status, "text/plain", body)

    private fun respond(out: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "ERR"}\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ── JSON-RPC / MCP ───────────────────────────────────────────────────────

    /** Возвращает строку-ответ. Уведомления (без id) дают "". */
    fun process(requestText: String): String {
        val msg = runCatching { Json.parseToJsonElement(requestText).jsonObject }.getOrNull()
            ?: return error(null, -32700, "parse error")
        val id = msg["id"]
        val method = msg["method"]?.jsonPrimitive?.content ?: return error(id, -32600, "no method")
        val params = msg["params"] as? JsonObject ?: buildJsonObject {}

        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", JsonPrimitive(MCP_PROTOCOL_VERSION))
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                })
                put("serverInfo", buildJsonObject {
                    put("name", "agent-phone-host")
                    put("title", "Agent Phone host bridge")
                    put("version", "0.2.0")
                })
                put("instructions", JsonPrimitive(INSTRUCTIONS))
            })

            "notifications/initialized", "initialized" -> ""

            "ping" -> result(id, buildJsonObject { })

            "tools/list" -> result(id, buildJsonObject { put("tools", registry.toolsJson()) })

            "tools/call" -> {
                val name = params["name"]?.jsonPrimitive?.content
                val args = params["arguments"] as? JsonObject ?: buildJsonObject {}
                val tool = name?.let { registry.find(it) }
                if (tool == null) {
                    result(id, buildJsonObject {
                        put("isError", true)
                        put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "нет такого инструмента: $name") })))
                    })
                } else {
                    val text = try {
                        runBlocking { tool.handler(args) }
                    } catch (e: Exception) {
                        "ошибка выполнения: ${e.message}"
                    }
                    result(id, buildJsonObject {
                        put("isError", false)
                        put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) })))
                    })
                }
            }

            else -> error(id, -32601, "method not found: $method")
        }
    }

    private fun result(id: JsonElement?, value: JsonObject): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("result", value)
        })

    private fun error(id: JsonElement?, code: Int, message: String): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        })

    private fun randomToken(): String {
        val b = ByteArray(24); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Версия протокола MCP (дата-строка), а не версия ACP: не путать. */
        const val MCP_PROTOCOL_VERSION = "2025-06-18"

        const val INSTRUCTIONS =
            "Это мост приложения Agent Phone. Ты говоришь с ним через эти инструменты, а не через файлы. " +
            "app_settings_* — настройки самого приложения (тема, лимиты, эгрегс-политика). " +
            "app_state / app_sessions_list / app_transcript — что сейчас происходит на экране пользователя. " +
            "browser_* — встроенный браузер: навигация, JS, скриншоты, консоль. Для игрового dev-loop " +
            "правильный цикл: правь код → browser_reload → browser_console (ошибки) → browser_screenshot (вид). " +
            "Скриншот дорогой, DOM и консоль дешёвые — начинай с них. " +
            "Опасные инструменты помечены и проходят через подтверждение пользователя."
    }
}
