package dev.kimiterminal.gateway

import dev.kimiterminal.acp.AcpException
import dev.kimiterminal.acp.AcpErrorCodes
import dev.kimiterminal.acp.Diff
import dev.kimiterminal.acp.ReadTextFileRequest
import dev.kimiterminal.acp.ReadTextFileResponse
import dev.kimiterminal.acp.WriteTextFileRequest
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Политики и шлюзы привилегий (BLUEPRINT §5).
 *
 * Это ядро продукта, а не «обвязка»: ACP устроен так, что агент ходит за файлами и за
 * командами **к клиенту**. Значит три шлюза = три точки контроля, и всё остальное — UI.
 *
 * R5 в CHARTER: запрещено заводить второй канал доступа (MCP files-bridge, bind-монты и т.п.).
 */

// ───────────────────────────────────────────────────────────── PathPolicy

sealed class Decision {
    object Allow : Decision()
    data class Ask(val why: String) : Decision()
    data class Deny(val why: String) : Decision()
}

data class PathPolicy(
    /** Разрешённые корни: cwd сессии + additionalDirectories + scratch. */
    val allowedRoots: List<String>,
    /** Пути, куда доступ закрыт всегда, независимо от «всегда разрешать». */
    val denyAlways: List<String> = DEFAULT_DENY,
    val maxReadBytes: Long = 10L * 1024 * 1024,
    val maxWriteBytes: Long = 25L * 1024 * 1024,
    val maxLines: Int = 200_000,
) {
    /**
     * Резолвим **до** решения и **после** симлинков. Иначе обход тривиален:
     * `workspace/link -> /data/data/…/credentials`.
     */
    fun resolve(raw: String): String {
        val f = File(raw)
        val canonical = runCatching { f.canonicalPath }.getOrElse { f.absolutePath }
        // если файл ещё не создан — резолвим родителя
        if (!File(canonical).exists()) {
            val parent = File(canonical).parentFile
            val pc = runCatching { parent?.canonicalPath }.getOrNull()
            if (pc != null && parent != null) return File(pc, File(canonical).name).absolutePath
        }
        return canonical
    }

    fun inAllowed(path: String): Boolean = allowedRoots.any { root ->
        val r = runCatching { File(root).canonicalPath }.getOrElse { root }
        path == r || path.startsWith(r + File.separator)
    }

    fun check(path: String, write: Boolean): Decision {
        val p = resolve(path)
        if (denyAlways.any { d -> p == d || p.startsWith(if (d.endsWith("/")) d else "$d/") })
            return Decision.Deny("путь в закрытом разделе: $p")
        if (!inAllowed(p))
            return Decision.Deny("вне рабочих папок сессии: $p")
        if (write) {
            val f = File(p)
            if (f.exists() && f.length() > maxWriteBytes)
                return Decision.Deny("файл больше лимита записи (${f.length()} Б)")
        }
        return Decision.Allow
    }

    companion object {
        /** Секреты и внутренности приложения. Не снимаются ничем (S2 в BLUEPRINT §9). */
        val DEFAULT_DENY = listOf(
            "credentials", "server.token", ".kimi-code/credentials",
            "shared_prefs", "databases", "app_kimi-home/credentials",
        )
    }
}

// ───────────────────────────────────────────────────────────── CheckpointStore

/**
 * Чекпоинты = обёртка над fs/write_text_file (BLUEPRINT §5.3).
 * Сохраняем oldText **до** записи. Откат turn'а — пройтись по индексу в обратном порядке.
 */
class CheckpointStore(private val root: File, private val keepTurns: Int = 50, private val maxBytes: Long = 200L * 1024 * 1024) {
    private val json = Json { prettyPrint = false; encodeDefaults = false }

    data class Entry(val path: String, val blob: String, val existed: Boolean, val ts: Long)

    private fun dir(sessionId: String, turnId: String) = File(root, "$sessionId/$turnId").apply { mkdirs() }

    fun save(sessionId: String, turnId: String, path: String, oldText: String?, existed: Boolean): String {
        val d = dir(sessionId, turnId)
        val name = sha256(path)
        File(d, "$name.bin").writeText(oldText ?: "", Charsets.UTF_8)
        appendIndex(d, Entry(path, name, existed, System.currentTimeMillis()))
        rotate(sessionId)
        return name
    }

    private fun appendIndex(d: File, e: Entry) {
        val idx = File(d, "index.json")
        val list = readIndex(d).toMutableList()
        list.removeAll { it.path == e.path }              // последний снапшот пути — победитель
        list += e
        idx.writeText(json.encodeToString(JsonArray.serializer(), buildJsonArray {
            list.forEach { add(buildJsonObject { put("path", it.path); put("blob", it.blob); put("existed", it.existed); put("ts", it.ts) }) }
        }))
    }

    fun readIndex(d: File): List<Entry> {
        val idx = File(d, "index.json")
        if (!idx.exists()) return emptyList()
        return runCatching {
            json.parseToJsonElement(idx.readText(Charsets.UTF_8)).let { arr ->
                (arr as JsonArray).map {
                    val o = it as kotlinx.serialization.json.JsonObject
                    Entry(o.getValue("path").jsonPrimitiveContent, o.getValue("blob").jsonPrimitiveContent,
                        o["existed"]?.jsonPrimitiveContent?.toBoolean() ?: true, o["ts"]?.jsonPrimitiveContent?.toLong() ?: 0)
                }
            }
        }.getOrDefault(emptyList())
    }

    private val kotlinx.serialization.json.JsonElement.jsonPrimitiveContent: String
        get() = (this as JsonPrimitive).content

