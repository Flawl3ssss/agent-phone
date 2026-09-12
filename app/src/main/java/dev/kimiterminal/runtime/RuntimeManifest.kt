package dev.kimiterminal.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MANIFEST.json из `tools/runtime/assemble.sh` — единственный источник истины о составе
 * payload. Держать тот же список ещё и в Kotlin означало бы разъезжаться с деревом в APK,
 * поэтому имена исполняемых файлов, библиотеки и контрольные суммы приходят отсюда;
 * в `RuntimeSpec` остаются только версии и минимум, без которого запуск невозможен.
 *
 * Форма (её задаёт сборщик): `{"spec_version": "2", "ubuntu_base": "24.04.4", "node": "v22.23.2",
 * "kimi_version": "0.42.0", "native": [{"path","sha256","bytes"}], "runtime_lib": [...],
 * "kimi_files": [...], "ssl_files": [...]}`, где path — относительно корня репозитория.
 */
data class RuntimeManifest(
    val specVersion: String,
    val ubuntuBase: String,
    val node: String,
    val kimiVersion: String,
    val executables: List<String>,
    val sharedLibs: List<String>,
    val entries: List<Entry>,
) {
    data class Entry(val path: String, val sha256: String, val bytes: Long)

    /** Общее имя для progress-UI: сколько весит установочный payload. */
    val totalBytes: Long get() = entries.sumOf { it.bytes }

    /** Версия сборки обязана совпадать, иначе старый READY-маркер переживёт смену состава. */
    fun matchesSpec(): Boolean = specVersion == RuntimeSpec.SPEC_VERSION

    /** Все ли обязательные части на месте. Претензии списком — не boolean, чтобы UI что-то показал. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (!matchesSpec()) out += "spec_version ждём ${RuntimeSpec.SPEC_VERSION}, в манифесте $specVersion"
        for (name in RuntimeSpec.CORE_EXECUTABLES) if (name !in executables) out += "нет исполняемого $name"
        for (lib in RuntimeSpec.REQUIRED_LIBS) if (lib !in sharedLibs) out += "нет библиотеки $lib"
        if (!RuntimeSpec.versionAtLeast(node, RuntimeSpec.MIN_NODE_FOR_KIMI)) {
            out += "node $node не удовлетворяет engines kimi (>= ${RuntimeSpec.MIN_NODE_FOR_KIMI})"
        }
        if (entries.isEmpty()) out += "манифест без файлов"
        for (e in entries) {
            if (!HEX.matches(e.sha256)) out += "битая сумма у ${e.path}"
            if (e.bytes <= 0) out += "нулевой размер у ${e.path}"
        }
        val entry = entries.firstOrNull { it.path.endsWith(RuntimeSpec.KIMI_ENTRYPOINT) }
        if (entry == null) out += "в манифесте нет ${RuntimeSpec.KIMI_ENTRYPOINT}"
        return out
    }

    companion object {
        private val HEX = Regex("^[0-9a-f]{64}$")
        private val GROUPS = listOf("native", "runtime_lib", "kimi_files", "ssl_files")

        /** Разбор без исключений: кривой манифест должен становиться понятной ошибкой в UI. */
        fun parse(text: String): Result<RuntimeManifest> = runCatching {
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: error("манифест — не JSON-объект")
            fun str(key: String): String =
                (obj[key] as? JsonPrimitive)?.content ?: error("нет поля $key")
            fun rows(key: String): List<Entry> = (obj[key] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = (el as? JsonObject) ?: return@mapNotNull null
                Entry(
                    path = o.strOf("path"),
                    sha256 = o.strOf("sha256"),
                    bytes = o.strOf("bytes").toLongOrNull() ?: 0L,
                )
            }
            val entries = GROUPS.flatMap { rows(it) }
            RuntimeManifest(
                specVersion = str("spec_version"),
                ubuntuBase = str("ubuntu_base"),
                node = str("node"),
                kimiVersion = str("kimi_version"),
                executables = rows("native").map { baseName(it.path) }.distinct().sorted(),
                sharedLibs = rows("runtime_lib").map { baseName(it.path) }.distinct().sorted(),
                entries = entries,
            )
        }

        /** `app/src/main/jniLibs/arm64-v8a/libnode.so` -> `node` */
        private fun baseName(path: String): String =
            path.substringAfterLast('/').removeSuffix(".so").removePrefix("lib")

        private fun JsonObject.strOf(key: String): String =
            (this[key] as? JsonPrimitive)?.content ?: error("запись манифеста без поля $key")
    }
}
