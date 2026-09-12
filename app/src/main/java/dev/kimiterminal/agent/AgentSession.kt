package dev.kimiterminal.agent

import dev.kimiterminal.acp.AcpAgentProcess
import dev.kimiterminal.acp.AcpClient
import dev.kimiterminal.acp.AcpException
import dev.kimiterminal.acp.ContentBlock
import dev.kimiterminal.acp.ElicitationRequest
import dev.kimiterminal.acp.ElicitationResponse
import dev.kimiterminal.acp.InitializeResponse
import dev.kimiterminal.acp.NewSessionResponse
import dev.kimiterminal.acp.PermissionOption
import dev.kimiterminal.acp.PermissionOutcome
import dev.kimiterminal.acp.PromptResponse
import dev.kimiterminal.acp.RequestPermissionRequest
import dev.kimiterminal.acp.RequestPermissionResult
import dev.kimiterminal.acp.SessionNotification
import dev.kimiterminal.acp.SessionStateMachine
import dev.kimiterminal.acp.StopReason
import dev.kimiterminal.gateway.CheckpointStore
import dev.kimiterminal.gateway.FsGateway
import dev.kimiterminal.gateway.LocalShellHost
import dev.kimiterminal.gateway.PathPolicy
import dev.kimiterminal.gateway.PermissionGateway
import dev.kimiterminal.gateway.TerminalGateway
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Оркестратор одной ACP-сессии: клиент + три шлюза + лента.
 *
 * Здесь «функции из плана» становятся одним графом: состояние (T2), карточки (T4),
 * подтверждения (T10), чекпоинты (T3) и откат (R8) выводятся из одних и тех же
 * протокольных событий. Отдельных парсеров нет.
 */

sealed class Item(val id: Long) {
    companion object { private val seq = AtomicLong(0) }

    class User(text: String) : Item(seq.incrementAndGet()) { val text: String = text }
    class AgentText(text: String) : Item(seq.incrementAndGet()) { val text: String = text }
    class Thought(text: String) : Item(seq.incrementAndGet()) { val text: String = text }
    class Notice(text: String, error: Boolean = false) : Item(seq.incrementAndGet()) {
        val text: String = text; val error: Boolean = error
    }
    class Tool(
        val toolCallId: String, val title: String, val kind: String, var status: String,
        val path: String? = null, var output: String? = null, var diffNew: String? = null, var diffOld: String? = null,
    ) : Item(seq.incrementAndGet())

    class Plan(val entries: List<dev.kimiterminal.acp.PlanEntry>) : Item(seq.incrementAndGet())
    class Usage(val used: Long, val size: Long, val amount: Double?, val currency: String?) : Item(seq.incrementAndGet())
}

/** Диалог подтверждения, ожидающий ответа из UI. */
data class PermissionPrompt(
    val request: RequestPermissionRequest,
    val reply: (PermissionOutcome) -> Unit,
) {
    val title: String get() = request.toolCall.str("title")
    val kind: String get() = request.toolCall.str("kind")
    val path: String get() = request.toolCall?.get("locations").toString()
        .substringAfter("\"path\":\"", "").substringBefore('"')
}

private fun JsonObject?.str(key: String): String =
    this?.get(key)?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: ""

/** Запуск агента. mode="mock" — Kotlin-агент в отдельном процессе, mode="kimi" — реальный `kimi acp`. */
class AgentLauncher(private val javaBin: String, private val classpath: String) {
    fun command(mode: String, extra: List<String> = emptyList()): List<String> = when (mode) {
        "mock", "inproc" -> listOf(javaBin, "-cp", classpath, "dev.kimiterminal.agent.MockKimiMainKt")
        "kimi" -> listOf("kimi", "acp") + extra
        else -> listOf(*mode.split(' ').toTypedArray()) + extra
    }
    companion object {
        /** Класспуть из системного classloader'а — работает и в JVM-тесте, и в `java -cp` на хосте. */
        // JDK 9+ системный загрузчик больше не URLClassLoader, поэтому читаем свойство.
        fun detectClasspath(): String =
            System.getProperty("java.class.path")?.takeIf { it.isNotBlank() } ?: "."
    }
}

