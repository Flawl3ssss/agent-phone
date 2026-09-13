package dev.kimiterminal.runtime

import java.io.File

/**
 * Раскладка собранного рантайма на устройстве и команды, которые из неё собираются.
 *
 * Класс ничего не создаёт и не скачивает (кроме чтения READY-маркера): вся логика
 * проверяется на JVM без устройства. I/O-часть — в RuntimeInstaller, терминал и агент
 * получают отсюда только список аргументов.
 *
 * @param executables имена ELF из манифеста сборки (`lib<имя>.so` в nativeLibraryDir).
 *   По умолчанию — минимум, достаточный для агента и оболочки.
 */
class RuntimeLayout(
    val nativeLibDir: File,
    val filesDir: File,
    val executables: List<String> = RuntimeSpec.CORE_EXECUTABLES,
    /** Имена не-ELF (kimi), которых нет среди lib….so: их раздаёт диспетчер по таблице scripts/. */
    val scripts: List<String> = emptyList(),
) {

    val root = File(filesDir, "runtime")
    val libDir = File(root, "lib")
    val binDir = File(root, "bin")
    val homeDir = File(root, "home")
    val tmpDir = File(root, "tmp")
    val workDir = File(root, "work")
    val kimiDir = File(root, "kimi")
    val gitCoreDir = File(root, "git-core")
    val sslCertFile = File(root, "ssl/ca-certificates.crt")
    val bashrcFile = File(root, "bashrc")
    val readyFile = File(root, "READY")

    /** Точка входа CLI внутри распакованного npm-пакета. */
    val kimiEntry: File = RuntimeSpec.kimiEntryIn(kimiDir)

    /** Исполняемые ELF живут в nativeLibraryDir: только оттуда разрешён exec (docs/RUNTIME-PLAN.md §1). */
    fun elf(name: String): File = File(nativeLibDir, "lib$name.so")

    fun lib(soname: String): File = File(libDir, soname)

    /** Имена, которые стоит раздать в PATH. Загрузчик и диспетчер сами по себе команды. */
    fun linkedNames(): List<String> =
        (executables.filter { it != "ldr" && it != "kexec" } + scripts).distinct().sorted()

    /**
     * Команда через glibc-загрузчик: `libldr.so --library-path <lib> lib<name>.so args…`.
     *
     * Обёртка обязательна: `PT_INTERP` у всех ubuntu-ELF указывает на `/lib/ld-linux-aarch64.so.1`,
     * которого в монтированном виде файловой системы Android нет, и прямой execve даёт ENOEXEC.
     */
    fun command(name: String, args: List<String> = emptyList()): List<String> {
        require(name in executables) { "в рантайме нет команды $name" }
        return listOf(elf("ldr").path, "--library-path", libDir.path, elf(name).path) + args
    }

    /** ACP-агент: `node <kimi>/dist/main.mjs acp` — форма подтверждена живым handshake. */
    fun agentCommand(): List<String> = command("node", listOf(kimiEntry.path, "acp"))

    /** Вход отдельным процессом: у настоящего Kimi это `kimi acp --login` (device-code поток). */
    fun loginCommand(): List<String> = command("node", listOf(kimiEntry.path, "acp", "--login"))

    /** Оболочка терминала. --rcfile наш: /etc/profile принадлежит Android, а не rootfs. */
    fun shellCommand(): List<String> = command("bash", listOf("--rcfile", bashrcFile.path, "-i"))

    /** Окружение для дочерних процессов. Всё внутри runtime-дерева — вне его писать нечего. */
    fun environment(): Map<String, String> = linkedMapOf(
        "PATH" to binDir.path + ":/system/bin:/system/xbin",
        "HOME" to homeDir.path,
        "TMPDIR" to tmpDir.path,
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "TERM" to "xterm-256color",
        // Node не трогает эти переменные: у него встроенный магазин Mozilla (проверено
        // прогоном — TLS работает и с SSL_CERT_FILE=/nonexistent). Бандл нужен git и curl,
        // которые ходят в /etc/ssl/certs, а такого пути в Android нет.
        "SSL_CERT_FILE" to sslCertFile.path,
        "GIT_SSL_CAINFO" to sslCertFile.path,
        "GIT_EXEC_PATH" to gitCoreDir.path,
        "GIT_TEMPLATE_DIR" to File(gitCoreDir, "templates").path,
        // kexec читает корень рантайма отсюда; пути в APK неизвестны на этапе сборки.
        "AGENT_PHONE_RUNTIME" to root.path,
    )

    /** Имя в PATH -> ELF-диспетчер. SELinux смотрит метку цели (apk_data_file), а не ссылки. */
    fun binLinks(): Map<String, String> = linkedNames().associateWith { elf("kexec").path }

    /** Чего не хватает, чтобы запуститься. Пустой список — рантайм собран и симлинки на месте. */
    fun missingPieces(): List<String> {
        val out = mutableListOf<String>()
        for (name in executables) if (!elf(name).isFile) out += "elf:$name"
        // Диспетчер обязателен независимо от манифеста: каждая ссылка в bin/ на него указывает.
        if (!elf("kexec").isFile) out += "elf:kexec"
        for (soname in RuntimeSpec.REQUIRED_LIBS) if (!lib(soname).isFile) out += "lib:$soname"
        if (!kimiEntry.isFile) out += "kimi:main.mjs"
        // Требуем бандл только когда в наборе есть git: агенту он не нужен, а ложная
        // тревога в диагностике хуже отсутствия диагностики.
        if ("git" in executables && !sslCertFile.isFile) out += "ssl:ca-certificates"
        for (name in linkedNames()) {
            if (!File(binDir, name).exists()) out += "link:$name"
        }
        for (name in scripts) {
            if (!File(root, "scripts/$name").isFile) out += "table:$name"
        }
        return out
    }

    /** Каталоги, которые установщик обязан создать. */
    fun directories(): List<File> =
        listOf(root, libDir, binDir, homeDir, tmpDir, workDir, kimiDir, gitCoreDir, sslCertFile.parentFile)

    fun readyVersion(): String? = runCatching { readyFile.readText().trim() }.getOrNull()

    fun isReady(): Boolean = readyVersion() == RuntimeSpec.SPEC_VERSION && missingPieces().isEmpty()

    /** Куда Kimi кладёт свои файлы проекта. */
    fun sessionCwd(): File = workDir
}
