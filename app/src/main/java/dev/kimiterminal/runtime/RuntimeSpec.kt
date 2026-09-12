package dev.kimiterminal.runtime

import java.io.File

/**
 * Пины того, что уезжает в APK как in-app рантайм.
 *
 * Все константы ниже — не «версии из доков», а значения, которые 13.09.2026 прогнаны
 * живьём: glibc-node запущен через встроенный `ld-linux`, `kimi acp` ответил настоящим
 * handshake'ом, trim-набор Kimi (без `dist-web` и без `native/`) сохранил работоспособность.
 * Подробности и таблица доказательств — docs/RUNTIME-PLAN.md.
 */
enum class ArchiveKind { TAR_GZ, TAR_XZ, TAR_GZ_NPM }

/** Скачиваемый артефакт. `checksum` — пин: менять только вместе с [RuntimeSpec.SPEC_VERSION]. */
data class Artifact(
    val id: String,
    val url: String,
    val algorithm: String,
    val checksum: String,
    val kind: ArchiveKind,
) {
    /** Формат контрольной суммы проверяется тестом: опечатка в hex дешёвая, но дорогая потом. */
    fun checksumWellFormed(): Boolean = when (algorithm) {
        "sha256" -> HEX_64.matches(checksum)
        "sha512" -> checksum.startsWith("sha512-") && BASE64.matches(checksum.substring(7))
        else -> false
    }

    private companion object {
        val HEX_64 = Regex("^[0-9a-f]{64}$")
        val BASE64 = Regex("^[A-Za-z0-9+/]{86}==$")
    }
}

object RuntimeSpec {

    /** Попала в READY-маркер: расхождение => рантайм переустанавливается. */
    const val SPEC_VERSION = "1"

    const val NODE_VERSION = "v22.23.2"
    const val UBUNTU_BASE_VERSION = "24.04.4"
    const val KIMI_VERSION = "0.42.0"

    /** `engines.node` пакета @moonshot-ai/kimi-code@0.42.0 — контракт, а не пожелание. */
    const val MIN_NODE_FOR_KIMI = "22.19.0"

    const val KIMI_ENTRYPOINT = "node_modules/@moonshot-ai/kimi-code/dist/main.mjs"

    val ARTIFACTS: List<Artifact> = listOf(
        Artifact(
            id = "ubuntu-base",
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_BASE_VERSION}/release/" +
                "ubuntu-base-${UBUNTU_BASE_VERSION}-base-arm64.tar.gz",
            algorithm = "sha256",
            checksum = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            kind = ArchiveKind.TAR_GZ,
        ),
        Artifact(
            id = "node",
            url = "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.xz",
            algorithm = "sha256",
            checksum = "fff4078c5def658577f92c88db7db3bc0072924bfb93fe52c1e744a54e94abb8",
            kind = ArchiveKind.TAR_XZ,
        ),
        Artifact(
            id = "kimi-code",
            url = "https://registry.npmjs.org/@moonshot-ai/kimi-code/-/kimi-code-${KIMI_VERSION}.tgz",
            algorithm = "sha512",
            checksum = "sha512-WNm2/j/7lVqK27379wwWlFrYpg6qpe20kTE8cA0fZLcrbHT2msM54MPDKwA5" +
                "3F6uGrKqPqUBqgX8O7BqTfCc3Q==",
            kind = ArchiveKind.TAR_GZ_NPM,
        ),
    )

    /**
     * ELF, которые лежат в `nativeLibraryDir` под именем `lib<name>.so` и исполняются ОТТУДА:
     * с targetSdk >= 29 exec из домашнего каталога приложения запрещён политиками W^X
     * (termux/termux-app#1072), а из read-only `/data/app/.../lib/arm64/` — разрешён.
     * `kexec` — наш Bionic-диспетчер (см. docs/RUNTIME-PLAN.md §3), `ldr` — glibc-загрузчик.
     */
    val EXEC_ELFS: List<String> = listOf("ldr", "node", "bash", "busybox", "git", "ssh", "kexec")

    /**
     * SONAME-файлы: их только читают, поэтому они лежат в `filesDir/runtime/lib` под
     * настоящими именами — AGP в jniLibs наружные имена не выносит. Закрыто по
     * `readelf -d` node/bash: libstdc++ у node появился в NEEDED только в официальной сборке.
     */
    val SHARED_LIBS: List<String> = listOf(
        "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0",
        "libstdc++.so.6", "libgcc_s.so.1", "libtinfo.so.6", "ld-linux-aarch64.so.1",
    )

    /**
     * Applet'ы busybox: одно исполняемое вместо десятков ubuntu-овских coreutils-файлов.
     * Здесь только то, что реально есть в busybox — `curl` и bash-овские builtins
     * (`export`, `source`) сюда не пишем: их нет в applet-таблице, и PATH-проверка
     * превратилась бы в ложную тревогу.
     */
    val BUSYBOX_APPLETS: List<String> = listOf(
        "sh", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "ln", "chmod", "chown", "touch",
        "echo", "pwd", "env", "printf", "head", "tail", "wc", "sort", "uniq", "cut",
        "tr", "grep", "sed", "awk", "find", "xargs", "stat", "du", "df", "tar", "gzip", "gunzip",
        "base64", "md5sum", "sha256sum", "date", "sleep", "kill", "ps", "uname", "whoami", "id",
        "vi", "less", "more", "wget", "nc", "ping",
    )

    /**
     * Сравнивает версии без зависимостей: `22.23.2` против `22.19.0`.
     * Нужен, чтобы апгрейд Node не «забыл» требование Kimi — проверка в тесте.
     */
    fun versionAtLeast(version: String, minimum: String): Boolean {
        val a = numbers(version)
        val b = numbers(minimum)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    /** `v22.23.2` / `22.23.2-nightly` / `22.19.0` -> списки чисел. */
    private fun numbers(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    /** Имя payload-каталога с исходниками npm-замыкания Kimi (qrcode, ws, …). */
    fun kimiEntryIn(kimiDir: File): File = File(kimiDir, KIMI_ENTRYPOINT)

    fun assertSelfConsistent(): List<String> {
        val problems = mutableListOf<String>()
        if (!versionAtLeast(NODE_VERSION.removePrefix("v"), MIN_NODE_FOR_KIMI)) {
            problems += "Node ${NODE_VERSION} не удовлетворяет engines kimi ($MIN_NODE_FOR_KIMI)"
        }
        for (art in ARTIFACTS) {
            if (!art.url.startsWith("https://")) problems += "${art.id}: не-https url"
            if (!art.checksumWellFormed()) problems += "${art.id}: битый ${art.algorithm}-пин"
        }
        return problems
    }
}
