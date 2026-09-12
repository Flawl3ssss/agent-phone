package dev.kimiterminal.mcp

import android.content.Context
import android.content.SharedPreferences
import dev.kimiterminal.agent.AgentSession
import dev.kimiterminal.agent.Item
import dev.kimiterminal.agent.PermissionPrompt
import dev.kimiterminal.acp.ContentBlock
import dev.kimiterminal.acp.SessionState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Настройки приложения как источник истины для агента.
 *
 * Ключевое — `confirmRequired`: то, что меняет поведение привилегий (тема безобидна,
 * а вот «разрешить агентам писать в /sdcard» или «поднимать 12 субагентов» — нет),
 * требует подтверждения пользователем. Это не вежливость: prompt injection из
 * прочитанной страницы должен упереться в человека, когда дело доходит до политики.
 */
class PrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agent_phone_settings", Context.MODE_PRIVATE)

    private data class Def(
        val key: String,
        val type: String,
        val default: JsonPrimitive,
        val description: String,
        val enum: List<String>? = null,
        val range: IntRange? = null,
        val confirmRequired: Boolean = false,
    )

    private val defs = listOf(
        Def("theme", "string", JsonPrimitive("dark"), "Тема интерфейса. dark — требование R3.", enum = listOf("dark", "oled", "system")),
        Def("font_size_sp", "integer", JsonPrimitive(14), "Кегль в чате и терминале.", range = 10..24),
        Def("terminal_font_size_sp", "integer", JsonPrimitive(13), "Кегль терминала.", range = 9..22),
        Def("max_concurrent_agents", "integer", JsonPrimitive(3),
            "Сколько субагентов роя может жить одновременно. Прямо влияет на риск убиения процесса системой.",
            range = 1..8, confirmRequired = true),
        Def("default_model", "string", JsonPrimitive("kimi-k3"), "Модель по умолчанию для новых сессий."),
        Def("secondary_model", "string", JsonPrimitive("kimi-k2"), "Дешёвая модель для рутинных субагентов."),
        Def("auto_approve_reads", "boolean", JsonPrimitive(true), "Автоматически выдавать доступ на чтение внутри cwd."),
        Def("allow_always_outside_cwd", "boolean", JsonPrimitive(false),
            "Разрешить «всегда» для путей вне cwd сессии. Открывает весь диск агенту.", confirmRequired = true),
        Def("egress_allowlist", "string", JsonPrimitive(""),
            "Через запятую — домены, куда агенту ход разрешён. Пусто = без ограничений (небезопасно).", confirmRequired = true),
        Def("notify_on_waiting", "boolean", JsonPrimitive(true), "Слать уведомление, когда агент ждёт ответа."),
        Def("notify_on_done", "boolean", JsonPrimitive(true), "Слать уведомление о завершении хода."),
        Def("battery_gate_enabled", "boolean", JsonPrimitive(true),
            "Не давать запускать ход на низком заряде/троттлинге (R14).", confirmRequired = true),
        Def("thermal_limit_c", "integer", JsonPrimitive(42), "Порог троттлинга, °C.", range = 35..55),
        Def("min_battery_percent", "integer", JsonPrimitive(15), "Ниже этого хода не будет.", range = 5..50),
        Def("checkpoint_keep_turns", "integer", JsonPrimitive(20), "Сколько ходов чекпоинтов хранить.", range = 1..200),
        Def("browser_tier", "string", JsonPrimitive("webview"),
            "Какой ярус браузера использовать по умолчанию.", enum = listOf("webview", "chrome", "off")),
        Def("yolo_mode", "boolean", JsonPrimitive(false),
            "Автосогласование всех разрешений. Осторожно: агент с полным диском без человека в контуре.", confirmRequired = true),
    )

    private val byKey = defs.associateBy { it.key }

    override fun all(): Map<String, JsonElement> = defs.associate { d ->
        d.key to (raw(d) ?: d.default)
    }

    override fun get(key: String): JsonElement? = byKey[key]?.let { raw(it) ?: it.default }

    override fun set(key: String, value: JsonElement): Boolean {
        val d = byKey[key] ?: return false
        val p = value as? JsonPrimitive ?: return false
        when (d.type) {
            "boolean" -> {
                val v = p.booleanOrNull ?: return false
                prefs.edit().putBoolean(key, v).apply()
            }
            "integer" -> {
                val v = p.intOrNull ?: p.content.toIntOrNull() ?: return false
                if (d.range != null && v !in d.range) return false
                prefs.edit().putInt(key, v).apply()
            }
            else -> {
                val v = p.content
                if (d.enum != null && v !in d.enum) return false
                prefs.edit().putString(key, v).apply()
            }
        }
        return true
    }

    override fun schema(): JsonArray = JsonArray(defs.map { d ->
        buildJsonObject {
            put("key", d.key)
            put("type", d.type)
            put("default", d.default)
            put("description", d.description)
            put("confirmRequired", d.confirmRequired)
            d.enum?.let { put("enum", JsonArray(it.map { v -> JsonPrimitive(v) })) }
            d.range?.let { put("min", it.first); put("max", it.last) }
        }
    })

    private fun raw(d: Def): JsonPrimitive? = when (d.type) {
        "boolean" -> if (prefs.contains(d.key)) JsonPrimitive(prefs.getBoolean(d.key, false)) else null
        "integer" -> if (prefs.contains(d.key)) JsonPrimitive(prefs.getInt(d.key, 0)) else null
        else -> prefs.getString(d.key, null)?.let { JsonPrimitive(it) }
    }

    // ── чтение настроек самим приложением (не только агентом) ───────────────

    fun bool(key: String): Boolean = (raw(byKey[key]!!) ?: byKey[key]!!.default).content == "true"
    fun int(key: String): Int = (raw(byKey[key]!!) ?: byKey[key]!!.default).content.toIntOrNull() ?: 0
    fun str(key: String): String = (raw(byKey[key]!!) ?: byKey[key]!!.default).content
    fun needsConfirm(key: String): Boolean = byKey[key]?.confirmRequired == true
}

