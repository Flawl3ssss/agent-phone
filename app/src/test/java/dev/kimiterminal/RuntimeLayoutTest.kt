package dev.kimiterminal.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Проверки раскладки in-app рантайма. Всё, что здесь утверждается, было сначала
 * получено живым прогоном на aarch64 (docs/RUNTIME-PLAN.md §1) — тесты сторонят
 * именно форму команды и полноту дерева, а не выдуманное поведение.
 */
class RuntimeLayoutTest {

    private lateinit var nativeDir: File
    private lateinit var filesDir: File
    private lateinit var layout: RuntimeLayout

    /** Полный набор, как его выдаёт assemble.sh: минимум + git/ssh/ls/cat. */
    private val full = RuntimeSpec.CORE_EXECUTABLES + listOf("git", "ssh", "ls", "cat")

    @Before
    fun setUp() {
        val base = File(System.getProperty("java.io.tmpdir"), "agentphone-runtime-${System.nanoTime()}")
        nativeDir = File(base, "native").apply { mkdirs() }
        filesDir = File(base, "files").apply { mkdirs() }
        layout = RuntimeLayout(nativeDir, filesDir, full)
    }

    @After
    fun tearDown() {
        File(nativeDir.parentFile.absolutePath).deleteRecursively()
    }

    @Test
    fun `команда агента собрана ровно так, как проверено живым зондом`() {
        val cmd = layout.agentCommand()
        // Загрузчик обязан идти первым: PT_INTERP у glibc-ELF указывает в несуществующий путь,
        // поэтому execve по node напрямую даётся ENOEXEC.
        assertEquals(6, cmd.size)
        assertEquals(File(nativeDir, "libldr.so").path, cmd[0])
        assertEquals("--library-path", cmd[1])
        assertEquals(layout.libDir.path, cmd[2])
        assertEquals(File(nativeDir, "libnode.so").path, cmd[3])
        assertEquals(layout.kimiEntry.path, cmd[4])
        assertEquals("acp", cmd[5])
        assertFalse("node вызывают явно, shell-шим kimi не исполняется", cmd[3].endsWith("/kimi"))
    }

    @Test
    fun `вход отдельным процессом с флагом логина`() {
        val login = layout.loginCommand()
        assertEquals(listOf("acp", "--login"), login.takeLast(2))
        assertEquals(layout.agentCommand() + "--login", login)
    }

    @Test
    fun `неизвестное имя команды отвергается, а не подменяется`() {
        val ls = layout.command("ls", listOf("-l"))
        assertEquals(File(nativeDir, "libls.so").path, ls[3])
        assertEquals(listOf("-l"), ls.subList(4, ls.size))
        val unknown = runCatching { layout.command("docker") }.exceptionOrNull()
        assertTrue("неизвестное имя должно бросаться", unknown is IllegalArgumentException)
        assertTrue(
            "в сообщении должно быть имя команды: ${unknown?.message}",
            unknown?.message?.contains("docker") == true,
        )
        assertTrue("git из CORE не должен требоваться", "git" !in RuntimeSpec.CORE_EXECUTABLES)
    }

    @Test
    fun `симлинки PATH не включают служебные ELF`() {
        val links = layout.binLinks()
        assertEquals(
            "ссылки = все исполняемые, кроме загрузчика и диспетчера",
            full.filter { it != "ldr" && it != "kexec" }.sorted(),
            links.keys.toList(),
        )
        val kexec = File(nativeDir, "libkexec.so").path
        assertTrue("все ссылки ведут в kexec", links.values.all { it == kexec })
        assertEquals("ссылки отсортированы и без повторов", links.size, links.keys.distinct().size)
        for (name in RuntimeSpec.ALWAYS_LINKED) assertTrue("нет ссылки $name", name in links)
    }

