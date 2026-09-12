package dev.kimiterminal

import dev.kimiterminal.acp.AcpAgentProcess
import dev.kimiterminal.acp.AcpClient
import dev.kimiterminal.acp.AcpLink
import dev.kimiterminal.acp.ClientCapabilities
import dev.kimiterminal.acp.FileSystemCapabilities
import dev.kimiterminal.acp.McpServer
import dev.kimiterminal.acp.RequestPermissionResult
import dev.kimiterminal.acp.ContentBlock
import dev.kimiterminal.acp.CreateTerminalResponse
import dev.kimiterminal.acp.ElicitationRequest
import dev.kimiterminal.acp.ElicitationResponse
import dev.kimiterminal.acp.PermissionOutcome
import dev.kimiterminal.acp.PipeTransport
import dev.kimiterminal.acp.ReadTextFileResponse
import dev.kimiterminal.acp.SessionNotification
import dev.kimiterminal.acp.SessionUpdate
import dev.kimiterminal.acp.StopReason
import dev.kimiterminal.acp.TerminalExitStatus
import dev.kimiterminal.acp.TerminalOutputResponse
import dev.kimiterminal.agent.MockAcpAgent
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Гейт G3′ в виде теста: **Kotlin-клиент против живого ACP-агента**.
 *
 * Два транспорта, один и тот же сценарий:
 *   1) subprocess — мок-агент в отдельной JVM, NDJSON по настоящим pipe'ам
 *      (это ровно то, что будет с `kimi acp` внутри proot);
 *   2) in-process — тот же агент поверх in-memory канала (то, что поедет на телефоне).
 *
 * Если красный — весь план стоит на песке. Если зелёный — протокольный слой реален.
 */
class AcpClientLiveTest {

    /**
     * Страховка от «тихого» зависания. JUnit-овский Timeout не просто валит тест —
     * он печатает stack trace застрявшего потока, так что в логе CI видно КОНКРЕТНУЮ
     * строку блокировки, а не 30 минут молчания до cancelled.
     */
    @get:Rule val hardTimeout = org.junit.rules.Timeout(150, java.util.concurrent.TimeUnit.SECONDS)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Рабочий каталог тестовой сессии: и в session/new, и в проверках путей — из одного места. */
    private val cwd = "/tmp"
    private var process: AcpAgentProcess? = null
    private var shutdown: (() -> Unit)? = null

    @After fun tearDown() {
        runCatching { process?.stop() }
        runCatching { shutdown?.invoke() }
        scope.cancel()
    }

    private val javaBin: String get() = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
    private val classpath: String get() = System.getProperty("java.class.path")

    private fun spawnMockProcess(): AcpLink {
        val p = AcpAgentProcess(listOf(javaBin, "-cp", classpath, "dev.kimiterminal.agent.MockKimiMainKt")) { line ->
            stderr.addLast(line); while (stderr.size > 200) stderr.pollFirst()
        }
        process = p
        return p.start()
    }

    private fun spawnMockInProcess(): AcpLink {
        val (link, stop) = PipeTransport.inProcess { r, w -> MockAcpAgent(r, w).run() }
        shutdown = stop
        return link
    }

    private val stderr = ConcurrentLinkedDeque<String>()