/**
 * Мок-браузер: даёт агенту правдоподобную поверхность, чтобы мост можно было
 * прогнать end-to-end без настоящего WebView. Реальная реализация лежит в
 * ui-слое и оборачивает android.webkit.WebView.
 */
class MockBrowserControl : BrowserControl {
    private var current = "about:blank"
    private var history = mutableListOf<String>()
    private var idx = -1
    private val logs = ArrayDeque<JsonObject>().apply {
        add(buildJsonObject { put("level", "log"); put("text", "game boot ok"); put("ts", 1L) })
        add(buildJsonObject { put("level", "error"); put("text", "TypeError: cannot read 'ctx' of null at main.js:42"); put("ts", 2L) })
    }

    override val available = true
    override fun url() = current
    override fun title() = "НЕОН-БАШНЯ — превью"

    override fun navigate(url: String) {
        history = history.subList(0, (idx + 1).coerceAtMost(history.size))
        history.add(url); idx = history.size - 1; current = url
    }

    override fun back(): Boolean = if (idx > 0) { idx--; current = history[idx]; true } else false
    override fun forward(): Boolean = if (idx < history.size - 1) { idx++; current = history[idx]; true } else false
    override fun reload() { logs.addLast(buildJsonObject { put("level", "log"); put("text", "reload $current"); put("ts", 3L) }) }

    override fun evalJs(script: String, timeoutMs: Long): String? = when {
        script.contains("score") -> "1240"
        script.contains("location") -> current
        else -> "undefined"
    }

    override fun screenshot(maxWidthPx: Int, quality: Int): ByteArray? =
        // 1x1 JPEG — валидный, но дешёвый. Настоящий WebView рисует кадр через View.draw().
        java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
                "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA" +
                "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=="
        )

    override fun consoleLogs(limit: Int, level: String?): List<JsonObject> =
        logs.toList().let { l -> (if (level == null) l else l.filter { it["level"]?.jsonPrimitive?.content == level }) }
            .takeLast(limit)

    override fun domText(selector: String?, maxChars: Int): String? =
        ("<canvas id=game width=360 height=640></canvas><div id=hud>score: 1240</div>").take(maxChars)

    override fun click(selector: String): String? = "clicked $selector"
    override fun type(selector: String, text: String, clearFirst: Boolean): String? = "typed into $selector"
    override fun pressKey(key: String): String? = "pressed $key"
}

