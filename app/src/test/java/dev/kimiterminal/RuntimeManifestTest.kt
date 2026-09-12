package dev.kimiterminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт «сборщик -> приложение». MANIFEST.json пишет `tools/runtime/assemble.sh`,
 * читает Kotlin; если одна из сторон переименует поле, тест обязан упасть здесь,
 * а не на первом запуске установщика на телефоне.
 */
class RuntimeManifestTest {

    private val hex64 = "a".repeat(64)

    private fun row(path: String, bytes: Long = 100L): String =
        """{"path": "$path", "sha256": "$hex64", "bytes": $bytes}"""

    /** Полностью валидный манифест той же формы, что выдаёт сборщик. */
    private fun fixture(
        spec: String = RuntimeSpec.SPEC_VERSION,
        node: String = RuntimeSpec.NODE_VERSION,
        execs: List<String> = RuntimeSpec.CORE_EXECUTABLES,
        libs: List<String> = RuntimeSpec.REQUIRED_LIBS,
        sha: String = hex64,
        kimiEntry: Boolean = true,
    ): String {
        val native = execs.joinToString(", ") {
            row("app/src/main/jniLibs/arm64-v8a/lib$it.so").replace("\"$hex64\"", "\"$sha\"")
        }
        val rtLibs = libs.joinToString(", ") {
            row("app/src/main/assets/runtime/lib/$it").replace("\"$hex64\"", "\"$sha\"")
        }
        val kimi = mutableListOf(
            row("app/src/main/assets/runtime/kimi/package.json"),
        )
        if (kimiEntry) {
            kimi += row("app/src/main/assets/runtime/kimi/${RuntimeSpec.KIMI_ENTRYPOINT}", 21_000_000L)
        }
        return """
            {
              "spec_version": "$spec",
              "ubuntu_base": "${RuntimeSpec.UBUNTU_BASE_VERSION}",
              "node": "$node",
              "kimi_version": "${RuntimeSpec.KIMI_VERSION}",
              "native": [$native],
              "runtime_lib": [$rtLibs],
              "kimi_files": [${kimi.joinToString(", ")}],
              "ssl_files": []
            }
        """.trimIndent()
    }

    @Test
    fun `валидный манифест разбирается и не имеет претензий`() {
        val m = RuntimeManifest.parse(fixture()).getOrThrow()
        assertEquals("исполняемые имена снимаются с lib*.so", RuntimeSpec.CORE_EXECUTABLES.sorted(), m.executables)
        assertEquals(RuntimeSpec.REQUIRED_LIBS.sorted(), m.sharedLibs)
        assertEquals("претензий быть не должно: ${m.problems()}", emptyList<String>(), m.problems())
        assertTrue(m.matchesSpec())
        val expected = (RuntimeSpec.CORE_EXECUTABLES.size + RuntimeSpec.REQUIRED_LIBS.size + 1) * 100L +
            21_000_000L
        assertEquals("размер суммируется по всем группам", expected, m.totalBytes)
    }

    @Test
    fun `несовпадение версии сборки ловится до установки`() {
        val m = RuntimeManifest.parse(fixture(spec = "1")).getOrThrow()
        assertTrue("старый payload должен требовать переустановки", !m.matchesSpec())
        assertTrue(m.problems().any { it.contains("spec_version") })
    }

    @Test
    fun `отсутствующие части перечисляются все сразу`() {
        val m = RuntimeManifest.parse(
            fixture(execs = listOf("ldr", "node"), libs = RuntimeSpec.REQUIRED_LIBS.dropLast(2), kimiEntry = false)
        ).getOrThrow()
        val problems = m.problems()
        for (name in listOf("kexec", "bash", "sh")) {
            assertTrue("нет претензии про $name: $problems", problems.any { it.contains(name) })
        }
        assertTrue("нет претензии про библиотеки", problems.count { it.startsWith("нет библиотеки") } == 2)
        assertTrue("CLI-точка входа потеряна", problems.any { it.contains(RuntimeSpec.KIMI_ENTRYPOINT) })
    }

    @Test
    fun `слишком старый node против engines kimi`() {
        val m = RuntimeManifest.parse(fixture(node = "v22.18.0")).getOrThrow()
        assertTrue(m.problems().any { it.contains("engines kimi") })
    }

    @Test
    fun `битые суммы и нулевые размеры не проходят`() {
        val broken = fixture().replace(hex64, "zzzz")
        val m = RuntimeManifest.parse(broken).getOrThrow()
        assertTrue("каждый файл с битой суммой должен быть назван", m.problems().all { it.contains("битая сумма") })
        assertTrue(m.problems().isNotEmpty())
    }

    @Test
    fun `мусор на входе не роняет разбор`() {
        for (bad in listOf("", "не json", "[]", "{}", "{\"native\": 5}")) {
            val r = RuntimeManifest.parse(bad)
            assertTrue("пустой ввод обязан быть ошибкой, а не молчаливым пустым манифестом", r.isFailure)
        }
    }

    @Test
    fun `реальный манифест из сборки соответствует kotlin-спецификации`() {
        val file = repoFile("runtime/MANIFEST.json")
        val m = RuntimeManifest.parse(file.readText()).getOrThrow()
        assertEquals("версия node в манифесте и в коде", RuntimeSpec.NODE_VERSION, m.node)
        assertEquals("версия kimi в манифесте и в коде", RuntimeSpec.KIMI_VERSION, m.kimiVersion)
        assertEquals("base ubuntu", RuntimeSpec.UBUNTU_BASE_VERSION, m.ubuntuBase)
        assertEquals("версия сборки", emptyList<String>(), m.problems())
        // Константы сборщика и Kotlin-спецификации не должны разъезжаться молча.
        val script = repoFile("tools/runtime/assemble.sh").readText()
        for ((key, value) in listOf(
            "UBUNTU_VERSION:=" to RuntimeSpec.UBUNTU_BASE_VERSION,
            "NODE_VERSION:=" to RuntimeSpec.NODE_VERSION,
            "KIMI_VERSION:=" to RuntimeSpec.KIMI_VERSION,
            "SPEC_VERSION:=" to RuntimeSpec.SPEC_VERSION,
        )) {
            assertTrue("$key должен быть $value", script.contains("$key$value}"))
        }
        for (group in listOf("native", "runtime_lib", "kimi_files", "ssl_files")) {
            assertTrue("сборщик должен писать группу $group", script.contains("\"$group\""))
        }
    }

    /** Gradle ставит workingDir в каталог модуля; проверяем и вариант от корня репозитория. */
    private fun repoFile(relative: String): File {
        val candidates = listOf(File(relative), File("../$relative"), File("../../$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "нет $relative; cwd=${File(".").absolutePath}, искали: ${candidates.map { it.absolutePath }}",
            )
    }
}
