package dev.kimiterminal

import dev.kimiterminal.acp.AcpLink
import dev.kimiterminal.acp.AcpSocketAgent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Транспорт через loopback-сокет — тот самый, которым приложение подключается к мосту
 * в Termux (`tools/termux/kimi-acp-bridge.mjs`).
 *
 * Настоящего Kimi здесь нет намеренно: «мост» подменяется ServerSocket прямо в тесте,
 * и проверяется ровно тот слой, который мы к нему прикручиваем — фрейминг NDJSON поверх
 * TCP, доставка в канал и ЗАКРЫТИЕ. Про последнее не стоит молчать: stdio-вариант умирал
 * именно на нём (сценарий зелёный, висит последняя строка, Diagnosis — только через
 * 150-секундный таймаут). Здесь тот же сценарий стоит 8 секунд и падает внятно.
 */
class SocketTransportTest {

    /** При зависании печатает стек потока, а не просто «timed out». */
    @get:Rule
    val globalTimeout = Timeout(60, TimeUnit.SECONDS)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: FakeBridge? = null
    private var agent: AcpSocketAgent? = null

    @After
    fun tearDown() {
        runCatching { agent?.stop() }
        runCatching { bridge?.close() }
        scope.cancel()
    }

    /** Заменитель моста: одно соединение, читает NDJSON, на каждую запись зовёт handler. */
    private class FakeBridge(private val onLine: (String) -> Unit) {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val received = ConcurrentLinkedQueue<String>()
        private val socketRef = AtomicReference<Socket?>(null)
        private val accepted = CountDownLatch(1)

        init {
            val t = Thread({
                runCatching {
                    val s = server.accept()
                    socketRef.set(s)
                    accepted.countDown()
                    val rd = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    while (true) {
                        val line = rd.readLine() ?: break
                        if (line.isNotBlank()) { received.add(line); onLine(line) }
                    }
                } catch (_: Exception) {
                    // Сервер закрыт tearDown'ом — выходим молча, это штатный конец теста.
                }
            }, "fake-bridge")
            t.isDaemon = true
            t.start()
        }

        fun port(): Int = server.localPort

        fun awaitAccepted(ms: Long = 8000): Boolean = accepted.await(ms, TimeUnit.MILLISECONDS)

        fun awaitCount(n: Int, ms: Long = 8000): Boolean {
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                if (received.size >= n) return true
                Thread.sleep(25)
            }
            return false
        }

        /** Одна запись = одна строка с \n: по-другому NDJSON не понимает. */
        fun sendLine(text: String) {
            val s = socketRef.get()
            if (s == null) { fail("мост не принял соединение"); return }
            s.getOutputStream().write((text + "\n").toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
        }

        fun close() {
            runCatching { socketRef.get()?.close() }
            runCatching { server.close() }
        }
    }

    private fun openBridge(handler: (String) -> Unit): Int {
        val b = FakeBridge(handler)
        bridge = b
        return b.port()
    }

    @Test
    fun `запись уходит одной строкой, ответ приходит в канал`() {
        val port = openBridge { bridge!!.sendLine("""{"jsonrpc":"2.0","id":7,"result":{"ok":true}}""") }
        agent = AcpSocketAgent(port = port)
        val link: AcpLink = agent!!.start()
        assertTrue("мост не принял соединение за 8 с", bridge!!.awaitAccepted())

        runBlocking {
            withTimeout(8000) {
                link.send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 7L)
                    put("method", "probe/echo")
                    // Перевод строки ЗНАЧЕНИЯ — самый вредный случай: он обязан быть
                    // экранирован, иначе запись разорвётся на две и парсер сойдёт с ума.
                    put("params", buildJsonObject { put("text", "раз\nдва") })
                })
            }
        }

        assertTrue("мост должен увидеть запись", bridge!!.awaitCount(1))
        val lines = bridge!!.received.toList()
        assertEquals("ровно одна строка на одну отправленную запись", 1, lines.size)
        val parsed = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("probe/echo", parsed["method"]!!.jsonPrimitive.content)

        val reply = runBlocking { withTimeout(8000) { link.incoming.receive() } }
        assertEquals(7L, reply["id"]!!.jsonPrimitive.long)
        assertEquals(true, reply["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `close отпускает читателя и не подвешивается`() {
        // Мост намеренно молчит: поток чтения клиента висит в readLine(), и именно
        // такое закрытие раньше приводило к взаимному ожиданию локов.
        val port = openBridge { }
        agent = AcpSocketAgent(port = port)
        val link = agent!!.start()
        assertTrue(bridge!!.awaitAccepted())

        val done = CountDownLatch(1)
        val closer = Thread({
            runCatching { link.close() }
            done.countDown()
        }, "closer")
        closer.start()
        if (!done.await(8, TimeUnit.SECONDS)) {
            closer.interrupt()
            fail("close() не вернулся за 8 с — дедлок закрытия (читатель держит лок)")
        }
        // Читатель обязан погаснуть САМ: сокет закрыт снизу, он получает IOException.
        runBlocking {
            withTimeout(8000) { while (link.alive.value) yield() }
        }
        assertEquals(false, link.alive.value)
    }

    @Test
    fun `смерть моста видна клиенту, а не превращается в вечное ожидание`() {
        val port = openBridge { }
        agent = AcpSocketAgent(port = port)
        val link = agent!!.start()
        assertTrue(bridge!!.awaitAccepted())
        // Рвём с другой стороны: клиент должен увидеть EOF, закрыть канал и погасить
        // alive. Иначе любой incoming.receive() висит навечно, а UI показывает «думает».
        bridge!!.close()
        runBlocking {
            withTimeout(8000) { while (link.alive.value) yield() }
        }
        assertEquals(false, link.alive.value)
        assertEquals("сокет должен быть отпущен", false, agent!!.connected)
    }
}
