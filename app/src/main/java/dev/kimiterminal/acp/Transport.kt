package dev.kimiterminal.acp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Транспорт ACP: NDJSON поверх двух символьных потоков.
 *
 * Ровно то, на чём сидит `kimi acp`: одна JSON-RPC запись на строку, `\n`-терминатор.
 * stdout агента — только протокол; всё его логирование идёт в stderr (в kimi-code стоит
 * явный редирект console.* → stderr). Наш [AcpAgentProcess] принимает stderr отдельно.
 */
interface AcpLink {
    /** Входящие сообщения уже разобраны в JsonObject. Закрыт — значит поток умер. */
    val incoming: ReceiveChannel<JsonObject>
    val alive: StateFlow<Boolean>
    suspend fun send(obj: JsonObject)
    fun close()
}

class AcpTransport(
    private val reader: BufferedReader,
    private val writer: BufferedWriter,
    private val scope: CoroutineScope,
    /** Жёсткий кап на один кадр. Kimi сам ограничивает вывод терминала 4 MiB; строка
     *  tool_call с диффом может быть больше — держим 8 MiB и честно падаем выше. */
    private val maxFrameBytes: Int = 8 * 1024 * 1024,
) : AcpLink {

    private val writeMutex = Mutex()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _incoming = Channel<JsonObject>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<JsonObject> = _incoming

    private val _alive = MutableStateFlow(true)
    override val alive: StateFlow<Boolean> = _alive

    /** Последняя ошибка чтения — для диагностики «почему всё умерло». */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    override suspend fun send(obj: JsonObject) = writeMutex.withLock {
        // encodeToString даёт compact-вывод. JsonObject.toString() местами печатает
        // pretty — перевод строки внутри кадра убил бы NDJSON.
        val line = compactJson.encodeToString(JsonObject.serializer(), obj)
        if (line.toByteArray(Charsets.UTF_8).size > maxFrameBytes) {
            throw AcpException(-32000, "кадр больше ${maxFrameBytes / 1024 / 1024} MiB")
        }
        try {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        } catch (e: IOException) {
            _alive.value = false
            throw AcpException(-32000, "write failed: ${e.message}")
        }
    }

    init {
        // Транспорт обязан начать читать сам. Иначе конструктор «успешен», канал есть,
        // но входящих нет никогда: каждый запрос молча доживает до таймаута — ровно тот
        // самый «вечный spinner», который мы обещали ловить на гейте G3.
        startReading()
    }

    fun startReading() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = try {
                        acpJson.parseToJsonElement(line).jsonObject
                    } catch (e: Exception) {
                        // Мусорная строка не должна рвать канал: агент обязан не писать в
                        // stdout ничего кроме протокола, но чужая библиотека может.
                        _lastError.value = "not JSON: ${line.take(160)}"
                        continue
                    }
                    _incoming.send(obj)
                }
            } catch (e: IOException) {
                _lastError.value = "io: ${e.message}"
            } finally {
                _alive.value = false
                _incoming.close()
            }
        }
    }

    override fun close() {
        _alive.value = false
        // Reader НЕ закрываем. Поток чтения почти наверняка висит в readLine() и
        // держит внутренний лок InputStreamReader; close() из другого потока ждёт
        // его release() — то есть никогда. На этом висял тест stdio-процесса:
        // весь сценарий проходил и умирал на последней строке — в shutdown().
        // Трубу закрывает снизу AcpAgentProcess.stop(): destroy() даёт читальному
        // потоку EOF/IOException, цикл выходит в свой finally и закрывает канал сам.
        runCatching { writer.close() }
        _incoming.close()
        scope.cancel()
    }
}

/** Compact-принтер для отправки. Атрибуты схемы (unknown keys и пр.) — как в acpJson. */
val compactJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
    explicitNulls = false
    prettyPrint = false
}

/**
 * Запуск агента как отдельного процесса.
 *
 * На Android это единственный способ дать приложению «внешний мозг»: `kimi acp` живёт
 * в proot-госте, и сюда попадает команда-обёртка (`proot ... kimi acp`). stdout — протокол,
 * stderr — логи, которые мы показываем в диагностике.
 */
class AcpAgentProcess(private val command: List<String>, private val onStderr: (String) -> Unit = {}) {

