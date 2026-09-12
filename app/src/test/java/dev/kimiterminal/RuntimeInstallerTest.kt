package dev.kimiterminal.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Установка payload: порядок «сначала проверить, потом писать» и обратимость —
 * то, что на телефоне проверить невозможно, поэтому проверяется здесь.
 */
class RuntimeInstallerTest {

    private lateinit var base: File
    private lateinit var nativeDir: File
    private lateinit var filesDir: File
    private lateinit var payloadRoot: File
    private lateinit var layout: RuntimeLayout

    private val execs = RuntimeSpec.CORE_EXECUTABLES
    private val libs = RuntimeSpec.REQUIRED_LIBS

    @Before
    fun setUp() {
        base = File(System.getProperty("java.io.tmpdir"), "agentphone-install-${System.nanoTime()}")
        nativeDir = File(base, "native").apply { mkdirs() }
        filesDir = File(base, "files").apply { mkdirs() }
        payloadRoot = File(base, "payload").apply { mkdirs() }
        // Диспетчер даёт NDK-модуль, а missingPieces() требует его всегда: без него
        // ни один позитивный сценарий не сойдётся.
        File(nativeDir, "libkexec.so").writeText("ELF-kexec")
        layout = RuntimeLayout(nativeDir, filesDir, execs)
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    /** Пишет файл в payload и возвращает строку манифеста для него. */
    private fun row(relPath: String, content: String): String {
        val f = File(payloadRoot, relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
        return """{"path": "$relPath", "sha256": "${sha(content)}", "bytes": ${content.toByteArray().size}}"""
    }

    /** Готовит ELF в nativeLibraryDir так, как это делает система при установке APK. */
    private fun plantNative(name: String): String {
        val f = File(nativeDir, "lib$name.so")
        f.writeText("ELF-$name")
        return row("app/src/main/jniLibs/arm64-v8a/lib$name.so", "ELF-$name")
    }

    private fun manifestText(extraEntries: List<String> = emptyList()): String {
        val native = execs.map { plantNative(it) } + extraEntries
        val rtLibs = libs.map { row("app/src/main/assets/runtime/lib/$it", "lib:$it") }
        val kimi = row("app/src/main/assets/runtime/kimi/${RuntimeSpec.KIMI_ENTRYPOINT}", "cli") +
            "," + row("app/src/main/assets/runtime/kimi/package.json", "{}")
        val ssl = row("app/src/main/assets/runtime/ssl/ca-certificates.crt", "PEM")
        return """
            {
              "spec_version": "${RuntimeSpec.SPEC_VERSION}",
              "ubuntu_base": "${RuntimeSpec.UBUNTU_BASE_VERSION}",
              "node": "${RuntimeSpec.NODE_VERSION}",
              "kimi_version": "${RuntimeSpec.KIMI_VERSION}",
              "native": [${native.joinToString(", ")}],
              "runtime_lib": [${rtLibs.joinToString(", ")}],
              "kimi_files": [$kimi],
              "ssl_files": [$ssl]
            }
        """.trimIndent()
    }

    private fun installer(text: String = manifestText()): RuntimeInstaller {
        val manifest = RuntimeManifest.parse(text).getOrThrow()
        val fullLayout = RuntimeLayout(nativeDir, filesDir, manifest.executables)
        return RuntimeInstaller(DirectorySource(payloadRoot), fullLayout, manifest)
    }

    @Test
    fun `успешная установка даёт готовый рантайм`() {
        val problems = installer().install()
        assertEquals("претензий быть не должно: $problems", emptyList<String>(), problems)
        assertEquals(RuntimeSpec.SPEC_VERSION, layout.readyVersion())
        assertTrue("кэш библиотек на месте", layout.lib("libc.so.6").isFile)
        assertEquals("lib:libc.so.6", layout.lib("libc.so.6").readText())
        assertTrue("CLI на месте", layout.kimiEntry.isFile)
        assertTrue("сертификаты на месте", layout.sslCertFile.isFile)
        assertTrue("проверка полноты должна смолчать: ${layout.missingPieces()}", layout.missingPieces().isEmpty())
    }

    @Test
    fun `симлинки PATH создаются и показывают на kexec`() {
        val problems = installer().install()
        assertEquals("претензий быть не должно: $problems", emptyList<String>(), problems)
        val links = layout.binLinks()
        assertTrue("есть ссылки", links.isNotEmpty())
        for ((name, target) in links) {
            val link = File(layout.binDir, name)
            assertTrue("нет ссылки $name", Files.isSymbolicLink(link.toPath()))
            assertEquals("$name указывает мимо диспетчера", File(target).canonicalFile, link.toPath().toRealPath().toFile().canonicalFile)
        }
        assertTrue("после установки рантайм готов", layout.isReady())
    }

    @Test
    fun `повторная установка идемпотентна`() {
        val inst = installer()
        assertEquals(emptyList<String>(), inst.install())
        val before = layout.lib("libc.so.6").readText()
        assertEquals("второй прогон обязан пройти так же", emptyList<String>(), installer().install())
        assertEquals(before, layout.lib("libc.so.6").readText())
        assertTrue(layout.isReady())
    }

    @Test
    fun `проверка предшествует записи — старый payload ничего не трогает`() {
        val stale = manifestText().replace(
            "\"spec_version\": \"${RuntimeSpec.SPEC_VERSION}\"",
            "\"spec_version\": \"1\"",
        )
        val problems = installer(stale).install()
        assertTrue("старый payload обязан быть отвергнут", problems.isNotEmpty())
        assertFalse("READY не должен появиться", layout.readyFile.exists())
        assertFalse("файлы не должны копироваться до проверки", layout.libDir.exists())
    }

    @Test
    fun `отсутствующий ELF в nativeLibraryDir останавливает установку`() {
        File(nativeDir, "libnode.so").delete()
        val problems = installer().install()
        assertTrue("должна быть названа причина", problems.any { it.contains("libnode") || it.contains("node") })
        assertFalse("запись не началась", layout.readyFile.exists())
    }

    @Test
    fun `повреждённый файл в apk ловится по sha256`() {
        val inst = installer()
        // Портим источник после того, как манифест уже составлен: так выглядит битая сборка.
        File(payloadRoot, "app/src/main/assets/runtime/kimi/${RuntimeSpec.KIMI_ENTRYPOINT}")
            .writeText("мусор вместо cli")
        val problems = inst.install()
        assertEquals("одна причина, названная честно", 1, problems.size)
        assertTrue("${problems[0]} должен говорить о повреждении", problems[0].contains("повреждён"))
        assertFalse("READY не пишется при сбое", layout.readyFile.exists())
    }

    @Test
    fun `отсутствующий в apk файл не превращается в пустой`() {
        val inst = installer()
        File(payloadRoot, "app/src/main/assets/runtime/ssl/ca-certificates.crt").delete()
        val problems = inst.install()
        assertEquals(1, problems.size)
        assertTrue("ожидалась жалоба на отсутствующий файл: ${problems[0]}", problems[0].contains("нет файла"))
        assertFalse(layout.readyFile.exists())
    }
}
