package dev.kimiterminal

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.kimiterminal.acp.InitializeResponse
import dev.kimiterminal.acp.PermissionOutcome
import dev.kimiterminal.acp.SessionState
import dev.kimiterminal.agent.AgentLauncher
import dev.kimiterminal.agent.AgentRuntime
import dev.kimiterminal.agent.AgentSession
import dev.kimiterminal.mcp.BrowserControl
import dev.kimiterminal.mcp.HostMcpServer
import dev.kimiterminal.mcp.MockBrowserControl
import dev.kimiterminal.mcp.PrefsSettingsStore
import dev.kimiterminal.mcp.SessionHostState
import dev.kimiterminal.mcp.ToolRegistry
import dev.kimiterminal.agent.Item
import dev.kimiterminal.agent.PermissionPrompt
import dev.kimiterminal.service.AgentForegroundService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentForegroundService.ensureChannels(this)
    }
}

sealed class Mode {
    /** Мок-агент в отдельном JVM-процессе (хост/отладка). */
    object MockProcess : Mode()
    /** Мок-агент в этом же процессе (Android: второго JVM нет). */
    object MockInProcess : Mode()
    /** Реальный `kimi acp` внутри proot-Ubuntu. */
    data class Kimi(val viaProot: List<String> = listOf("kimi", "acp")) : Mode()
    val label: String get() = when (this) {
        is Mode.MockProcess -> "мок (процесс)"
        is Mode.MockInProcess -> "мок (in-app)"
        is Mode.Kimi -> "kimi acp"
    }
}