class AgentSession(
    val cwd: String,
    private val scope: CoroutineScope,
    private val command: List<String>,
    checkpointsDir: File,
    val stateMachine: SessionStateMachine = SessionStateMachine(),
    /**
     * MCP-серверы, регистрируемые в session/new. Именно так приложение отдаёт агенту
     * свои настройки и браузер: kimi конвертирует type=http в свой http-транспорт
     * (packages/acp-server/src/convert.ts:188) и подключается сам.
     */
    private var mcpServers: List<dev.kimiterminal.acp.McpServer> = emptyList(),
    /** Окружение агента. Для локального рантайма это обязательная часть контракта:
     *  без PATH/HOME своего каталога дочерние процессы kimi не найдут ни утилит, ни дома. */
    private val environment: Map<String, String> = emptyMap(),
) {
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _permission = MutableStateFlow<PermissionPrompt?>(null)
    val permission: StateFlow<PermissionPrompt?> = _permission

    private val _init = MutableStateFlow<InitializeResponse?>(null)
    val init: StateFlow<InitializeResponse?> = _init

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId

    private val _sessionInfo = MutableStateFlow<NewSessionResponse?>(null)
    val sessionInfo: StateFlow<NewSessionResponse?> = _sessionInfo

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val stderrRing = ConcurrentLinkedDeque<String>()
    private var turnCounter = 0
    private var currentTurnId = "turn-0"

    val policy = PathPolicy(allowedRoots = listOf(cwd))
    val store = CheckpointStore(checkpointsDir)
    private var readCount = 0L
    private var writeCount = 0L
    private var deniedCount = 0L
    val stats: String get() = "fs +$readCount/-$writeCount · отказов $deniedCount · терминалов ${if (::terminals.isInitialized) terminals.liveCount else 0}"

    private val process: AcpAgentProcess? = if (command.isEmpty()) null
        else AcpAgentProcess(command.toList(), environment) { line ->
            stderrRing.addLast(line); while (stderrRing.size > 400) stderrRing.pollFirst()
        }
    /** Транспорт, заданный извне (in-process мок на Android). Имеет приоритет над [command]. */
    var injectedTransport: dev.kimiterminal.acp.AcpLink? = null
    var shutdownInProcess: (() -> Unit)? = null
    private lateinit var client: AcpClient
    private lateinit var fs: FsGateway
    internal lateinit var terminals: TerminalGateway
    internal lateinit var permissions: PermissionGateway
    internal val clientRef: AcpClient get() = client

    /** Синхронный запуск handshake + session/new (для `main()`/теста вне coroutine'а). */
    fun startBlocking(): InitializeResponse = runBlocking { start() }

    suspend fun start(): InitializeResponse {
        val transport = injectedTransport ?: process!!.start()
        client = AcpClient(transport, scope)

        fs = FsGateway(
            policy = policy,
            store = store,
            askUser = { title, detail -> askYesNo(title, detail) },
            onDiff = { d ->
                append(Item.Tool("diff-" + d.path.hashCode(), "Diff ${d.path}", "edit", "completed", d.path, null, d.newText, d.oldText))
            },
        )
        fs.turnProvider = { currentTurnId }
        terminals = TerminalGateway(LocalShellHost(cwd))
        permissions = PermissionGateway(ask = { req -> awaitPermission(req) })

        val h = AcpClient.Handlers()
        h.readTextFile = { req -> readCount++; fs.read(req) }
        h.writeTextFile = { req -> writeCount++; fs.write(req) }
        h.requestPermission = { req -> permissions.handle(req) }
        h.createTerminal = { req -> terminals.create(req) }
        h.terminalOutput = { sid, tid -> terminals.output(sid, tid) }
        h.waitForTerminalExit = { sid, tid -> terminals.waitForExit(sid, tid) }
        h.killTerminal = { sid, tid -> terminals.kill(sid, tid) }
        h.releaseTerminal = { sid, tid -> terminals.release(sid, tid) }
        h.createElicitation = { req -> elicitation(req) }
        h.sessionUpdate = { n -> onSessionUpdate(n) }
        client.startDispatch(h)

        val init = client.initialize()
        _init.value = init
        stateMachine.onConnected()
        append(Item.Notice("Подключено: ${init.agentInfo?.name} ${init.agentInfo?.version} · протокол v${init.protocolVersion} · методов агента: ${init.agentCapabilities.let { c -> listOfNotNull("loadSession".takeIf { c.loadSession }, "image".takeIf { c.promptCapabilities.image }, "fork".takeIf { c.has("fork") }, "terminal".takeIf { true }) }.joinToString() }"))
        return init
    }

    fun setMcpServers(servers: List<dev.kimiterminal.acp.McpServer>) { mcpServers = servers }

    suspend fun newSession(): String {
        val r = client.newSession(cwd, mcpServers = mcpServers)
        _sessionInfo.value = r
        _sessionId.value = r.sessionId
        append(Item.Notice("Сессия ${r.sessionId.take(18)}… · режим ${r.modes?.currentModeId} · опций: ${r.configOptions?.size ?: 0}"))
        return r.sessionId
    }

    suspend fun send(text: String): PromptResponse? {
        val sid = _sessionId.value ?: newSession()
        currentTurnId = "turn-${++turnCounter}-$sid"
        append(Item.User(text))
        _busy.value = true
        stateMachine.onPromptStarted()
        return try {
            val r = client.prompt(sid, listOf(ContentBlock.text(text)))
            stateMachine.onStop(r.stopReason)
            r
        } catch (e: AcpException) {
            deniedCount++
            stateMachine.onStop(if (e.message.contains("cancel")) StopReason.Cancelled else StopReason.Refusal)
            append(Item.Notice("Ошибка ACP ${e.code}: ${e.message}", true))
            null
        } finally {
            _busy.value = false
        }
    }

    suspend fun cancel() { _sessionId.value?.let { runCatching { client.cancel(it) } } }

    fun rollback(): Int {
        val sid = _sessionId.value ?: return 0
        val restored = store.rollbackTurn(sid, currentTurnId)
        append(Item.Notice("Откат ${currentTurnId}: восстановлено ${restored.size} файл(ов)"))
        return restored.size
    }

    fun stop() {
        if (::terminals.isInitialized) runCatching { terminals.sweepOrphans() }
        runCatching { process?.stop() }
        runCatching { shutdownInProcess?.invoke() }
        stateMachine.onProcessDied()
    }

    fun stderrTail(): List<String> = stderrRing.toList().takeLast(80)

    // ── UI-проекция протокола ────────────────────────────────────────────────

    private fun onSessionUpdate(n: SessionNotification) {
        val u = n.update
        when (u.sessionUpdate) {
            "agent_message_chunk" -> append(Item.AgentText(u.content.textOf()))
            "agent_thought_chunk" -> append(Item.Thought(u.content.textOf()))
            "user_message_chunk" -> {}
            "tool_call" -> append(Item.Tool(u.toolCallId ?: "?", u.title ?: "?", u.kind ?: "other", u.status ?: "pending", u.locations.firstOrNull()?.path))
            "tool_call_update" -> updateTool(u.toolCallId, u.status, u.contentItems?.firstOrNull()?.let { extractContent(it) })
            "plan" -> append(Item.Plan(u.entries))
            "usage_update" -> append(Item.Usage(u.used ?: 0, u.size ?: 0, u.cost?.amount, u.cost?.currency))
            "available_commands_update" -> append(Item.Notice("Команд агента: ${u.availableCommands?.size ?: 0}"))
            "current_mode_update" -> append(Item.Notice("Режим → ${u.currentModeId}"))
            "config_option_update" -> append(Item.Notice("Опции сессии обновлены"))
            "session_info_update" -> {}
            else -> append(Item.Notice("Неизвестный тип обновления: ${u.sessionUpdate} (проигнорирован)") )
        }
    }

    private fun JsonElement?.textOf(): String =
        (this as? JsonObject)?.get("text")?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: ""

    private fun extractContent(el: JsonElement): String {
        val o = el as? JsonObject ?: return ""
        return when (o["type"]?.let { (it as? JsonPrimitive)?.content }) {
            "content" -> (o["content"] as? JsonObject)?.let { c -> (c["text"] as? JsonPrimitive)?.content } ?: ""
            "diff" -> "${(o["path"] as? JsonPrimitive)?.content}: изменён (+${((o["newText"] as? JsonPrimitive)?.content ?: "").lines().size} строк)"
            "terminal" -> "terminal ${(o["terminalId"] as? JsonPrimitive)?.content}"
            else -> ""
        }
    }

    private fun updateTool(id: String?, status: String?, output: String?) {
        _items.value = _items.value.map {
            if (it is Item.Tool && it.toolCallId == id) {
                it.status = status ?: it.status; it.output = output ?: it.output
            }
            it
        }
    }

    private fun append(i: Item) { _items.value = _items.value + i }

    // ── подтверждения ────────────────────────────────────────────────────────

    private suspend fun awaitPermission(req: RequestPermissionRequest): RequestPermissionResult {
        val done = CompletableDeferred<RequestPermissionResult>()
        stateMachine.onPermissionRequested()
        _permission.value = PermissionPrompt(req) { done.complete(RequestPermissionResult(it)) }
        val r = withTimeoutOrNull(10 * 60_000) { done.await() }
            ?: RequestPermissionResult(PermissionOutcome("cancelled"))
        _permission.value = null
        stateMachine.onPermissionResolved()
        return r
    }

    private suspend fun askYesNo(title: String, detail: String): Boolean =
        awaitPermission(
            RequestPermissionRequest(
                sessionId = _sessionId.value ?: "",
                toolCall = buildJsonObject { put("title", title); put("kind", "other"); put("detail", detail) },
                options = listOf(PermissionOption("yes", "Разрешить", "allow_once"), PermissionOption("no", "Отклонить", "reject_once")),
            )
        ).let { it.outcome.optionId == "yes" }

    private suspend fun elicitation(req: ElicitationRequest): ElicitationResponse {
        val yes = askYesNo("Вопрос агента", req.message ?: "Требуется ответ")
        return ElicitationResponse(if (yes) "accept" else "decline", buildJsonObject {})
    }
}

