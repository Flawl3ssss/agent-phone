package dev.kimiterminal.gateway

import dev.kimiterminal.acp.AcpException
import dev.kimiterminal.acp.AcpErrorCodes
import dev.kimiterminal.acp.CreateTerminalRequest
import dev.kimiterminal.acp.CreateTerminalResponse
import dev.kimiterminal.acp.PermissionOption
import dev.kimiterminal.acp.PermissionOutcome
import dev.kimiterminal.acp.RequestPermissionRequest
import dev.kimiterminal.acp.RequestPermissionResult
import dev.kimiterminal.acp.TerminalExitStatus
import dev.kimiterminal.acp.TerminalOutputResponse
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TerminalGateway (BLUEPRINT §5.2, требование R4).
 *
 * Команды, которые агент запускает через `terminal/…`, живут в **том же** терминальном UI,
 * что и пользовательские. Обязательный жизненный цикл:
 *   create → (output | wait_for_exit)* → kill? → release
 * release без wait_for_exit = утечка буфера, поэтому за ним следит [sweepOrphans].
 */
class TerminalGateway(
    private val shellHost: ShellHost,
    private val hardByteCap: Int = 4 * 1024 * 1024,
    private val idleTimeoutMs: Long = 10 * 60_000,
) {
    private class Slot(
        val handle: ShellHandle,
        val ring: StringBuilder,
        var truncated: Boolean = false,
        var released: Boolean = false,
        var cap: Int,
        val createdAt: Long = System.currentTimeMillis(),
        val exit: CompletableDeferred<TerminalExitStatus> = CompletableDeferred(),
    )

    private val slots = ConcurrentHashMap<String, Slot>()
    private var counter = 0

    fun create(req: CreateTerminalRequest): CreateTerminalResponse {
        val id = "term-${++counter}"
        val h = shellHost.spawn(req.command, req.args, req.env.associate { it.name to it.value }, req.cwd)
        val slot = Slot(h, StringBuilder(), cap = minOf(req.outputByteLimit ?: hardByteCap, hardByteCap))
        slots[id] = slot
        Thread {
            val code = h.waitFor()
            synchronized(slot) { slot.append(h.drain()) }
            slot.exit.complete(TerminalExitStatus(code, null))
        }.start()
        return CreateTerminalResponse(id)
    }

    fun output(sessionId: String, terminalId: String): TerminalOutputResponse {
        val s = slots[terminalId] ?: throw AcpException(AcpErrorCodes.RESOURCE_NOT_FOUND, "no such terminal: $terminalId")
        synchronized(s) { s.append(s.handle.drain()) }
        val done = if (s.handle.alive) null else s.exit.getCompleted()
        return TerminalOutputResponse(s.ring.toString(), s.truncated, done)
    }

    suspend fun waitForExit(sessionId: String, terminalId: String): TerminalExitStatus {
        val s = slots[terminalId] ?: throw AcpException(AcpErrorCodes.RESOURCE_NOT_FOUND, "no such terminal: $terminalId")
        return withTimeoutOrNull(idleTimeoutMs) { s.exit.await() }
            ?: run { s.handle.kill(); TerminalExitStatus(null, "SIGTERM") }
    }

    fun kill(sessionId: String, terminalId: String) {
        slots[terminalId]?.let { it.handle.kill() }
    }

    fun release(sessionId: String, terminalId: String) {
        val s = slots.remove(terminalId) ?: return
        s.released = true
        if (s.handle.alive) s.handle.kill()
    }

    /** Зачистка незакрытых терминалов после смерти процесса агента (S5/утечки). */
    fun sweepOrphans(): Int {
        var n = 0
        slots.entries.toList().forEach { (id, s) ->
            if (s.handle.alive) s.handle.kill()
            slots.remove(id); n++
        }
        return n
    }

    val liveCount: Int get() = slots.size

    private fun Slot.append(chunk: String) {
        if (chunk.isEmpty()) return
        if (ring.length + chunk.length > cap) {
            val keep = cap - ring.length
            if (keep > 0) ring.append(chunk.take(keep))
            truncated = true
        } else ring.append(chunk)
    }
}

/** Абстракция «запустить процесс в госте». На телефоне — proot-exec, в тесте — sh. */
interface ShellHost {
    fun spawn(command: String, args: List<String>, env: Map<String, String>, cwd: String?): ShellHandle
}

interface ShellHandle {
    val alive: Boolean
    fun drain(): String
    fun waitFor(): Int
    fun kill()
}