class AgentViewModel(
    private val appContext: Context,
    private val javaBin: String?,
    private val classpath: String?,
) : ViewModel() {

    private val workRoot = File(appContext.filesDir, "work").apply { mkdirs() }
    private val ckRoot = File(appContext.filesDir, "checkpoints").apply { mkdirs() }

    private val launcher = AgentLauncher(javaBin ?: "java", classpath ?: ".")

    /** Настройки — единый источник и для UI, и для агента (через мост). */
    val settings = PrefsSettingsStore(appContext)

    /** Ярус 1 браузера. На реальном устройстве заменяется на WebView-backed. */
    var browser: BrowserControl = MockBrowserControl()

    private var bridge: HostMcpServer? = null

    /**
     * Поднимает MCP-мост и возвращает запись для session/new. Порт выбирается ядром,
     * токен генерируется на запуск — агент получает доступ только к нашему loopback.
     */
    private fun startBridge(session: AgentSession): dev.kimiterminal.acp.McpServer {
        val registry = ToolRegistry(settings, SessionHostState(session), browser, session.permissions)
        val srv = HostMcpServer(registry).also { it.start() }
        bridge = srv
        return dev.kimiterminal.acp.McpServer(
            name = "agent-phone-host",
            type = "http",
            url = srv.url(),
            headers = srv.headers().map { dev.kimiterminal.acp.McpHeader(it.first, it.second) },
        )
    }
    private val runtime = AgentRuntime(launcher, workRoot, ckRoot)

    private val _session = MutableStateFlow<AgentSession?>(null)
    val session: StateFlow<AgentSession?> = _session

    private val _mode = MutableStateFlow<Mode>(Mode.MockInProcess)
    val mode: StateFlow<Mode> = _mode

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    // Зеркала текущей сессии. Держим стабильные ссылки, иначе Compose теряет подписку
    // при пересоздании сессии (get()-за возврат нового flow — классический баг).
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state
    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason
    private val _permission = MutableStateFlow<PermissionPrompt?>(null)
    val permission: StateFlow<PermissionPrompt?> = _permission
    private val _init = MutableStateFlow<InitializeResponse?>(null)
    val init: StateFlow<InitializeResponse?> = _init
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId
    private val _caps = MutableStateFlow<dev.kimiterminal.acp.AgentCapabilities?>(null)
    val caps: StateFlow<dev.kimiterminal.acp.AgentCapabilities?> = _caps

    fun setMode(m: Mode) { _mode.value = m }

    fun connect() {
        if (_connecting.value) return
        _connecting.value = true
        viewModelScope.launch {
            try {
                val mode = _mode.value
                val s = runtime.spawn(
                    mode = if (mode is Mode.MockProcess) "mock" else "inproc",
                    name = "proj",
                )
                // Мост поднимаем ДО session/new: агент подключается к нему на старте сессии.
                runCatching { runtime.setMcp(s, listOf(startBridge(s))) }
                if (mode is Mode.MockInProcess) {
                    val (link, stop) = dev.kimiterminal.acp.PipeTransport.inProcess { r, w ->
                        dev.kimiterminal.agent.MockAcpAgent(r, w).run()
                    }
                    s.injectedTransport = link
                    s.shutdownInProcess = stop
                }
                withContext(Dispatchers.IO) { s.startBlocking() }
                _session.value = s
                _caps.value = s.init.value?.agentCapabilities
                log("Сессия создана: ${s.cwd}")
                mirror(s)
            } catch (e: Exception) {
                log("Не удалось подключиться: ${e.message}")
            } finally {
                _connecting.value = false
            }
        }
    }

    /**
     * Одно зеркало на все потоки сессии. Из него же рисуется UI, из него же —
     * уведомление (T2): состояние не может разойтись с картинкой, потому что
     * источник один.
     */
    private fun mirror(s: AgentSession) {
        viewModelScope.launch { s.items.collect { _items.value = it } }
        viewModelScope.launch { s.permission.collect { _permission.value = it } }
        viewModelScope.launch { s.init.collect { _init.value = it } }
        viewModelScope.launch { s.busy.collect { _busy.value = it } }
        viewModelScope.launch { s.sessionId.collect { _sessionId.value = it } }
        viewModelScope.launch {
            s.stateMachine.state.collect { st ->
                _state.value = st
                _reason.value = s.stateMachine.reason.value
                val sid = s.sessionId.value ?: "—"
                withContext(Dispatchers.IO) {
                    AgentForegroundService.start(appContext, st, sid, s.stateMachine.reason.value)
                }
            }
        }
    }

    fun send(text: String) {
        val s = _session.value ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { s.send(text) }.onFailure { log("prompt: ${it.message}") } }
    }

    fun cancelTurn() { val s = _session.value ?: return; viewModelScope.launch(Dispatchers.IO) { s.cancel() } }

    fun rollback() { val s = _session.value ?: return; viewModelScope.launch(Dispatchers.IO) { s.rollback() } }

    fun answerPermission(optionId: String?) {
        val p = _session.value?.permission?.value ?: return
        p.reply(PermissionOutcome(if (optionId == null) "cancelled" else "selected", optionId))
    }

    fun disconnect() {
        _session.value?.let { runtime.dispose(it) }
        _session.value = null
    }

    fun stderrTail(): List<String> = _session.value?.stderrTail() ?: emptyList()
    val statsText: String get() = _session.value?.stats ?: "—"

    /** Перезапуск мёртвой сессии с backoff (T8). Сессии на диске гостя → resume, не потеря. */
    fun connectOrRestart() = if (_session.value == null) connect() else restart()

    fun restart() {
        val old = _session.value
        viewModelScope.launch {
            disconnect()
            old?.let { runCatching { it.stateMachine.onReset() } }
            for (attempt in 0 until dev.kimiterminal.acp.SessionStateMachine.BACKOFF_MS.size) {
                dev.kimiterminal.acp.SessionStateMachine.backoff(attempt)
                connect()
                if (_session.value?.stateMachine?.state?.value == SessionState.READY && _session.value != null) {
                    log("перезапуск удался с попытки ${attempt + 1}"); return@launch
                }
            }
            log("эскалация: ${dev.kimiterminal.acp.SessionStateMachine.BACKOFF_MS.size} перезапусков не помогли")
        }
    }

    /** R14: можно ли вообще начинать ход. */
    fun canStartTurn(ctx: Context = appContext): Pair<Boolean, String?> {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOk = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
        val temp = runCatching {
            ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        }.getOrDefault(0)
        val hot = temp > 420   // деци-Цельсии
        return when {
            hot -> false to "троттлинг: ${temp / 10f}°C"
            !batteryOk -> false to "режим экономии батареи"
            else -> true to null
        }
    }

    private fun log(s: String) { _log.value = (_log.value + s).takeLast(200) }

    override fun onCleared() { runtime.shutdown(); super.onCleared() }

    companion object {
        private val EMPTY_ITEMS = MutableStateFlow<List<Item>>(emptyList())
        private val EMPTY_STATE = MutableStateFlow(SessionState.IDLE)
        private val EMPTY_REASON = MutableStateFlow<String?>(null)
        private val EMPTY_PERM = MutableStateFlow<PermissionPrompt?>(null)
        private val EMPTY_INIT = MutableStateFlow<InitializeResponse?>(null)
        private val EMPTY_BUSY = MutableStateFlow(false)
        private val EMPTY_SID = MutableStateFlow<String?>(null)

        fun factory(ctx: Context, javaBin: String?, classpath: String?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AgentViewModel(ctx.applicationContext, javaBin, classpath) as T
        }
    }
}

/** Есть ли в системе запускаемый `java` (хост/эмулятор). На устройстве — нет. */
fun jvmAvailable(): Boolean = runCatching {
    val home = System.getProperty("java.home") ?: return false
    File(home, "bin/java").canExecute()
}.getOrDefault(false)