    @Test
    fun `окружение держит всё в дереве рантайма и отдаёт сертификаты`() {
        val env = layout.environment()
        assertTrue(env.getValue("PATH").startsWith(layout.binDir.path))
        assertEquals(layout.homeDir.path, env.getValue("HOME"))
        assertEquals(layout.tmpDir.path, env.getValue("TMPDIR"))
        assertEquals("xterm-256color", env.getValue("TERM"))
        // Без SSL_CERT_FILE node не проверит цепочки: /etc/ssl/certs в Android нет.
        assertEquals(layout.sslCertFile.path, env.getValue("SSL_CERT_FILE"))
        assertEquals(layout.gitCoreDir.path, env.getValue("GIT_EXEC_PATH"))
        assertEquals(layout.root.path, env.getValue("AGENT_PHONE_RUNTIME"))
        assertEquals(layout.sslCertFile.path, env.getValue("GIT_SSL_CAINFO"))
        for (key in listOf("HOME", "TMPDIR", "SSL_CERT_FILE", "GIT_EXEC_PATH", "GIT_SSL_CAINFO")) {
            assertTrue("$key вне дерева рантайма: ${env[key]}", File(env.getValue(key)).path.startsWith(layout.root.path))
        }
    }

    @Test
    fun `проверка неполноты называет всё отсутствующее и стихает после сборки`() {
        // +1 kexec (вне манифеста), +1 main.mjs, +1 бандл сертификатов (git в наборе есть).
        val expected = full.size + 1 + RuntimeSpec.REQUIRED_LIBS.size + 2 +
            RuntimeSpec.ALWAYS_LINKED.count { it in full }
        val missing = layout.missingPieces()
        assertEquals("пустое дерево: $missing", expected, missing.size)
        for (name in full) assertTrue("нет elf:$name", missing.contains("elf:$name"))
        for (soname in RuntimeSpec.REQUIRED_LIBS) assertTrue("нет $soname", missing.contains("lib:$soname"))
        assertTrue(missing.contains("ssl:ca-certificates"))
        assertFalse("рантайм ещё не собран", layout.isReady())

        for (dir in layout.directories()) dir.mkdirs()
        for (name in full) layout.elf(name).createNewFile()
        layout.elf("kexec").createNewFile()  // его даёт NDK-модуль, но требуется всегда
        for (soname in RuntimeSpec.REQUIRED_LIBS) layout.lib(soname).createNewFile()
        layout.kimiEntry.parentFile.mkdirs()
        layout.kimiEntry.createNewFile()
        layout.sslCertFile.parentFile.mkdirs()
        layout.sslCertFile.createNewFile()
        for (name in layout.linkedNames()) File(layout.binDir, name).createNewFile()

        assertEquals("после сборки ничего не должно недоставать: ${layout.missingPieces()}", emptyList<String>(), layout.missingPieces())
        assertFalse("READY ещё нет", layout.isReady())

        layout.readyFile.writeText(RuntimeSpec.SPEC_VERSION)
        assertTrue(layout.isReady())
        layout.readyFile.writeText("999")
        assertFalse("устаревший маркер требует переустановки", layout.isReady())
    }

    @Test
    fun `спецификация внутренне согласована`() {
        assertTrue("версия node должна удовлетворять engines kimi", RuntimeSpec.nodeSatisfiesKimi())
        assertFalse(RuntimeSpec.versionAtLeast("22.19.0", "22.23.2"))
        assertTrue(RuntimeSpec.versionAtLeast("22.23.2", "22.23.2"))
        assertTrue(RuntimeSpec.versionAtLeast("23.0.0", "22.19.0"))
        assertEquals("ldr", RuntimeSpec.CORE_EXECUTABLES.first())
        assertTrue(RuntimeSpec.kimiEntryIn(File("/x/kimi")).path.endsWith(RuntimeSpec.KIMI_ENTRYPOINT))
        for (name in RuntimeSpec.REQUIRED_LIBS) {
            assertTrue("$name — не soname?", ".so" in name)
        }
    }
}