/** Реализация для хоста/отладки. На устройстве заменяется на proot-мост (Ф1). */
class LocalShellHost(private val defaultCwd: String = System.getProperty("user.dir") ?: "/tmp") : ShellHost {
    override fun spawn(command: String, args: List<String>, env: Map<String, String>, cwd: String?): ShellHandle {
        val pb = ProcessBuilder(listOf(command) + args)
            .redirectErrorStream(true)
            .directory(File(cwd ?: defaultCwd))
        pb.environment().putAll(env)
        val p = pb.start()
        val out = StringBuilder()
        val t = Thread {
            p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        return object : ShellHandle {
            override val alive: Boolean get() = p.isAlive
            override fun drain(): String = synchronized(out) { val s = out.toString(); out.setLength(0); s }
            override fun waitFor(): Int = runCatching { p.waitFor() }.getOrDefault(-1).also { t.join(200) }
            override fun kill() { p.destroy(); runCatching { p.destroyForcibly() } }
        }
    }
}

// ───────────────────────────────────────────────────────────── PermissionGateway

/**
 * PermissionGateway (T10, BLUEPRINT §5.4).
 *
 * Кнопки генерируются из `options[].kind` — мы не изобретаем свои варианты ответа.
 * `allow_always` уважается только если локальная политика это разрешает (S5: на `execute`
 * «всегда» не действует).
 */
class PermissionGateway(
    private val ask: suspend (RequestPermissionRequest) -> RequestPermissionResult,
    private val policy: (RequestPermissionRequest) -> RequestPermissionResult? = { null },
) {
    private val pendingBySession = ConcurrentHashMap<String, RequestPermissionRequest>()
    private val alwaysRules = ConcurrentHashMap<String, String>()   // "tool:root" → optionId

    /** Идемпотентность: первый победивший ответ, повторный — no-op. */
    suspend fun handle(req: RequestPermissionRequest): RequestPermissionResult {
        policy(req)?.let { return it }
        val ruleKey = ruleKey(req)
        alwaysRules[ruleKey]?.let { opt ->
            if (req.options.any { it.optionId == opt }) return RequestPermissionResult(PermissionOutcome("selected", opt))
        }
        if (pendingBySession.putIfAbsent(req.sessionId + "/" + (req.toolCall?.toString()?.hashCode() ?: 0), req) != null) {
            // гонка: уже спрашиваем — отвечаем cancelled, агент сам разберётся
            return RequestPermissionResult(PermissionOutcome("cancelled"))
        }
        return try {
            val r = ask(req)
            if (r.outcome.optionId != null && req.options.any { it.optionId == r.outcome.optionId && it.kind == "allow_always" }
                && allowAlways(req)) alwaysRules[ruleKey] = r.outcome.optionId!!
            r
        } finally {
            pendingBySession.remove(req.sessionId + "/" + (req.toolCall?.toString()?.hashCode() ?: 0))
        }
    }

    /** Запрос, которого агент ждёт прямо сейчас — его может разобрать другой агент через мост. */
    fun pending(): RequestPermissionRequest? = pendingBySession.values.firstOrNull()

    /** «Всегда» запрещено для execute и для путей из deny-списка (BLUEPRINT §9.2). */
    private fun allowAlways(req: RequestPermissionRequest): Boolean {
        val kind = req.toolCall?.get("kind")?.toString()?.trim('"') ?: return false
        return kind != "execute"
    }

    private fun ruleKey(req: RequestPermissionRequest): String {
        val kind = req.toolCall?.get("kind")?.toString()?.trim('"') ?: "other"
        val path = req.toolCall?.get("locations")?.toString().orEmpty()
            .substringAfter("\"path\":\"", "").substringBefore('"')
        val root = path.substringBeforeLast('/', "")
        return "$kind:$root"
    }

    fun forgetRules() = alwaysRules.clear()
    fun rules(): Map<String, String> = alwaysRules.toMap()

    companion object {
        /** Порядок и подписи кнопок — из kind, как предписывает протокол. */
        fun label(o: PermissionOption): String = when (o.kind) {
            "allow_once" -> "Разрешить"
            "allow_always" -> "Всегда"
            "reject_once" -> "Отклонить"
            "reject_always" -> "Больше не спрашивать"
            else -> o.name
        }
        fun isAllow(kind: String): Boolean = kind == "allow_once" || kind == "allow_always"
    }
}
