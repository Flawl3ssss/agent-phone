package dev.kimiterminal.acp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Транспорт через TCP-сокет на loopback.
 *
 * ПОЧЕМУ НЕ «ПРОСТО ЗАПУСТИТЬ KIMI». Android запрещает приложению А исполнять бинарь из
 * песочницы приложения Б: у Termux свой uid, свои библиотеки (glibc вместо Bionic) и свой
 * mount namespace, поэтому ProcessBuilder нашего процесса до `node`/`kimi` оттуда не
 * дотянется как его ни настраивай. Единственный разрешённый канал между двумя
 * приложениями на одном устройстве — loopback: мост
 * (`tools/termux/kimi-acp-bridge.mjs`) слушает в Termux, сюда подключаемся на 127.0.0.1.
 *
 * Разрешение INTERNET в манифесте обязательное даже для 127.0.0.1: без него connect
 * бросает SecurityException, и на телефоне это выглядит как «странная ошибка сети».
 */
class AcpSocketAgent(
    private val host: String = "127.0.0.1",
    private val port: Int = 8712,
    private val connectTimeoutMs: Int = 5000,
    private val onLog: (String) -> Unit = {},
) {
    private var socket: Socket? = null
    private var scope: CoroutineScope? = null

    /**
     * Подключается к мосту и возвращает готовый к работе [AcpLink].
     *
     * Метод блокирующий — вызывать только из IO-контекста: с главного потока Android
     * срезает NetworkOnMainThreadException ещё до того, как станет видно настоящую
     * причину (неподнятый мост).
     */
    fun start(): AcpLink {
        val s = Socket()
        // Задержка Негольда здесь враг: ACP-стриминг — десятки крохотных сообщений,
        // и без tcpNoDelay каждое дожидается подтверждения доставки, превращая
        // печатающийся ответ в слайд-шоу.
        s.tcpNoDelay = true
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { s.close() }
            // Диагноз сразу с лечением: чаще всего мост просто не запущен.
            throw AcpException(
                -32000,
                "мост не отвечает на $host:$port (${e.javaClass.simpleName}: ${e.message}). " +
                    "В Termux должно быть запущено: node ~/agent-phone/tools/termux/kimi-acp-bridge.mjs",
            )
        }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        socket = s
        scope = sc
        onLog("сокет открыт: $host:$port (наш локальный порт ${s.localPort})")
        val transport = AcpTransport(
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)),
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)),
            scope = sc,
            // Читателя отпускаем закрытием сокета. reader.close() из этого потока —
            // верный дедлок: он ждёт внутренний лок, который держит сам висящий в
            // readLine() читатель (на этом тест stdio-процесса висел целый день).
            onStreamEnd = {
                runCatching { s.close() }
                onLog("сокет закрыт: входящий поток оборвался (мост лег или сеть порвалась)")
            },
        )
        return object : AcpLink by transport {
            override fun close() {
                runCatching { transport.close() }
                runCatching { s.close() }
                sc.cancel()
                onLog("сокет закрыт по shutdown")
            }
        }
    }

    /** Идемпотентно: и штатная остановка сессии, и вызов из onCleared(). */
    fun stop() {
        runCatching { socket?.close() }
        scope?.cancel()
        socket = null
        scope = null
    }

    /** Жив ли ещё сокет — для статуса в UI, чтобы «нет связи» не выглядело как «думает». */
    val connected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed && !it.isInputShutdown } == true
}
