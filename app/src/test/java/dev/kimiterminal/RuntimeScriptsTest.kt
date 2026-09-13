package dev.kimiterminal.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблица скриптов: то, чем `kimi` отличается от обычного бинарника в наборе.
 *
 * Проверяется две независимые вещи — что имя получает ссылку в PATH и что сборщик
 * и Kotlin ссылаются на один и тот же путь точки входа. Второе важнее: путь в C
 * пишется человекочитаемой строкой в shell, и расхождение молча лишило бы терминал
 * команды kimi без какой-либо ошибки при сборке.
 */
class RuntimeScriptsTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "ap-scripts-${System.nanoTime()}")
    private val nativeDir = File(base, "native").apply { mkdirs() }
    private val filesDir = File(base, "files").apply { mkdirs() }

    @Test
    fun `скрипты получают ссылку диспетчера наравне с бинарниками`() {
        val layout = RuntimeLayout(nativeDir, filesDir, listOf("sh", "node", "ldr", "kexec"), listOf("kimi"))
        assertEquals(listOf("kimi", "node", "sh"), layout.linkedNames())
        // Все ссылки ведут в один файл-диспетчер: различие имён возникает уже в argv[0].
        assertEquals(layout.binLinks()["sh"], layout.binLinks()["kimi"])
    }

    @Test
    fun `отсутствующие ссылка и таблица названы отдельными причинами`() {
        val layout = RuntimeLayout(nativeDir, filesDir, listOf("sh"), listOf("kimi"))
        val missing = layout.missingPieces()
        assertTrue("ссылка не названа: $missing", "link:kimi" in missing)
        assertTrue("таблица не названа: $missing", "table:kimi" in missing)
    }

    @Test
    fun `манифест отдаёт имена из группы scripts`() {
        val sha = "a".repeat(64)
        fun r(p: String) = """{"path": "$p", "sha256": "$sha", "bytes": 10}"""
        val json = """
            { "spec_version": "${RuntimeSpec.SPEC_VERSION}",
              "ubuntu_base": "${RuntimeSpec.UBUNTU_BASE_VERSION}",
              "node": "${RuntimeSpec.NODE_VERSION}",
              "kimi_version": "${RuntimeSpec.KIMI_VERSION}",
              "native": [${r("app/src/main/jniLibs/arm64-v8a/libsh.so")}],
              "runtime_lib": [],
              "kimi_files": [${r("app/src/main/assets/runtime/kimi/${RuntimeSpec.KIMI_ENTRYPOINT}")}],
              "ssl_files": [],
              "scripts": [${r("app/src/main/assets/runtime/scripts/kimi")}] }
        """.trimIndent()
        assertEquals(listOf("kimi"), RuntimeManifest.parse(json).getOrThrow().scripts)
    }

    @Test
    fun `путь из таблицы сборщика ведёт туда же, куда смотрит Kotlin`() {
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/runtime/assemble.sh") }
            .firstOrNull { it.isFile } ?: return // сборщик доступен только в репозитории
        val text = script.readText()
        // Вместо разбора синтаксиса shell — проверка фактов: цель таблицы ведёт туда же,
        // куда смотрит Kotlin. Расхождение молча лишило бы терминал команды kimi.
        val expected = "kimi/" + RuntimeSpec.KIMI_ENTRYPOINT
        assertTrue("в сборщике потерялся путь точки входа " + expected, expected in text)
        assertTrue("каталог таблицы задан дважды по-разному", "SCR=\"\$ASSETS/scripts\"" in text)
    }
}
