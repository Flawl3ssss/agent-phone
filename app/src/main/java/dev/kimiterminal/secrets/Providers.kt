package dev.kimiterminal.secrets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Провайдеры моделей и их ключи.
 *
 * Почему это отдельный слой, а не ещё один ключ в [dev.kimiterminal.mcp.SettingsStore]:
 * настройки агента читаются агентом через MCP и светятся в UI, а секрет не должен
 * светиться нигде. Поэтому метаданные и ключ лежат раздельно, и ключ проходит только
 * через [SecretBox] — интерфейс, который на устройстве реализован Android Keystore,
 * а в тестах остаётся in-memory. Само ядро (реестр, маскирование, окружение для гостя)
 * от Android не зависит и покрывается юнит-тестами.
 */

@Serializable
enum class ProviderKind(val envVar: String, val defaultBaseUrl: String, val title: String) {
    /** Kimi / Moonshot — родной бэкенд продукта. */
    MOONSHOT("MOONSHOT_API_KEY", "https://api.moonshot.ai/v1", "Moonshot (Kimi)"),
    OPENAI("OPENAI_API_KEY", "https://api.openai.com/v1", "OpenAI"),
    ANTHROPIC("ANTHROPIC_API_KEY", "https://api.anthropic.com", "Anthropic"),
    GOOGLE("GEMINI_API_KEY", "https://generativelanguage.googleapis.com", "Google Gemini"),
    DEEPSEEK("DEEPSEEK_API_KEY", "https://api.deepseek.com/v1", "DeepSeek"),
    /** Любой OpenAI-совместимый эндпоинт: vLLM, ollama-совместимый, self-hosted. */
    CUSTOM("OPENAI_API_KEY", "", "Свой (OpenAI-совместимый)"),
}

/**
 * Запись о провайдере. Секрета здесь нет by design — [masked] это то, что можно
 * показать в UI и залогировать, не боясь утечки через скриншот или баг-репорт.
 */
@Serializable
data class Provider(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val masked: String = "",
    val hasKey: Boolean = false,
) {
    fun effectiveBaseUrl(): String = baseUrl.ifBlank { kind.defaultBaseUrl }
}

/** Хранилище ключей. Реализации обязаны не писать секрет на диск в открытом виде. */
interface SecretBox {
    fun put(id: String, secret: String)
    fun get(id: String): String?
    fun drop(id: String)
}

class InMemorySecretBox : SecretBox {
    private val m = mutableMapOf<String, String>()
    override fun put(id: String, secret: String) { m[id] = secret }
    override fun get(id: String): String? = m[id]
    override fun drop(id: String) { m.remove(id) }
    fun snapshot(): Map<String, String> = m.toMap()
}

/**
 * «…4f2a» — первые 2 и последние 4 символа. Для коротких ключей маскируем всё:
 * иначе префикс+суффикс восстановят 12-символьный секрет целиком.
 */
fun maskSecret(secret: String): String {
    val s = secret.trim()
    if (s.length < 16) return if (s.isEmpty()) "" else "•".repeat(s.length)
    return "${s.take(2)}…${s.takeLast(4)}"
}

/** Форма для проверки «похоже на ключ», а не «равно эталону». */
fun looksLikeApiKey(s: String): Boolean {
    val t = s.trim()
    return t.length >= 20 && t.none { it.isWhitespace() } && t.all { !it.isISOControl() }
}

/**
 * Реестр провайдеров поверх пары «метаданные (можно открыто) + SecretBox (нельзя)».
 *
 * Персистентность отдана наружу через [load]/[save]: ядро не знает, SharedPreferences
 * это файл или SQLite, и потому тестируется без Robolectric.
 */
class ProviderRegistry(
    private val box: SecretBox,
    private val load: () -> String?,
    private val save: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }

    @Serializable private data class Doc(val providers: List<Provider> = emptyList(), val activeId: String? = null)

    private var doc: Doc = read()

    private fun read(): Doc = runCatching {
        load()?.let { json.decodeFromString(Doc.serializer(), it) }
    }.getOrNull() ?: Doc()

    private fun write() = save(json.encodeToString(Doc.serializer(), doc))

    fun all(): List<Provider> = doc.providers

    fun active(): Provider? = doc.providers.firstOrNull { it.id == doc.activeId }

    fun setActive(id: String) {
        if (doc.providers.any { it.id == id }) { doc = doc.copy(activeId = id); write() }
    }

    /**
     * Добавляет или обновляет. Пустой [apiKey] означает «не менять существующий» —
     * иначе любое редактирование метки стирало бы ключ, и это был бы тот самый баг,
     * из-за которого люди вводят ключ по три раза.
     */
    fun upsert(id: String, label: String, kind: ProviderKind, baseUrl: String, model: String, apiKey: String?): Provider {
        val b = baseUrl.trim()
        // У CUSTOM нет дефолтного URL по определению: пустой base означает, что агент
        // уйдёт в запрос на пустой адрес и получит «connection refused» без внятной причины.
        require(kind != ProviderKind.CUSTOM || b.isNotEmpty()) { "для своего провайдера нужно указать base URL" }
        val p0 = doc.providers.firstOrNull { it.id == id }
        var masked = p0?.masked ?: ""
        var hasKey = p0?.hasKey ?: false
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null) {
            require(looksLikeApiKey(key)) { "ключ слишком короткий или содержит пробелы" }
            box.put(id, key)
            masked = maskSecret(key); hasKey = true
        }
        val p = Provider(
            id = id,
            label = label.ifBlank { kind.title },
            kind = kind,
            baseUrl = b,
            model = model.trim().ifBlank { defaultModel(kind) },
            masked = masked,
            hasKey = hasKey,
        )
        doc = doc.copy(
            providers = doc.providers.filterNot { it.id == id } + p,
            activeId = doc.activeId ?: id,
        )
        write()
        return p
    }

    fun remove(id: String) {
        box.drop(id)
        doc = doc.copy(providers = doc.providers.filterNot { it.id == id },
            activeId = if (doc.activeId == id) doc.providers.firstOrNull { it.id != id }?.id else doc.activeId)
        write()
    }

    /** Секрет наружу — только туда, где он неизбежен: env гостя и заголовок запроса. */
    fun secretOf(id: String): String? = box.get(id)

    /**
     * Переменные окружения для запускаемого агента. Ключ отдаётся под именем,
     * которое ждёт конкретный SDK, плюс единые `AGENT_PHONE_*` — чтобы мок и реальный
     * агент читали одно и то же.
     */
    fun envFor(p: Provider = active() ?: error("нет активного провайдера")): Map<String, String> = mapOf(
        p.kind.envVar to (box.get(p.id) ?: ""),
        "AGENT_PHONE_PROVIDER" to p.id,
        "AGENT_PHONE_MODEL" to p.model,
        "AGENT_PHONE_BASE_URL" to p.effectiveBaseUrl(),
    )

    companion object {
        fun defaultModel(kind: ProviderKind) = when (kind) {
            ProviderKind.MOONSHOT -> "kimi-k2-0905-preview"
            ProviderKind.OPENAI -> "gpt-5-mini"
            ProviderKind.ANTHROPIC -> "claude-sonnet-4-5"
            ProviderKind.GOOGLE -> "gemini-2.5-flash"
            ProviderKind.DEEPSEEK -> "deepseek-chat"
            ProviderKind.CUSTOM -> "default"
        }
    }
}
