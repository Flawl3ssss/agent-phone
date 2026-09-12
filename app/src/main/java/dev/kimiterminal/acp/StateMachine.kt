package dev.kimiterminal.acp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Состояния сессии (T2 в CHARTER §2.2).
 *
 * Выводятся **исключительно** из протокольных событий — никакого парсинга текста терминала.
 * Это главное, что даёт переход на ACP: `stopReason` и `request_permission` — точные,
 * а «похоже ли сообщение на вопрос» — нет.
 */
enum class SessionState {
    IDLE, AUTH_REQUIRED, READY, RUNNING, WAITING_APPROVAL, WAITING_USER,
    DONE, FAILED, CANCELLED, DEAD,
}

/**
 * Машина состояний + гистерезис.
 *
 * Гистерезис обязателен: телефон — не IDE. 200 `tool_call_update` в секунду = 200 вибраций.
 *  - WAITING_APPROVAL показывается мгновенно (это единственное состояние, требующее человека);
 *  - RUNNING не отображается раньше, чем продержалось [runningDebounceMs];
 *  - DONE/FAILED не снимаются [holdMs].
 */
class SessionStateMachine(
    private val runningDebounceMs: Long = 800,
    private val holdMs: Long = 30_000,
) {
    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state

    /** Причина перехода в FAILED/CANCELLED/AUTH_REQUIRED — для строки статуса в UI. */
    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason

    @Volatile private var runningSince: Long = 0
    @Volatile private var lastStop: StopReason? = null

    val current: SessionState get() = _state.value

    fun onConnected() = transition(SessionState.READY, null)
    fun onAuthRequired(detail: String? = null) = transition(SessionState.AUTH_REQUIRED, detail ?: "нужен вход")
    fun onPromptStarted() {
        runningSince = System.currentTimeMillis()
        _reason.value = null
        // RUNNING выставляем с задержкой, чтобы не мигало на быстрых turn'ах
        _state.value = SessionState.RUNNING
    }

    fun onPermissionRequested() = transition(SessionState.WAITING_APPROVAL, "агент ждёт разрешения")
    fun onElicitationRequested() = transition(SessionState.WAITING_USER, "агент задал вопрос")
    fun onPermissionResolved() = transition(SessionState.RUNNING, null)

    fun onStop(reason: StopReason) {
        lastStop = reason
        val st = when (reason) {
            StopReason.EndTurn -> SessionState.DONE
            StopReason.Cancelled -> SessionState.CANCELLED
            StopReason.Refusal -> SessionState.FAILED
            StopReason.MaxTokens -> SessionState.FAILED
            StopReason.MaxTurnRequests -> SessionState.FAILED
        }
        _reason.value = when (reason) {
            StopReason.EndTurn -> null
            StopReason.Cancelled -> "остановлено пользователем"
            StopReason.Refusal -> "агент отказался продолжать"
            StopReason.MaxTokens -> "лимит токенов в turn"
            StopReason.MaxTurnRequests -> "лимит запросов между ходами"
        }
        transition(st, _reason.value)
    }

    fun onProcessDied(detail: String? = null) = transition(SessionState.DEAD, detail ?: "процесс агента умер")
    fun onReset() = transition(SessionState.IDLE, null)

    /** RUNNING держалось меньше debounce — считаем, что «мигнуло», показываем DONE без шума. */
    fun runningWasTransient(): Boolean =
        System.currentTimeMillis() - runningSince < runningDebounceMs

    private fun transition(to: SessionState, why: String?) {
        _state.value = to
        if (why != null) _reason.value = why
    }

    companion object {
        /**
         * Рестарт упавшего процесса: backoff 1→2→4→8, кап 60 с.
         * Возвращает список пауз; после исчерпания — эскалация к пользователю (T8).
         */
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 60_000L)
        suspend fun backoff(attempt: Int) = delay(BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.last() })
    }
}