    /** Один и тот же сценарий для обоих транспортов. */
    private fun runScenario(transport: AcpLink) = runBlocking {
        val client = AcpClient(transport, scope, requestTimeoutMs = 10_000)
        val updates = ConcurrentLinkedDeque<SessionUpdate>()
        val gotPermission = CompletableDeferred<String>()
        val gotFsRead = CompletableDeferred<String>()
        val gotFsWrite = CompletableDeferred<String>()
        val gotTermCreate = CompletableDeferred<String>()
        val gotElicit = CompletableDeferred<String>()

        val h = AcpClient.Handlers().apply {
            sessionUpdate = { n -> updates.addLast(n.update) }
            requestPermission = { req ->
                val title = req.toolCall?.get("title")?.jsonPrimitive?.content ?: "?"
                gotPermission.complete(title)
                // Отвечаем ровно тем optionId, который предложил агент — не своим.
                val allow = req.options.first { it.kind == "allow_once" || it.kind == "allow_always" }
                RequestPermissionResult(PermissionOutcome("selected", allow.optionId))
            }
            readTextFile = { req -> gotFsRead.complete(req.path); ReadTextFileResponse("{\"old\":true}") }
            writeTextFile = { req -> gotFsWrite.complete(req.path) }
            createTerminal = { req -> gotTermCreate.complete(req.command); CreateTerminalResponse("term_1") }
            terminalOutput = { _, _ -> TerminalOutputResponse("v22.23.2\n", false, null) }
            waitForTerminalExit = { _, _ -> TerminalExitStatus(0, null) }
            killTerminal = { _, _ -> }
            releaseTerminal = { _, _ -> }
            createElicitation = { req ->
                gotElicit.complete(req.message ?: "")
                ElicitationResponse("accept", buildJsonObject { put("deploy", kotlinx.serialization.json.JsonPrimitive(false)) })
            }
        }
        client.startDispatch(h)

        // ── initialize: сверяем матрицу с задокументированной ────────────────
        val init = withTimeout(20_000) {
            client.initialize(
                ClientCapabilities(FileSystemCapabilities(true, true), terminal = true),
            )
        }
        assertEquals("протокол v1", 1, init.protocolVersion)
        assertEquals("Kimi Code CLI", init.agentInfo?.name)
        assertTrue("loadSession", init.agentCapabilities?.loadSession == true)
        assertTrue("image", init.agentCapabilities?.promptCapabilities?.image == true)
        assertTrue("audio=false", init.agentCapabilities?.promptCapabilities?.audio == false)
        assertTrue("mcp http", init.agentCapabilities?.mcpCapabilities?.http == true)
        assertNotNull("sessionCapabilities", init.agentCapabilities?.sessionCapabilities)
        assertEquals("terminal-auth первым", "terminal", init.authMethods?.firstOrNull()?.type)

        // ── authenticate ─────────────────────────────────────────────────────
        client.authenticate("login")

        // ── session/new с additionalDirectories и MCP ────────────────────────
        val ns = client.newSession(
            cwd,
            additionalDirectories = listOf("/tmp/extra"),
            mcpServers = listOf(McpServer(name = "game-preview", type = "http", url = "http://127.0.0.1:59100/mcp")),
        )
        assertNotNull("sessionId", ns.sessionId)
        assertTrue("configOptions", (ns.configOptions ?: emptyList()).isNotEmpty())
        assertEquals("mode по умолчанию", "default", ns.modes?.currentModeId)

        // ── prompt: полный turn со всеми обратными RPC ───────────────────────
        val res = withTimeout(40_000) { client.prompt(ns.sessionId, listOf(ContentBlock.text("собери проект"))) }
        assertEquals("stopReason", StopReason.EndTurn, res.stopReason)

        assertEquals("fs/read path", "$cwd/config.json", withTimeout(20_000) { gotFsRead.await() })
        assertEquals("permission title", "Edit $cwd/config.json", withTimeout(20_000) { gotPermission.await() })
        assertEquals("fs/write path", "$cwd/config.json", withTimeout(20_000) { gotFsWrite.await() })
        assertEquals("terminal cmd", "node", withTimeout(5_000) { gotTermCreate.await() })
        assertEquals("elicitation", "Деплоить на itch сейчас?", withTimeout(5_000) { gotElicit.await() })

        // ── что мы увидели в ленте (T2: состояния из протокола, не из текста) ─
        val kinds = updates.map { it.sessionUpdate }
        for (need in listOf(
            "agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update",
            "plan", "usage_update", "config_option_update", "available_commands_update",
        )) assertTrue("нет $need в $kinds", kinds.contains(need))

        // plan обязан доехать с приоритетами (грабли PlanEntry: все три поля обязательны)
        val plan = updates.first { it.sessionUpdate == "plan" }
        assertTrue("plan непустой", plan.entries.isNotEmpty())
        assertNotNull("priority", plan.entries.first().priority)
        assertNotNull("status", plan.entries.first().status)

        // usage_update → расходомер
        val usage = updates.first { it.sessionUpdate == "usage_update" }
        assertTrue("used>0", (usage.used ?: 0L) > 0)
        assertEquals("валюта", "USD", usage.cost?.currency)

        // ── жизненный цикл сессий ────────────────────────────────────────────
        val ls = client.listSessions("/tmp")
        assertTrue("list видит сессию", ls.sessions.any { it.sessionId == ns.sessionId })

        val fk = client.forkSession(ns.sessionId, "/tmp")
        assertTrue("fork дал новый id", fk != ns.sessionId)

        client.loadSession(ns.sessionId, "/tmp")
        client.setMode(ns.sessionId, "plan")
        client.setConfigOption(ns.sessionId, "model", "kimi-code")
        client.closeSession(ns.sessionId)

        // ── негативные случаи: протокол должен держать удар ──────────────────
        // пустой cwd формально валиден для JSON, но невалиден для протокола —
        // шлём запрос в обёрнутом виде, чтобы проверить ответ агента
        try {
            client.rawRequest("session/load", buildJsonObject { put("sessionId", ns.sessionId) })
            fail("load без cwd обязан падать (LoadSessionRequest требует cwd)")
        } catch (e: dev.kimiterminal.acp.AcpException) {
            assertEquals(-32602, e.code)
        }
        try {
            client.rawRequest("providers/list", kotlinx.serialization.json.buildJsonObject {})
            fail("providers/list не реализован — обязан быть methodNotFound")
        } catch (e: dev.kimiterminal.acp.AcpException) {
            assertEquals(-32601, e.code)
        }
        // канал жив после ошибок
        assertTrue("канал жив", transport.alive.value)
        client.shutdown()
    }

    @Test fun `клиент против мока по настоящему stdio-процессу`() = runScenario(spawnMockProcess())

    @Test fun `клиент против мока in-process`() = runScenario(spawnMockInProcess())

    @Test fun `смерть процесса агента не вешает клиента`() = runBlocking {
        val transport = spawnMockProcess()
        val client = AcpClient(transport, scope, requestTimeoutMs = 10_000)
        client.startDispatch(AcpClient.Handlers())
        val init = withTimeout(20_000) { client.initialize(ClientCapabilities()) }
        assertEquals(1, init.protocolVersion)
        process!!.stop()
        // любой последующий вызов обязан упасть с ошибкой, а не висеть вечно
        try {
            withTimeout(15_000) { client.listSessions("/tmp") }
            fail("после смерти процесса обязан быть error, а не ответ")
        } catch (e: dev.kimiterminal.acp.AcpException) {
            // ок
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            fail("клиент завис после смерти агента — это и есть грабля «вечного spinner»")
        }
    }
}
