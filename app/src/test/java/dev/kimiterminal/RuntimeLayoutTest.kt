package dev.kimiterminal

import dev.kimiterminal.runtime.Dispatch
import dev.kimiterminal.runtime.RuntimeLayout
import dev.kimiterminal.runtime.RuntimeSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раскладка in-app рантайма — то, что можно доказать без устройства, и то, что на устройстве
 * дорожает всего дороже: форма командной строки агента. Она сверена с ЖИВЫМ ответом
 * kimi 0.42.0 (docs/RUNTIME-PLAN.md, п.8), а не с описанием в доке.
 *
 * Отдельно сторожусь от двух ловушек, которые я сама себе устроила бы позже:
 *   1) запустить `kimi` как исполняемый файл (шебанг `#!/usr/bin/env node` на Android нерабочий);
 *   2) вызвать busybox-applet без явного аргумента-имени (glibc-загрузчик портит argv[0]).
 */
class RuntimeLayoutTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "agentphone-${System.nanoTime()}")
    private val nativeDir = File(base, "lib/arm64")
    private val layout = RuntimeLayout(nativeLibDir = nativeDir, filesDir = File(base, "files"))

    @Test
    fun `спека внутренне согласована и пины не битые`() {
        assertEquals(emptyList<String>(), RuntimeSpec.assertSelfConsistent())
        assertTrue(RuntimeSpec.versionAtLeast("22.23.2", RuntimeSpec.MIN_NODE_FOR_KIMI))
        // Компаратор сам по себе тоже должен уметь сравнивать, иначе проверка выше — пустая.
        assertFalse(RuntimeSpec.versionAtLeast("22.19.0", "22.23.2"))
        assertTrue(RuntimeSpec.versionAtLeast("22.19.0", "22.19.0"))
        assertTrue(RuntimeSpec.versionAtLeast("23.0.0", "22.19.0"))
    }

    @Test
    fun `команда агента = загрузчик + node + entry + acp, без shell-обёртки kimi`() {
        val cmd = layout.agentCommand()
        assertEquals(6, cmd.size)
        assertTrue("исполняется встроенный glibc-загрузчик", cmd[0].endsWith("/libldr.so"))
        assertEquals("--library-path", cmd[1])
        assertEquals(layout.libDir.path, cmd[2])
        assertTrue("node, а не npm-шим с шебангом env", cmd[3].endsWith("/libnode.so"))
        assertTrue(cmd[4].endsWith("dist/main.mjs"))
        assertEquals("acp", cmd[5])
        assertFalse("нигде не должно быть пути вида /bin/kimi", cmd.any { it.endsWith("/kimi") })
    }

    @Test
    fun `вход отдельным процессом с --login, как обещает authMethods`() {
        val login = layout.loginCommand()
        assertEquals(listOf("acp", "--login"), login.takeLast(2))
        assertEquals(layout.agentCommand().dropLast(1) + "--login", login)
    }

    @Test
    fun `busybox-applet вызывается с явным именем после цели`() {
        val ls = layout.command("ls", listOf("-l", "/tmp"))
        assertEquals(ls.subList(0, 4) + listOf("ls", "-l", "/tmp"), ls)
        assertTrue(ls[3].endsWith("/libbusybox.so"))
        assertEquals("ls", ls[4])
    }

    @Test
    fun `неизвестное имя не разыгрывается в bash`() {
        val thrown = runCatching { layout.command("not-a-real-command") }.exceptionOrNull()
        assertTrue("ожидался IllegalArgumentException", thrown is IllegalArgumentException)
    }

    @Test
    fun `все цели dispatch существуют в списке ELF`() {
        val unknown = layout.dispatch().values.map { it.target }.distinct() - RuntimeSpec.EXEC_ELFS.toSet()
        assertEquals(emptySet<String>(), unknown)
    }

    @Test
    fun `PATH-симлинки покрывают dispatch и ведут в kexec`() {
        val links = layout.binLinks()
        assertEquals(layout.dispatch().size, links.size)
        assertTrue(links.containsKey("sh"))
        assertTrue(links.containsKey("node"))
        assertEquals(setOf(layout.elf("kexec").path), links.values.toSet())
    }

    @Test
    fun `окружение держит всё внутри runtime-дерева`() {
        val env = layout.environment()
        assertTrue(env.getValue("PATH").startsWith(layout.binDir.path + ":"))
        for (key in listOf("HOME", "TMPDIR")) {
            assertTrue("$key вне runtime-дерева", File(env.getValue(key)).path.startsWith(layout.root.path))
        }
        assertEquals("xterm-256color", env.getValue("TERM"))
    }

    @Test
    fun `missingPieces называет всё отсутствующее и стихает после сборки`() {
        val missing = layout.missingPieces()
        assertEquals(RuntimeSpec.EXEC_ELFS.size + RuntimeSpec.SHARED_LIBS.size + 1, missing.size)
        for (name in RuntimeSpec.EXEC_ELFS) assertTrue("нет elf:$name", missing.contains("elf:$name"))
        for (soname in RuntimeSpec.SHARED_LIBS) assertTrue(missing.contains("lib:$soname"))
        assertTrue(missing.contains("kimi:main.mjs"))
        assertFalse(layout.isReady())

        // «Собираем» рантайм фиктивно: те же пути, чем инсталлятор и занят.
        for (dir in layout.directories()) dir.mkdirs()
        for (name in RuntimeSpec.EXEC_ELFS) {
            val f = layout.elf(name)
            f.parentFile.mkdirs()
            f.createNewFile()
        }
        for (soname in RuntimeSpec.SHARED_LIBS) layout.lib(soname).createNewFile()
        layout.kimiEntry.parentFile.mkdirs()
        layout.kimiEntry.createNewFile()

        assertEquals(emptyList<String>(), layout.missingPieces())
        assertFalse("READY ещё нет — готово быть не должно", layout.isReady())
        layout.readyFile.createNewFile()
        layout.readyFile.writeText(RuntimeSpec.SPEC_VERSION)
        assertTrue(layout.isReady())
        layout.readyFile.writeText("999")
        assertFalse("смена SPEC_VERSION обязывает переустановить", layout.isReady())

        base.deleteRecursively()
    }

    @Test
    fun `dispatch с дефолтным префиксом не тащит пустой список в команду`() {
        val d = Dispatch("node")
        assertEquals(emptyList<String>(), d.prefix)
        assertEquals(
            listOf(layout.elf("ldr").path, "--library-path", layout.libDir.path, layout.elf("node").path, "x"),
            layout.command("node", listOf("x")),
        )
    }
}