    /** Откат последнего turn'а. Возвращает список восстановленных путей. */
    fun rollbackLastTurn(sessionId: String): List<String> {
        val turns = File(root, sessionId).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        val last = turns.lastOrNull() ?: return emptyList()
        val restored = readIndex(last).map { e ->
            val target = File(e.path)
            if (e.existed) { target.parentFile?.mkdirs(); target.writeText(File(last, "${e.blob}.bin").readText(Charsets.UTF_8), Charsets.UTF_8) }
            else runCatching { target.delete() }
            e.path
        }
        last.deleteRecursively()
        return restored
    }

    fun rollbackTurn(sessionId: String, turnId: String): List<String> {
        val d = File(root, "$sessionId/$turnId")
        val restored = readIndex(d).map { e ->
            val target = File(e.path)
            if (e.existed) { target.parentFile?.mkdirs(); target.writeText(File(d, "${e.blob}.bin").readText(Charsets.UTF_8), Charsets.UTF_8) }
            else runCatching { target.delete() }
            e.path
        }
        d.deleteRecursively()
        return restored
    }

    fun listTurns(sessionId: String): List<String> =
        File(root, sessionId).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    private fun rotate(sessionId: String) {
        val sdir = File(root, sessionId)
        val turns = sdir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        turns.dropLast(keepTurns).forEach { it.deleteRecursively() }
        var total = sdir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var i = 0
        while (total > maxBytes && i < turns.size - 1) { total -= turns[i].walkTopDown().filter { it.isFile }.sumOf { it.length() }; turns[i].deleteRecursively(); i++ }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

// ───────────────────────────────────────────────────────────── FsGateway

/**
 * Ответ на `fs/read_text_file` / `fs/write_text_file`.
 *
 * Отказ — это **ошибка протокола**, а не пустой результат: агент должен понимать,
 * что его остановили, иначе он решит, что файл пустой, и «починит» его стиранием.
 */
class FsGateway(
    private val policy: PathPolicy,
    private val store: CheckpointStore,
    /** Вызывается перед записью, когда политика говорит Ask, либо обнаружено чужое изменение. */
    private val askUser: suspend (title: String, detail: String) -> Boolean,
    private val onDiff: (Diff) -> Unit = {},
) {
    /** sha(path) → (mtime, size) последнего виденного нами состояния. */
    private val observed = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    fun read(req: ReadTextFileRequest): ReadTextFileResponse {
        when (val d = policy.check(req.path, write = false)) {
            is Decision.Deny -> throw AcpException(AcpErrorCodes.RESOURCE_NOT_FOUND, "denied: ${d.why}")
            is Decision.Ask -> throw AcpException(AcpErrorCodes.RESOURCE_NOT_FOUND, "denied: ${d.why}")
            Decision.Allow -> {}
        }
        val f = File(policy.resolve(req.path))
        if (!f.exists()) throw AcpException(AcpErrorCodes.RESOURCE_NOT_FOUND, "no such file: ${f.path}")
        if (f.length() > policy.maxReadBytes)
            throw AcpException(AcpErrorCodes.INVALID_PARAMS, "file too large (${f.length()} > ${policy.maxReadBytes})")

        var text = f.readText(Charsets.UTF_8)
        if (req.line != null && req.line > 1) text = text.lines().drop(req.line - 1).joinToString("\n")
        if (req.limit != null) text = text.lines().take(req.limit).joinToString("\n")
        if (text.lines().size > policy.maxLines) text = text.lines().take(policy.maxLines).joinToString("\n")
        observed[sha(f.path)] = f.lastModified() to f.length()
        return ReadTextFileResponse(text)
    }

    suspend fun write(req: WriteTextFileRequest) {
        val path = policy.resolve(req.path)
        val f = File(path)
        when (val d = policy.check(path, write = true)) {
            is Decision.Deny -> throw AcpException(AcpErrorCodes.RESOURCE_NOT_FOUND, "denied: ${d.why}")
            else -> {}
        }
        if (req.content.toByteArray().size > policy.maxWriteBytes)
            throw AcpException(AcpErrorCodes.INVALID_PARAMS, "payload too large")

        val existed = f.exists()
        val oldText = if (existed) f.readText(Charsets.UTF_8) else null

        // Правило №2 BLUEPRINT §6.3: поймать правку, сделанную мимо ACP (через terminal/…).
        val key = sha(path)
        val seen = observed[key]
        if (existed && seen != null && (f.lastModified() to f.length()) != seen) {
            val ok = askUser("Файл изменился вне сессии",
                "${f.path}\nИзменён после того, как его читал агент. Перезаписать?")
            if (!ok) throw AcpException(AcpErrorCodes.RESOURCE_NOT_FOUND, "cancelled by user: concurrent modification")
        }

        val turnId = currentTurn(req.sessionId)
        store.save(req.sessionId, turnId, path, oldText, existed)

        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(req.content, Charsets.UTF_8)
        runCatching { FileOutputStreamCompat.sync(tmp) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        observed[key] = f.lastModified() to f.length()
        onDiff(Diff(path, oldText, req.content))
    }

    /** turnId берём из внешнего переключателя — так чекпоинты ложатся в гранулярности «ход агента». */
    var turnProvider: (String) -> String = { sessionId -> "turn-${System.currentTimeMillis() / 1000}-$sessionId" }
    private fun currentTurn(sessionId: String) = turnProvider(sessionId)

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}

private object FileOutputStreamCompat {
    fun sync(f: File) = java.io.FileOutputStream(f).channel.use { it.force(true) }
}
