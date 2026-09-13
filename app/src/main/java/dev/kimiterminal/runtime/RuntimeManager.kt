package dev.kimiterminal.runtime

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Что видит экран рантайма. */
sealed class RuntimeStatus {
    object Empty : RuntimeStatus()
    data class Installing(val done: Int, val total: Int, val current: String) : RuntimeStatus()
    data class Ready(val checks: List<String>) : RuntimeStatus()
    data class Broken(val reasons: List<String>) : RuntimeStatus()
}

/** Одна проверка цепочки запуска. Детали идут в UI дословно — гадать по «не работает» нельзя. */
data class Probe(val name: String, val ok: Boolean, val detail: String) {
    fun render(): String = (if (ok) "OK  " else "НЕТ ") + name + " — " + detail
}

/**
 * Установка и диагностика in-app рантайма на живом устройстве.
 *
 * Здесь живут единственные проверки, которые нельзя сделать на JVM: exec по симлинку
 * и работа glibc-загрузчика решает SELinux конкретного устройства. Всё остальное
 * (пути, манифест, замыкание) покрыто тестами, поэтому сюда я иду за правдой о железе.
 */
class RuntimeManager(context: Context) {

    private val app = context.applicationContext

    private val _status = MutableStateFlow<RuntimeStatus>(RuntimeStatus.Empty)
    val status: StateFlow<RuntimeStatus> = _status

    var layout: RuntimeLayout? = null
        private set
    var manifest: RuntimeManifest? = null
        private set

    private class AssetSource(val ctx: Context) : PayloadSource {
        override fun open(name: String): InputStream? = runCatching { ctx.assets.open(name) }.getOrNull()
    }

    private fun readAssetManifest(): RuntimeManifest? {
        val text = runCatching {
            app.assets.open("runtime/MANIFEST.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return RuntimeManifest.parse(text).getOrElse {
            _status.value = RuntimeStatus.Broken(listOf(it.message ?: "манифест не разобран"))
            null
        }
    }

    /** Блокирующая установка из APK. Вызывать из IO-корутины. */
    fun install(): List<Probe> {
        val m = readAssetManifest()
            ?: return listOf(Probe("установка", false, "в APK нет assets/runtime/MANIFEST.json"))
        if (m.specVersion != RuntimeSpec.SPEC_VERSION) {
            _status.value = RuntimeStatus.Broken(
                listOf("APK собран под spec " + m.specVersion + ", приложение ждёт " + RuntimeSpec.SPEC_VERSION),
            )
            return emptyList()
        }
        val l = RuntimeLayout(File(app.nativeLibraryDir), app.filesDir, m.executables)
        layout = l
        manifest = m
        _status.value = RuntimeStatus.Installing(0, m.entries.size, "подготовка")
        val problems = RuntimeInstaller(
            source = AssetSource(app),
            layout = l,
            manifest = m,
            onProgress = { p ->
                _status.value = RuntimeStatus.Installing(p.done, p.total, p.current.substringAfterLast('/'))
            },
            log = { step ->
                _status.value = RuntimeStatus.Installing(0, m.entries.size, step)
            },
        ).install()
        if (problems.isNotEmpty()) {
            _status.value = RuntimeStatus.Broken(problems)
            return emptyList()
        }
        return verifyAndPublish(l)
    }

    /** Рантайм уже на месте? Проверяем READY и ещё раз пробегаем пробы. */
    fun resume(): List<Probe> {
        val m = readAssetManifest() ?: return emptyList()
        val l = RuntimeLayout(File(app.nativeLibraryDir), app.filesDir, m.executables)
        if (!l.isReady()) {
            _status.value = RuntimeStatus.Empty
            return emptyList()
        }
        layout = l
        manifest = m
        return verifyAndPublish(l)
    }

    private fun verifyAndPublish(l: RuntimeLayout): List<Probe> {
        val probes = verify(l)
        _status.value = RuntimeStatus.Ready(probes.map { it.render() })
        return probes
    }

    private fun verify(l: RuntimeLayout): List<Probe> {
        val env = l.environment()
        return RuntimeProbes.commands(l).map { (name, cmd, expect) -> runOnce(cmd, env, name, expect) }
    }

    private fun runOnce(cmd: List<String>, env: Map<String, String>, name: String, expect: String): Probe {
        val p = runCatching {
            ProcessBuilder(cmd).redirectErrorStream(false).also { it.environment().putAll(env) }.start()
        }.getOrElse { return Probe(name, false, "старт не удался: " + it.message) }
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = thread(start = true) {
            runCatching { p.inputStream.bufferedReader().forEachLine { l -> synchronized(out) { out.append(l).append('\n') } } }
        }
        val t2 = thread(start = true) {
            runCatching { p.errorStream.bufferedReader().forEachLine { l -> synchronized(err) { err.append(l).append('\n') } } }
        }
        if (!p.waitFor(25, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Probe(name, false, "таймаут 25 с (возможно, exec завис в проверке прав)")
        }
        t1.join(1000)
        t2.join(1000)
        val text = out.toString().trim()
        val ok = p.exitValue() == 0 && text.startsWith(expect)
        val detail = if (ok) {
            text.lineSequence().firstOrNull() ?: ""
        } else {
            "код " + p.exitValue() + " · out: " + text.take(120) + " · err: " + err.toString().trim().take(200)
        }
        return Probe(name, ok, detail)
    }

    /** Агенту достаточно первой пробы: его мы запускаем абсолютными путями. */
    fun agentUsable(): Boolean = (layout?.missingPieces() ?: listOf("нет")).isEmpty() &&
        (status.value as? RuntimeStatus.Ready)?.checks?.any { it.startsWith("OK") } == true

    /** Терминалу и дочерним процессам kimi нужны симлинки и PATH — то есть пробы 2 и 3. */
    fun terminalUsable(): Boolean {
        val checks = (status.value as? RuntimeStatus.Ready)?.checks ?: return false
        return checks.count { it.startsWith("OK") } >= 3
    }

    fun agentCommand(): List<String> = layout?.agentCommand() ?: emptyList()
    fun agentEnvironment(): Map<String, String> = layout?.environment() ?: emptyMap()
    fun sessionDir(): File = layout?.sessionCwd() ?: app.filesDir
}