/** Пул сессий. Одна ACP-сессия = один процесс агента (Kimi мультиплексирует внутри). */
class AgentRuntime(
    private val launcher: AgentLauncher,
    private val workRoot: File,
    private val checkpointRoot: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val sessions: StateFlow<List<AgentSession>> = _sessions

    fun spawn(
        mode: String,
        name: String = "session",
        mcpServers: List<dev.kimiterminal.acp.McpServer> = emptyList(),
    ): AgentSession {
        val cwd = File(workRoot, name).apply { mkdirs() }
        val ck = File(checkpointRoot, name).apply { mkdirs() }
        val s = AgentSession(cwd.absolutePath, scope, launcher.command(mode), ck, mcpServers = mcpServers)
        _sessions.value = _sessions.value + s
        return s
    }

    /**
     * Агент из in-app рантайма: команду и окружение собрал RuntimeLayout. Ни сокета, ни
     * внешнего терминала — процесс наш, он умирает вместе с приложением.
     */
    fun spawnLocal(
        command: List<String>,
        environment: Map<String, String>,
        name: String = "session",
        mcpServers: List<dev.kimiterminal.acp.McpServer> = emptyList(),
    ): AgentSession {
        val cwd = File(workRoot, name).apply { mkdirs() }
        val ck = File(checkpointRoot, name).apply { mkdirs() }
        val s = AgentSession(
            cwd.absolutePath, scope, command, ck,
            mcpServers = mcpServers, environment = environment,
        )
        _sessions.value = _sessions.value + s
        return s
    }

    /** Задать MCP-серверы уже созданной сессии (мост поднимается после spawn). */
    fun setMcp(s: AgentSession, servers: List<dev.kimiterminal.acp.McpServer>) = s.setMcpServers(servers)

    fun dispose(s: AgentSession) { s.stop(); _sessions.value = _sessions.value - s }
    fun shutdown() { _sessions.value.forEach { it.stop() }; scope.cancel() }
}