/**
 * То, как агент видит живую сессию. Реализация поверх AgentSession — без копий
 * состояния: один источник истины, иначе UI и картина агента разойдутся.
 */
class SessionHostState(private val session: AgentSession) : HostState {

    override fun sessions(): List<JsonObject> = listOf(
        buildJsonObject {
            put("sessionId", session.sessionId.value ?: JsonNull)
            put("state", session.stateMachine.state.value.name)
            put("cwd", session.cwd)
            put("busy", session.busy.value)
        }
    )

    override fun transcript(limit: Int): List<JsonObject> = session.items.value.takeLast(limit).map { it ->
        buildJsonObject {
            put("kind", when (it) {
                is Item.User -> "user"; is Item.AgentText -> "agent"; is Item.Thought -> "thought"
                is Item.Tool -> "tool"; is Item.Plan -> "plan"; is Item.Usage -> "usage"
                else -> "notice"
            })
            put("text", when (it) {
                is Item.User -> it.text
                is Item.AgentText -> it.text
                is Item.Thought -> it.text
                is Item.Tool -> "${it.title} [${it.status}] ${it.path.orEmpty()}"
                is Item.Plan -> it.entries.joinToString("; ") { e -> "${e.status}:${e.content}" }
                is Item.Usage -> "${it.used}/${it.size} ${it.currency ?: ""}"
                is Item.Notice -> it.text
            })
        }
    }

    override fun state(): SessionState? = session.stateMachine.state.value
    override fun busy(): Boolean = session.busy.value
    override fun stats(): String = session.stats
    override fun stderrTail(n: Int): List<String> = session.stderrTail(n)
    override fun capabilitiesSummary(): String =
        session.init.value?.let { "${it.agentInfo?.name} ${it.agentInfo?.version}" } ?: "нет handshake"

    override fun pendingPermission(): JsonObject? = session.permission.value?.let { p ->
        buildJsonObject {
            put("title", p.title)
            put("kind", p.kind)
            put("path", p.path)
            put("sessionId", p.request.sessionId)
            put("options", JsonArray(p.request.options.map { o ->
                buildJsonObject { put("optionId", o.optionId); put("name", o.name); put("kind", o.kind) }
            }))
        }
    }

    override suspend fun resolvePermission(optionId: String): Boolean {
        val p = session.permission.value ?: return false
        if (p.request.options.none { it.optionId == optionId }) return false
        // Отвечаем через тот же канал, что и кнопка в UI — двух путей «разрешить» быть не должно.
        p.reply(dev.kimiterminal.acp.PermissionOutcome("selected", optionId))
        return true
    }

    override suspend fun cancelTurn(): Boolean {
        val sid = session.sessionId.value ?: return false
        runCatching { session.clientRef.cancel(sid) }
        return true
    }

    override suspend fun setMode(modeId: String): Boolean {
        val sid = session.sessionId.value ?: return false
        val allowed = session.sessionInfo.value?.modes?.availableModes?.any { it.id == modeId } ?: true
        if (!allowed) return false
        runCatching { session.clientRef.setMode(sid, modeId) }
        return true
    }

    override suspend fun setThinking(on: Boolean): Boolean {
        val sid = session.sessionId.value ?: return false
        runCatching { session.clientRef.setConfigOption(sid, "thinking", if (on) "high" else "off") }
        return true
    }

    /**
     * Субагент — через нативный AgentSwarm движка: мы не порождаем процессов,
     * а просим сам kimi завести задачу. Поэтому phantom-лимит Android не при чём.
     */
    override suspend fun spawnSubagent(prompt: String, model: String?, background: Boolean): String? {
        val sid = session.sessionId.value ?: return null
        val text = buildString {
            append("Породи субагента через инструмент Agent (или AgentSwarm, если задач несколько).\n")
            if (model != null) append("Модель: $model\n")
            append("Фоновый режим: ${background}\n")
            append("Задача: $prompt")
        }
        runCatching { session.clientRef.prompt(sid, listOf(ContentBlock.text(text))) }
            .onFailure { return null }
        return "ok: запрос на субагента отправлен в текущем ходе"
    }
}