    private var process: Process? = null

    fun start(): AcpLink {
        val pb = ProcessBuilder(command).redirectErrorStream(false)
        val p = pb.start()
        process = p
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch(Dispatchers.IO) {
            p.errorStream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.forEach { onStderr(it) }
            }
        }
        return AcpTransport(
            p.inputStream.bufferedReader(Charsets.UTF_8),
            BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8)),
            scope,
        )
    }

    /** Убийство по-хорошему, потом по расписанию. Иначе осиротевший агент съест phantom-лимит. */
    fun stop() {
        val p = process ?: return
        runCatching { p.destroy() }
        runCatching {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        runCatching { p.destroyForcibly() }
        process = null
    }

    fun isAlive(): Boolean = process?.isAlive == true
}

/**
 * In-memory транспорт «агент внутри приложения».
 *
 * На Android нельзя запустить `java -cp …` — второго JVM нет. Поэтому мок-агент живёт
 * в том же процессе, а [PipeTransport] даёт ему ровно тот же контракт (JsonObject туда/
 * обратно), что и реальный stdio. Протокольный код при этом не дублируется: AcpClient
 * работает поверх того же [AcpLink].
 */
class PipeTransport private constructor(
    private val inbound: Channel<JsonObject>,
    private val outbound: Channel<JsonObject>,
) : AcpLink {

    override val incoming: ReceiveChannel<JsonObject> = inbound
    private val _alive = MutableStateFlow(true)
    override val alive: StateFlow<Boolean> = _alive

    override suspend fun send(obj: JsonObject) {
        if (outbound.isClosedForSend) {
            _alive.value = false
            throw AcpException(-32000, "pipe closed")
        }
        outbound.send(obj)
    }

    override fun close() {
        inbound.close(); outbound.close(); _alive.value = false
    }

    companion object {
        /** Перекрёстная пара: то, что пишет одна сторона, читает другая. */
        fun newPair(capacity: Int = 512): Pair<PipeTransport, PipeTransport> {
            val c2a = Channel<JsonObject>(capacity)
            val a2c = Channel<JsonObject>(capacity)
            return PipeTransport(a2c, c2a) to PipeTransport(c2a, a2c)
        }

        /**
         * Поднимает мокагента (читающий BufferedReader / пишущий Writer) в отдельном
         * потоке внутри этого же процесса и возвращает клиентскую половину транспорта.
         */
        fun inProcess(
            agentMain: (BufferedReader, java.io.Writer) -> Unit,
        ): Pair<AcpLink, () -> Unit> {
            val (client, agent) = newPair()

            // Агент думает, что читает построчно. На самом деле берёт JsonObject из канала
            // и перепечатывает его в компактный JSON — ровно как это делает реальный stdio.
            val reader = object : BufferedReader(object : java.io.Reader() {
                override fun read(cbuf: CharArray, off: Int, len: Int): Int = -1
                override fun close() {}
            }) {
                override fun readLine(): String? = try {
                    runBlocking {
                        withTimeoutOrNull(120_000) {
                            agent.incoming.receive().let {
                                compactJson.encodeToString(JsonObject.serializer(), it)
                            }
                        }
                    }
                } catch (e: Exception) { null }
            }

            val writer = object : java.io.Writer() {
                private val sb = StringBuilder()
                override fun write(cbuf: CharArray, off: Int, len: Int) {
                    sb.appendRange(cbuf, off, off + len)
                    while (true) {
                        val i = sb.indexOf("\n")
                        if (i < 0) break
                        val line = sb.substring(0, i)
                        sb.delete(0, i + 1)
                        if (line.isBlank()) continue
                        val obj = try {
                            acpJson.parseToJsonElement(line).jsonObject
                        } catch (e: Exception) { continue }
                        try { runBlocking { agent.send(obj) } } catch (e: Exception) { return }
                    }
                }
                override fun flush() {}
                override fun close() { runCatching { agent.close() } }
            }

            val t = Thread({ runCatching { agentMain(reader, writer) } }, "acp-inproc-agent")
                .apply { isDaemon = true }
            t.start()
            return client to { runCatching { client.close() }; t.interrupt() }
        }
    }
}
