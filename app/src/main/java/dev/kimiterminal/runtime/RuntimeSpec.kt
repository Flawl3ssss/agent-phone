package dev.kimiterminal.runtime

import java.io.File

/**
 * Пины and форма in-app рантайма. Единственный источник истины по составу — MANIFEST.json,
 * который генерирует `tools/runtime/assemble.sh`; здесь живут только версии и то, без чего
 * запуск не имеет смысла.
 *
 * Проверенные основания раскладки — в docs/RUNTIME-PLAN.md (§1). Ключевые:
 *  - Kimi Code CLI = чистый JS (`dist/main.mjs`), отдельный SEA-бинарь не нужен;
 *  - glibc-ные ELF исполняются через встроенный `ld-linux-aarch64.so.1 --library-path`;
 *  - exec разрешён только из `nativeLibraryDir` (W^X при targetSdk >= 29), поэтому
 *    исполняемые файлы лежат там как `lib<имя>.so`, а читаемые — в `filesDir`.
 */
object RuntimeSpec {

    /** Состав рантайма менялся (убран busybox, добавлен git): пометка в READY-файле. */
    const val SPEC_VERSION = "2"

    const val NODE_VERSION = "v22.23.2"
    const val UBUNTU_BASE_VERSION = "24.04.4"
    const val KIMI_VERSION = "0.42.0"

    /** engines из package.json @moonshot-ai/kimi-code@0.42.0. */
    const val MIN_NODE_FOR_KIMI = "22.19.0"

    /** Точка входа CLI: шебанг `#!/usr/bin/env node`, поэтому запускаем только так. */
    const val KIMI_ENTRYPOINT = "node_modules/@moonshot-ai/kimi-code/dist/main.mjs"

    /** Библиотеки, без которых node/bash не стартуют. */
    val REQUIRED_LIBS: List<String> = listOf(
        "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0",
        "libstdc++.so.6", "libgcc_s.so.1", "libtinfo.so.6", "ld-linux-aarch64.so.1",
    )

    /**
     * Минимум исполняемых имён: агент + оболочка. Полный список приходит из манифеста.
     * `kexec` здесь сознательно нет: его даёт NDK-модуль, а манифест описывает payload,
     * который собирается сегодня. Требование «kexec на устройстве есть» живёт в
     * RuntimeLayout.missingPieces(), потому что ссылки PATH указывают на него всегда.
     */
    val CORE_EXECUTABLES: List<String> = listOf("ldr", "node", "bash", "sh")

    /** Имена, которые в PATH должны существовать как ссылки на kexec. */
    val ALWAYS_LINKED: List<String> = listOf("sh", "bash", "node", "git", "ssh", "ls", "cat")

    /** Релиз-цикл сборки. */
    fun versionAtLeast(version: String, minimum: String): Boolean {
        val have = numbers(version)
        val need = numbers(minimum)
        val size = maxOf(have.size, need.size)
        for (i in 0 until size) {
            val h = have.getOrElse(i) { 0 }
            val n = need.getOrElse(i) { 0 }
            if (h != n) return h > n
        }
        return true
    }

    fun nodeSatisfiesKimi(): Boolean = versionAtLeast(NODE_VERSION, MIN_NODE_FOR_KIMI)

    private fun numbers(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: 0 }

    /** Путь к CLI внутри распакованного пакета. */
    fun kimiEntryIn(kimiDir: File): File = File(kimiDir, KIMI_ENTRYPOINT)
}
