package dev.kimiterminal.mcp

import dev.kimiterminal.acp.SessionState
import dev.kimiterminal.agent.AgentSession
import dev.kimiterminal.gateway.PermissionGateway
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * ═══════════════════════════════════════════════════════════════════════════
 *  МОСТ «KIMI ⇄ ПРИЛОЖЕНИЕ»
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * ACP односторонний по замыслу: клиент управляет агентом. У него нет понятия
 * «инструмент клиента». Зато есть легальный способ дать агенту инструменты —
 * поле `mcpServers` в `session/new`.
 *
 * Проверено в исходниках kimi-code (packages/acp-server/src/convert.ts:188):
 *   if (server.type === 'http' || server.type === 'sse') {
 *     out[server.name] = { transport: server.type, url: server.url, headers: ... }
 *   }
 * и в docs/en/reference/kimi-acp.md, раздел «MCP forwarding»:
 *   http → kimi's transport: 'http';  sse → 'sse';  acp → discarded.
 *
 * Значит: приложение поднимает HTTP MCP-сервер на 127.0.0.1 и регистрирует его
 * в session/new. Kimi подключается сам и начинает видеть настройки приложения,
 * его состояние и браузер — как обычные tools, вызовы которых проходят через
 * штатный session/request_permission.
 *
 * Никаких самописных протоколов, никаких кастомных ACP-методов, никаких
 * расширений за пределами спецификации. Один транспорт, один контракт.
 *
 * Почему 127.0.0.1 доступен из гостя: proot не выделяет отдельный network
 * namespace, а наследует android-ный. Loopback общий.
 */

/** Абстракция браузера. Реализация на WebView — в ui-слое; здесь контракт. */
interface BrowserControl {
    val available: Boolean
    fun url(): String?
    fun title(): String?
    fun navigate(url: String)
    fun back(): Boolean
    fun forward(): Boolean
    fun reload()
    fun evalJs(script: String, timeoutMs: Long = 8_000): String?
    fun screenshot(maxWidthPx: Int = 1080, quality: Int = 70): ByteArray?
    fun consoleLogs(limit: Int, level: String?): List<JsonObject>
    fun domText(selector: String?, maxChars: Int): String?
    fun click(selector: String): String?
    fun type(selector: String, text: String, clearFirst: Boolean): String?
    fun pressKey(key: String): String?
}

/** То, что агент должен видеть про само приложение. */
interface HostState {
    fun sessions(): List<JsonObject>
    fun transcript(limit: Int): List<JsonObject>
    fun state(): SessionState?
    fun busy(): Boolean
    fun stats(): String
    fun stderrTail(n: Int): List<String>
    fun capabilitiesSummary(): String
    /** Разрешить или отклонить висящий запрос агента. */
    suspend fun resolvePermission(optionId: String): Boolean
    fun pendingPermission(): JsonObject?
    suspend fun cancelTurn(): Boolean
    suspend fun setMode(modeId: String): Boolean
    suspend fun setThinking(on: Boolean): Boolean
    suspend fun spawnSubagent(prompt: String, model: String?, background: Boolean): String?
}

/** Хранилище настроек. Значения типизированы, чтобы агент не угадывал. */
interface SettingsStore {
    fun all(): Map<String, JsonElement>
    fun get(key: String): JsonElement?
    /** @return null если ключ неизвестен. */
    fun set(key: String, value: JsonElement): Boolean
    fun schema(): JsonArray
}

// ─────────────────────────────────────────────────────────────────────────────
//  Реестр инструментов
// ─────────────────────────────────────────────────────────────────────────────

class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val dangerous: Boolean,
    val handler: suspend (JsonObject) -> String,
)

private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(fields.toMap()))
    val req = fields.filter { it.value.jsonObject["__required"]?.jsonPrimitive?.content == true }
        .map { it.first }
    if (req.isNotEmpty()) put("required", JsonArray(req.map { JsonPrimitive(it) }))
}

