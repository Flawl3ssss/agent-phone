package dev.kimiterminal.terminal

import android.content.Context
import android.system.Os
import dev.kimiterminal.runtime.RuntimeManager
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Процесс терминала: насос `libptysh.so` + оболочка внутри.
 *
 * Рабочая оболочка выбирается по состоянию рантайма: установлен — Ubuntu (`bin/sh`
 * через диспетчер имён), не установлен — Bionic `/system/bin/sh`. Терминал поэтому
 * открывается всегда: пустое окно с текстом «сначала установи рантайм» — это отказ
 * от работы, а не подсказка.
 */
class TerminalBridge(private val context: Context, private val manager: RuntimeManager?) {

    /** Буфер приходит кусками: копировать в UI целиком на каждый байт нельзя. */
    @Volatile var onOutput: ((ByteArray, Int) -> Unit)? = null

    private var process: Process? = null
    private var threads: List<Thread> = emptyList()
    val sizeFile: File = File(context.cacheDir, "pty.size")

    val running: Boolean get() = process != null

    /** Команда и окружение запуска. Разделены, чтобы проверить раскладку без процесса. */
    fun command(cols: Int, rows: Int): Pair<List<String>, Map<String, String>> {
        val pump = File(context.applicationInfo.nativeLibraryDir, "libptysh.so").absolutePath
        val layout = manager?.layout
        val shell = if (layout != null) File(layout.binDir, "sh").absolutePath else "/system/bin/sh"
        val env = (layout?.environment() ?: emptyMap()).toMutableMap()
        env["TERM"] = "xterm-256color"
        env["LINES"] = rows.toString()
        env["COLUMNS"] = cols.toString()
        val cmd = listOf(pump, rows.toString(), cols.toString(), sizeFile.absolutePath, shell, "-i")
        return cmd to env
    }

    fun writeSize(cols: Int, rows: Int) {
        runCatching { sizeFile.writeText("$rows $cols") }
    }

    /** Возвращает текст ошибки или null, если процесс жив. */
    fun start(cols: Int, rows: Int): String? {
        if (running) return null
        val (cmd, env) = command(cols, rows)
        writeSize(cols, rows)
        val p = runCatching {
            ProcessBuilder(cmd).redirectErrorStream(false)
                .also { it.environment().clear(); it.environment().putAll(env) }
                .start()
        }.getOrElse { return "насос PTY не запустился: ${it.message}" }
        process = p
        val sink = onOutput
        threads = listOf(
            thread(name = "pty-out") { pumpIn(p.inputStream, sink) },
            thread(name = "pty-err") { pumpIn(p.errorStream, sink) },
        )
        return null
    }

    private fun pumpIn(input: InputStream, sink: ((ByteArray, Int) -> Unit)?) {
        val buf = ByteArray(8192)
        while (true) {
            val n = runCatching { input.read(buf) }.getOrDefault(-1)
            if (n <= 0) return
            sink?.invoke(buf.copyOf(n), n)
        }
    }

    fun input(bytes: ByteArray) {
        val p = process ?: return
        runCatching {
            p.outputStream.write(bytes)
            p.outputStream.flush()
        }
    }

    fun resize(cols: Int, rows: Int) {
        writeSize(cols, rows)
        val p = process ?: return
        runCatching { Os.kill(p.pid, 28) } // SIGWINCH: ядро само разошлёт его по группе
    }

    fun stop() {
        val p = process
        process = null
        runCatching { p?.destroy() }
        threads.forEach { runCatching { it.join(400) } }
        threads = emptyList()
    }
}
