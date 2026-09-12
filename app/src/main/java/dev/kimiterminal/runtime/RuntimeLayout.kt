package dev.kimiterminal.runtime

import java.io.File

/** Куда идёт `kexec` по имени: целевой ELF из `nativeLibraryDir` и его аргументы-префикс. */
data class Dispatch(val target: String, val prefix: List<String> = emptyList())

/**
 * Раскладка in-app рантайма: пути, команды, окружение.
 *
 * Класс сознательно ничего не создаёт и не скачивает (кроме чтения READY-маркера):
 * вся логика тестируется на JVM, без устройства. Единственный I/O-участник —
 * [missingPieces], и он только статист.
 *
 * Ключевое ограничение платформы (docs/RUNTIME-PLAN.md, п.13–14): с targetSdk >= 29
 * приложение не может исполнять файлы из своего домашнего каталога, поэтому исполняемые
 * ELF живут в `nativeLibraryDir` под именами `lib*.so`, а читаемые библиотеки — в
 * `filesDir/runtime/lib` под настоящими soname.
 */
class RuntimeLayout(val nativeLibDir: File, val filesDir: File) {

    val root = File(filesDir, "runtime")
    val libDir = File(root, "lib")
    val binDir = File(root, "bin")
    val homeDir = File(root, "home")
    val tmpDir = File(root, "tmp")
    val workDir = File(root, "work")
    val kimiDir = File(root, "kimi")
    val bashrcFile = File(root, "bashrc")
    val readyFile = File(root, "READY")

    /** Точка входа CLI. Обёрткой `kimi` не пользуемся: шебанг там `#!/usr/bin/env node`. */
    val kimiEntry: File = RuntimeSpec.kimiEntryIn(kimiDir)

    /** Исполняемый ELF в nativeLibraryDir (AGP фильтрует jniLibs по маске lib*.so). */
    fun elf(name: String): File = File(nativeLibDir, "lib$name.so")

    /** SONAME-файл, который только читают. */
    fun lib(name: String): File = File(libDir, name)

    /**
     * Таблица «имя в PATH → что исполнить». Для applet'ов busybox цель вызывается как
     * `busybox <applet> …`: glibc-загрузчик переустанавливает argv[0] на путь цели,
     * и обычная диспетчеризация busybox по argv[0] не сработала бы.
     */
    fun dispatch(): Map<String, Dispatch> {
        val table = LinkedHashMap<String, Dispatch>()
        table["bash"] = Dispatch("bash")
        table["node"] = Dispatch("node")
        table["git"] = Dispatch("git")
        table["ssh"] = Dispatch("ssh")
        for (applet in RuntimeSpec.BUSYBOX_APPLETS) table[applet] = Dispatch("busybox", listOf(applet))
        return table
    }

    /** Команда целиком: загрузчик + library-path + цель + префикс + аргументы. */
    fun command(name: String, args: List<String> = emptyList()): List<String> {
        val d = dispatch()[name] ?: throw IllegalArgumentException("нет команды в рантайме: $name")
        return listOf(elf("ldr").path, "--library-path", libDir.path, elf(d.target).path) + d.prefix + args
    }

    /** ACP-агент — ровно та командная строка, на которой kimi 0.42.0 ответил живым handshake. */
    fun agentCommand(): List<String> = command("node", listOf(kimiEntry.path, "acp"))

    /** Отдельный процесс входа: device-code поток, `kimi acp --login`. */
    fun loginCommand(): List<String> = command("node", listOf(kimiEntry.path, "acp", "--login"))

    /** Оболочка терминала. rc-file свой: /etc/profile здесь android-овский, а не ubuntu-овский. */
    fun shellCommand(): List<String> = command("bash", listOf("--rcfile", bashrcFile.path, "-i"))

    /** Окружение и агента, и терминала. PATH первым ставит binDir — иначе kexec не найдётся. */
    fun environment(): Map<String, String> = linkedMapOf(
        "PATH" to binDir.path + ":/system/bin:/system/xbin",
        "HOME" to homeDir.path,
        "TMPDIR" to tmpDir.path,
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "TERM" to "xterm-256color",
    )

    /** Симлинки PATH: имя -> kexec. SELinux смотрит метку цели (apk_data_file), не ссылки. */
    fun binLinks(): Map<String, String> =
        dispatch().keys.toList().sorted().associateWith { elf("kexec").path }

    /** Чего не хватает. Пустой список == рантайм собран и агент готов к запуску. */
    fun missingPieces(): List<String> {
        val out = mutableListOf<String>()
        for (name in RuntimeSpec.EXEC_ELFS) if (!elf(name).isFile) out += "elf:$name"
        for (soname in RuntimeSpec.SHARED_LIBS) if (!lib(soname).isFile) out += "lib:$soname"
        if (!kimiEntry.isFile) out += "kimi:main.mjs"
        return out
    }

    fun readyVersion(): String? = runCatching { readyFile.readText().trim() }.getOrNull()

    fun isReady(): Boolean = readyVersion() == RuntimeSpec.SPEC_VERSION && missingPieces().isEmpty()

    /** Каталог рабочей сессии — его отдаём Kimi как cwd. */
    fun sessionCwd(): File = workDir

    /** Что нужно создать установщику. Только каталоги: файлы и симлинки — дело инсталлятора. */
    fun directories(): List<File> =
        listOf(libDir, binDir, homeDir, tmpDir, workDir, kimiDir)
}