private fun s(desc: String, required: Boolean = true, enum: List<String>? = null): JsonObject =
    buildJsonObject {
        put("type", "string"); put("description", desc)
        if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
        put("__required", JsonPrimitive(required))
    }

private fun i(desc: String, required: Boolean = false): JsonObject =
    buildJsonObject { put("type", "integer"); put("description", desc); put("__required", JsonPrimitive(required)) }

private fun b(desc: String, required: Boolean = false): JsonObject =
    buildJsonObject { put("type", "boolean"); put("description", desc); put("__required", JsonPrimitive(required)) }

private fun any(desc: String, required: Boolean = false): JsonObject =
    buildJsonObject { put("description", desc); put("__required", JsonPrimitive(required)) }

class ToolRegistry(
    private val settings: SettingsStore,
    private val host: HostState,
    private val browser: BrowserControl,
    private val perm: PermissionGateway,
) {
    private val tools = LinkedHashMap<String, ToolSpec>()

    init {
        register(settingsTools())
        register(hostTools())
        register(browserTools())
        register(controlTools())
    }

    fun all(): List<ToolSpec> = tools.values.toList()
    fun find(name: String): ToolSpec? = tools[name]

    private fun register(list: List<ToolSpec>) { list.forEach { tools[it.name] = it } }

    fun toolsJson(): JsonArray = JsonArray(tools.values.map { t ->
        buildJsonObject {
            put("name", t.name)
            put("description", t.description)
            put("inputSchema", stripRequiredMarkers(t.inputSchema))
        }
    })

    private fun stripRequiredMarkers(schema: JsonObject): JsonObject {
        val props = schema["properties"]?.jsonObject ?: return schema
        val clean = props.mapValues { (_, v) ->
            JsonObject(v.jsonObject.filterKeys { it != "__required" })
        }
        val required = props.filterValues { it.jsonObject["__required"]?.jsonPrimitive?.content == "true" }.keys
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(clean))
            if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    // ── настройки приложения ────────────────────────────────────────────────

    private fun settingsTools() = listOf(
        ToolSpec("app_settings_get", "Вернуть все настройки приложения Agent Phone и их текущие значения.", obj(), dangerous = false) {
            settings.all().entries.joinToString("\n") { (k, v) -> "$k = ${v}" }
        },
        ToolSpec(
            "app_settings_set",
            "Изменить настройку приложения. Список ключей, типов и допустимых значений — в app_settings_schema. " +
                "Часть ключей (theme, max_concurrent_agents, egress_allowlist) требует подтверждения пользователем.",
            obj("key" to s("имя настройки"), "value" to any("новое значение: строка, число или булеан")),
            dangerous = true,
        ) { p ->
            val key = p["key"]!!.jsonPrimitive.content
            val value = p["value"] ?: throw IllegalArgumentException("value обязателен")
            if (!settings.set(key, value)) return@ToolSpec "неизвестный ключ: $key"
            "ok: $key := $value"
        },
        ToolSpec("app_settings_schema", "Схема настроек: ключ, тип, допустимые значения, нужен ли подтверждение.", obj(), dangerous = false) {
            Json.encodeToString(JsonArray.serializer(), JsonArray(settings.schema())).also { it }
        },
    )

    // ── состояние приложения ────────────────────────────────────────────────

    private fun hostTools() = listOf(
        ToolSpec("app_sessions_list", "Список сессий агента в приложении: id, состояние, cwd, чем заняты.", obj(), false) {
            Json.encodeToString(JsonArray.serializer(), JsonArray(host.sessions()))
        },
        ToolSpec(
            "app_transcript",
            "Последние сообщения текущей сессии — то, что видит пользователь в чате. Нужно агенту, " +
                "чтобы понимать контекст UI (например, на чём остановился другой субагент).",
            obj("limit" to i("сколько записей, по умолчанию 30")), false,
        ) { p ->
            val n = (p["limit"]?.jsonPrimitive?.int ?: 30).coerceIn(1, 200)
            Json.encodeToString(JsonArray.serializer(), JsonArray(host.transcript(n)))
        },
        ToolSpec("app_state", "Состояние приложения: состояние сессии, занята ли работа, счётчики fs/терминалов, троттлинг.", obj(), false) {
            buildJsonObject {
                put("sessionState", host.state()?.name ?: "NONE")
                put("busy", JsonPrimitive(host.busy()))
                put("stats", JsonPrimitive(host.stats()))
                put("capabilities", JsonPrimitive(host.capabilitiesSummary()))
            }.toString()
        },
        ToolSpec("app_log_tail", "Хвост stderr агента — сырые логи kimi. Помогает диагностировать, почему что-то упало.", obj("lines" to i("строк, по умолчанию 80")), false) { p ->
            val n = (p["lines"]?.jsonPrimitive?.int ?: 80).coerceIn(1, 400)
            host.stderrTail(n).joinToString("\n")
        },
    )

    // ── управление сессиями ─────────────────────────────────────────────────

    private fun controlTools() = listOf(
        ToolSpec(
            "app_permission_respond",
            "Ответить на запрос разрешения, который сейчас показан пользователю. " +
                "Позволяет рую согласовывать между собой, не будя человека. Отклонённый вариант не выбирается.",
            obj("optionId" to s("id варианта из options[].optionId")), true,
        ) { p ->
            val id = p["optionId"]!!.jsonPrimitive.content
            if (host.resolvePermission(id)) "ok: выбран $id" else "нет активного запроса или такой опции нет"
        },
        ToolSpec("app_permission_pending", "Показать, какого разрешения агента ждут прямо сейчас (если ждут).", obj(), false) {
            host.pendingPermission()?.toString() ?: "нет ожидающих запросов"
        },
        ToolSpec("app_session_cancel", "Остановить текущий ход агента.", obj(), true) {
            if (host.cancelTurn()) "ok" else "нечего отменять"
        },
        ToolSpec(
            "app_session_set_mode",
            "Переключить режим сессии. plan — только чтение, default — обычные права, yolo — автосогласование (только с ведома пользователя).",
            obj("mode" to s("plan|default|yolo", enum = listOf("plan", "default", "yolo"))), true,
        ) { p -> if (host.setMode(p["mode"]!!.jsonPrimitive.content)) "ok" else "режим недоступен" },
        ToolSpec("app_session_set_thinking", "Включить/выключить расширенное размышление (дороже, медленнее, точнее).", obj("on" to b("включить?", required = true)), false) { p ->
            if (host.setThinking(p["on"]!!.jsonPrimitive.content == "true")) "ok" else "нет такого configOption"
        },
        ToolSpec(
            "app_subagent_spawn",
            "Породить субагента через нативный AgentSwarm движка. background=true — не ждать результата.",
            obj(
                "prompt" to s("задача для субагента"),
                "model" to s("id модели или 'secondary' для дешёвой", required = false),
                "background" to b("не ждать завершения"),
            ), true,
        ) { p ->
            host.spawnSubagent(
                prompt = p["prompt"]!!.jsonPrimitive.content,
                model = p["model"]?.jsonPrimitive?.contentOrNullSafe(),
                background = p["background"]?.jsonPrimitive?.content == "true",
            ) ?: "не удалось: движок не вернул task id"
        },
    )

    // ── браузер ─────────────────────────────────────────────────────────────

    private fun browserTools() = listOf(
        ToolSpec("browser_state", "Открыт ли браузер, какой URL и заголовок.", obj(), false) {
            if (!browser.available) "браузер не запущен"
            else buildJsonObject {
                put("available", JsonPrimitive(true))
                put("url", browser.url()?.let { JsonPrimitive(it) } ?: JsonNull)
                put("title", browser.title()?.let { JsonPrimitive(it) } ?: JsonNull)
            }.toString()
        },
        ToolSpec("browser_navigate", "Открыть URL во встроенном браузере приложения.", obj("url" to s("http(s)://…")), true) { p ->
            val u = p["url"]!!.jsonPrimitive.content
            if (!u.startsWith("http://") && !u.startsWith("https://")) return@ToolSpec "разрешены только http/https"
            browser.navigate(u); "ok: открыт $u"
        },
        ToolSpec("browser_back", "Назад по истории.", obj(), false) { if (browser.back()) "ok" else "история пуста" },
        ToolSpec("browser_forward", "Вперёд по истории.", obj(), false) { if (browser.forward()) "ok" else "вперёд некуда" },
        ToolSpec("browser_reload", "Перезагрузить страницу.", obj(), false) { browser.reload(); "ok" },
        ToolSpec(
            "browser_evaluate",
            "Выполнить JS в странице и вернуть результат. Основной инструмент dev-loop: читать состояние игры, " +
                "дёргать функции, проверять DOM.",
            obj("script" to s("JS-выражение или IIFE")), true,
        ) { p -> browser.evalJs(p["script"]!!.jsonPrimitive.content) ?: "null (или таймаут)" },
        ToolSpec(
            "browser_screenshot",
            "Скрин текущей страницы в base64 JPEG. Возвращается уменьшенным — не засирай контекст, " +
                "для деталей используй browser_dom.",
            obj("maxWidth" to i("пикселей по ширине, по умолчанию 1080"), "quality" to i("JPEG quality 10..95")), false,
        ) { p ->
            val bytes = browser.screenshot(
                p["maxWidth"]?.jsonPrimitive?.int ?: 1080,
                p["quality"]?.jsonPrimitive?.int ?: 70,
            ) ?: return@ToolSpec "скриншот недоступен"
            "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        },
        ToolSpec(
            "browser_console",
            "Логи консоли страницы. В dev-loop это обратная связь от кода: ошибки, предупреждения, console.log.",
            obj("limit" to i("сколько записей, по умолчанию 50"), "level" to s("log|warn|error|info", required = false, enum = listOf("log", "warn", "error", "info"))), false,
        ) { p ->
            val logs = browser.consoleLogs(p["limit"]?.jsonPrimitive?.int ?: 50, p["level"]?.jsonPrimitive?.contentOrNullSafe())
            Json.encodeToString(JsonArray.serializer(), JsonArray(logs))
        },
        ToolSpec(
            "browser_dom",
            "Текстовое содержимое DOM (или поддерева по CSS-селектору). Дешевле скриншота, читает структуру.",
            obj("selector" to s("CSS-селектор, пусто — весь body", required = false), "maxChars" to i("объём, по умолчанию 8000")), false,
        ) { p ->
            browser.domText(p["selector"]?.jsonPrimitive?.contentOrNullSafe(), p["maxChars"]?.jsonPrimitive?.int ?: 8000)
                ?: "DOM недоступен"
        },
        ToolSpec("browser_click", "Кликнуть по CSS-селектору.", obj("selector" to s("CSS-селектор")), true) { p ->
            browser.click(p["selector"]!!.jsonPrimitive.content) ?: "элемент не найден"
        },
        ToolSpec("browser_type", "Ввести текст в поле по селектору.", obj("selector" to s("CSS-селектор"), "text" to s("что ввести"), "clear" to b("очистить перед вводом")), true) { p ->
            browser.type(p["selector"]!!.jsonPrimitive.content, p["text"]!!.jsonPrimitive.content, p["clear"]?.jsonPrimitive?.content == "true") ?: "элемент не найден"
        },
        ToolSpec("browser_press_key", "Нажать клавишу (Enter, Tab, ArrowLeft, …).", obj("key" to s("имя клавиши")), true) { p ->
            browser.pressKey(p["key"]!!.jsonPrimitive.content) ?: "не удалось"
        },
    )
}

private fun JsonPrimitive?.contentOrNullSafe(): String? = this?.takeIf { it !is JsonNull }?.content
