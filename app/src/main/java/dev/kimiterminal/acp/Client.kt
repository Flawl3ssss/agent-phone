package dev.kimiterminal.acp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Клиент Agent Client Protocol v1.
 *
 * ACP двусторонний: агент **тоже шлёт запросы** — `fs/…`, `terminal/…`,
 * `session/request_permission`, `elicitation/create`. Именно на этой двусторонности
 * держится вся архитектура приложения (BLUEPRINT §5): три шлюза привилегий — это
 * обработчики входящих запросов, а не «дополнение» к клиенту.
 *
 * Диспетчер входящих запросов **конкурентный**. Последовательный даёт гарантированный
 * deadlock: агент ждёт ответ на `request_permission`, а мы в этот момент не можем
 * принять следующий кадр, потому что обрабатываем предыдущий.
 */
class AcpClient(
    private val link: AcpLink,
    private val scope: CoroutineScope,
    private val requestTimeoutMs: Long = 120_000,
) {
    /** Обработчики входящих запросов агента. Лювый null → ответ methodNotFound. */
    class Handlers {
        var requestPermission: (suspend (RequestPermissionRequest) -> RequestPermissionResult)? = null
        var readTextFile: (suspend (ReadTextFileRequest) -> ReadTextFileResponse)? = null
        var writeTextFile: (suspend (WriteTextFileRequest) -> Unit)? = null
        var createTerminal: (suspend (CreateTerminalRequest) -> CreateTerminalResponse)? = null
        var terminalOutput: (suspend (String, String) -> TerminalOutputResponse)? = null
        var waitForTerminalExit: (suspend (String, String) -> TerminalExitStatus)? = null
        var killTerminal: (suspend (String, String) -> Unit)? = null
        var releaseTerminal: (suspend (String, String) -> Unit)? = null
        var createElicitation: (suspend (ElicitationRequest) -> ElicitationResponse)? = null
        var sessionUpdate: ((SessionNotification) -> Unit)? = null
    }

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeMutex = Mutex()
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)
    private var handlers: Handlers = Handlers()

    private val _updates = Channel<SessionNotification>(Channel.UNLIMITED)
    val updates = _updates

    private val _wire = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val wire: SharedFlow<String> = _wire

    private val _state = MutableStateFlow(ClientState.DISCONNECTED)
    val state: StateFlow<ClientState> = _state

    enum class ClientState { DISCONNECTED, HANDSHAKING, READY, DEAD }

    /** Счётчик необработанных входящих запросов — для диагностики подвешивания. */
    @Volatile var inFlightInbound: Int = 0
        private set

    fun startDispatch(h: Handlers) {
        handlers = h
        scope.launch {
            for (msg in link.incoming) {
                // ВАЖНО: не ждём. Каждый кадр уходит в свою корутину.
                launch { runCatching { dispatch(msg) } }
            }
            _state.value = ClientState.DEAD
            pending.values.forEach { it.completeExceptionally(AcpException(-32000, "ACP connection closed")) }
            pending.clear()
        }
    }

    // ── исходящие ────────────────────────────────────────────────────────────

    suspend fun initialize(
        caps: ClientCapabilities = ClientCapabilities(
            fs = FileSystemCapabilities(readTextFile = true, writeTextFile = true),
            terminal = true,
        ),
        clientInfo: Implementation = Implementation("agent-phone", "Agent Phone", "0.2.0"),
    ): InitializeResponse {
        _state.value = ClientState.HANDSHAKING
        val r = request("initialize", acpJson.encodeToJsonElement(InitializeRequest.serializer(), InitializeRequest(ACP_PROTOCOL_VERSION, caps, clientInfo)))
        _state.value = ClientState.READY
        return acpJson.decodeFromJsonElement(InitializeResponse.serializer(), r)
    }

    suspend fun authenticate(methodId: String) =
        request("authenticate", buildJsonObject { put("methodId", methodId) })

    suspend fun newSession(cwd: String, additionalDirectories: List<String> = emptyList(), mcpServers: List<McpServer> = emptyList()): NewSessionResponse {
        val p = NewSessionRequest(cwd, additionalDirectories, mcpServers)
        return acpJson.decodeFromJsonElement(NewSessionResponse.serializer(),
            request("session/new", acpJson.encodeToJsonElement(NewSessionRequest.serializer(), p)))
    }

    /** `cwd` обязателен и здесь, и в resume, и в fork — проверено живым прогоном (-32602 без него). */
    suspend fun loadSession(sessionId: String, cwd: String, mcpServers: List<McpServer> = emptyList()) =
        request("session/load", buildJsonObject {
            put("sessionId", sessionId); put("cwd", cwd)
            put("mcpServers", encodeList(McpServer.serializer(), mcpServers))
        })

    suspend fun resumeSession(sessionId: String, cwd: String) =
        request("session/resume", buildJsonObject { put("sessionId", sessionId); put("cwd", cwd) })

    suspend fun listSessions(cwd: String? = null, cursor: String? = null): ListSessionsResponse {
        val body = buildJsonObject {
            if (cwd != null) put("cwd", cwd)
            if (cursor != null) put("cursor", cursor)
        }
        return acpJson.decodeFromJsonElement(ListSessionsResponse.serializer(), request("session/list", body))
    }

    suspend fun forkSession(sessionId: String, cwd: String): String {
        val r = request("session/fork", buildJsonObject {
            put("sessionId", sessionId); put("cwd", cwd)
            put("mcpServers", buildJsonArrayLike())
        })
        return r.jsonObject["sessionId"]?.jsonPrimitive?.content
            ?: throw AcpException(-32603, "fork без sessionId")
    }

    private fun buildJsonArrayLike(): JsonElement =
        kotlinx.serialization.json.JsonArray(emptyList())

    suspend fun closeSession(sessionId: String) =
        request("session/close", buildJsonObject { put("sessionId", sessionId) })

    suspend fun deleteSession(sessionId: String) =
        request("session/delete", buildJsonObject { put("sessionId", sessionId) })

    suspend fun setMode(sessionId: String, modeId: String) =
        request("session/set_mode", buildJsonObject { put("sessionId", sessionId); put("modeId", modeId) })

    suspend fun setConfigOption(sessionId: String, configId: String, value: String) =
        request("session/set_config_option", buildJsonObject {
            put("sessionId", sessionId); put("configId", configId); put("value", value)
        })

    /** Расширение Kimi (не в AGENT_METHODS SDK 1.4) — шлём как есть. */
    suspend fun setModel(sessionId: String, modelId: String) =
        request("session/set_model", buildJsonObject { put("sessionId", sessionId); put("modelId", modelId) })

    suspend fun prompt(sessionId: String, blocks: List<ContentBlock>): PromptResponse {
        val body = buildJsonObject {
            put("sessionId", sessionId)
            put("prompt", encodeList(ContentBlock.serializer(), blocks))
        }
        // prompt — долгий запрос: таймаут на него не ставим совсем
        return acpJson.decodeFromJsonElement(PromptResponse.serializer(), requestNoTimeout("session/prompt", body))
    }

    suspend fun cancel(sessionId: String) = notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })

    suspend fun logout() = request("logout", buildJsonObject {})

    /** Произвольный метод — для негативных тестов и расширений вне SDK. */
    suspend fun rawRequest(method: String, params: JsonElement = buildJsonObject {}): JsonElement =
        request(method, params)

    // ── JSON-RPC ─────────────────────────────────────────────────────────────

    private suspend fun request(method: String, params: JsonElement): JsonElement =
        withTimeoutOrNull(requestTimeoutMs) { requestNoTimeout(method, params) }
            ?: throw AcpException(-32000, "timeout after ${requestTimeoutMs}ms: $method")

    private suspend fun requestNoTimeout(method: String, params: JsonElement): JsonElement {
        val id = nextId.incrementAndGet()
        val d = CompletableDeferred<JsonElement>()
        pending[id] = d
        try {
            write(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
            })
            return d.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AcpException) {
            throw e
        } catch (e: Exception) {
            throw AcpException(AcpErrorCodes.INTERNAL_ERROR, "$method: ${e.message}")
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun notify(method: String, params: JsonElement) = write(
        buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) }
    )

    private suspend fun write(obj: JsonObject) = writeMutex.withLock {
        if (!link.alive.value) throw AcpException(-32000, "transport closed")
        runCatching { _wire.tryEmit("→ ${obj.toString().take(400)}") }
        link.send(obj)
    }

    // ── входящие ─────────────────────────────────────────────────────────────

    private suspend fun dispatch(msg: JsonObject) {
        runCatching { _wire.tryEmit("← ${msg.toString().take(600)}") }
        val hasMethod = msg.containsKey("method")
        val hasId = msg.containsKey("id") && msg["id"] !is JsonNull

        if (!hasMethod && hasId) {
            val id = msg["id"]!!.jsonPrimitive.long
            val d = pending.remove(id) ?: return
            val err = msg["error"]
            if (err is JsonObject) {
                d.completeExceptionally(
                    AcpException(
                        err["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                        err["message"]?.jsonPrimitive?.content ?: "?",
                        err["data"],
                    )
                )
            } else d.complete(msg["result"] ?: JsonObject(emptyMap()))
            return
        }

        if (!hasMethod) return

        val method = msg["method"]!!.jsonPrimitive.content

        if (!hasId) {                                       // уведомление
            if (method == "session/update") {
                val n = acpJson.decodeFromJsonElement(SessionNotification.serializer(), msg.getValue("params"))
                runCatching { handlers.sessionUpdate?.invoke(n) }
                runCatching { _updates.trySend(n) }
            }
            return
                                        }

        // входящий запрос — обязаны ответить, иначе агент висит навсегда
        val id = msg["id"]!!
        inFlightInbound++
        val params = msg["params"] as? JsonObject ?: buildJsonObject {}
        val outcome = try {
            "result" to route(method, params)
        } catch (e: AcpException) {
            "error" to errorBody(e.code, e.message)
        } catch (e: Exception) {
            "error" to errorBody(AcpErrorCodes.INTERNAL_ERROR, e.message ?: "internal")
        } finally {
            inFlightInbound--
        }
        write(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put(outcome.first, outcome.second)
        })
    }

    private fun errorBody(code: Int, message: String) = buildJsonObject {
        put("code", code); put("message", message)
    }

    private suspend fun route(method: String, params: JsonObject): JsonObject = when (method) {
        "session/request_permission" -> encode(RequestPermissionResult.serializer(),
            handlers.requestPermission?.invoke(decode(RequestPermissionRequest.serializer(), params))
                ?: RequestPermissionResult(PermissionOutcome("cancelled")))
        "fs/read_text_file" -> encode(ReadTextFileResponse.serializer(),
            handlers.readTextFile?.invoke(decode(ReadTextFileRequest.serializer(), params))
                ?: throw AcpException(AcpErrorCodes.METHOD_NOT_FOUND, "клиент не объявил fs.readTextFile"))
        "fs/write_text_file" -> {
            handlers.writeTextFile?.invoke(decode(WriteTextFileRequest.serializer(), params))
                ?: throw AcpException(AcpErrorCodes.METHOD_NOT_FOUND, "клиент не объявил fs.writeTextFile")
            buildJsonObject {}
        }
        "terminal/create" -> encode(CreateTerminalResponse.serializer(),
            handlers.createTerminal?.invoke(decode(CreateTerminalRequest.serializer(), params))
                ?: throw AcpException(AcpErrorCodes.METHOD_NOT_FOUND, "клиент не объявил terminal"))
        "terminal/output" -> encode(TerminalOutputResponse.serializer(),
            handlers.terminalOutput?.invoke(sid(params), tid(params))
                ?: throw AcpException(AcpErrorCodes.METHOD_NOT_FOUND, "нет terminal/output"))
        "terminal/wait_for_exit" -> buildJsonObject {
            put("exitStatus", encodeObj(TerminalExitStatus.serializer(),
                handlers.waitForTerminalExit?.invoke(sid(params), tid(params)) ?: TerminalExitStatus(0, null)))
        }
        "terminal/kill" -> { handlers.killTerminal?.invoke(sid(params), tid(params)); buildJsonObject {} }
        "terminal/release" -> { handlers.releaseTerminal?.invoke(sid(params), tid(params)); buildJsonObject {} }
        "elicitation/create" -> encode(ElicitationResponse.serializer(),
            handlers.createElicitation?.invoke(
                ElicitationRequest(
                    sessionId = params["sessionId"]?.jsonPrimitive?.content ?: "",
                    mode = params["mode"]?.jsonPrimitive?.content ?: "form",
                    message = params["message"]?.jsonPrimitive?.content,
                    requestedSchema = params["requestedSchema"] as? JsonObject,
                )
            ) ?: ElicitationResponse("cancel", null))
        else -> throw AcpException(AcpErrorCodes.METHOD_NOT_FOUND, "клиент не поддерживает $method")
    }

    private fun sid(p: JsonObject) = p["sessionId"]?.jsonPrimitive?.content ?: ""
    private fun tid(p: JsonObject) = p["terminalId"]?.jsonPrimitive?.content ?: ""

    private inline fun <reified T> decode(s: kotlinx.serialization.KSerializer<T>, e: JsonElement): T =
        acpJson.decodeFromJsonElement(s, e)

    private fun <T> encode(s: kotlinx.serialization.KSerializer<T>, v: T): JsonObject =
        acpJson.encodeToJsonElement(s, v).jsonObject

    private fun <T> encodeObj(s: kotlinx.serialization.KSerializer<T>, v: T): JsonElement =
        acpJson.encodeToJsonElement(s, v)

    private fun <T> encodeList(s: kotlinx.serialization.KSerializer<T>, v: List<T>): JsonElement =
        acpJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(s), v)

    fun shutdown() {
        pending.values.forEach { it.completeExceptionally(AcpException(-32000, "client shutdown")) }
        pending.clear()
        runCatching { link.close() }
        _state.value = ClientState.DEAD
    }
}
