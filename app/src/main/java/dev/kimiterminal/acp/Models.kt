package dev.kimiterminal.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Модели Agent Client Protocol v1.
 *
 * Источник истины: agent-client-protocol/schema/v1/schema.json (170 $defs) и
 * @agentclientprotocol/sdk@1.4.0 — тот же SDK, что у packages/acp-server в kimi-code 0.42.0.
 *
 * Принципы, выстраданные на живом прогоне (см. tools/acp-smoke.sh):
 *  - protocolVersion — **integer**, не дата-строка как в MCP;
 *  - `session/load`, `session/resume`, `session/fork` требуют `cwd` (required);
 *  - на неизвестные поля не падаем: агент имеет право их прислать.
 */

const val ACP_PROTOCOL_VERSION = 1

val acpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
    explicitNulls = false
}

// ───────────────────────────────────────────────────────────────── initialize

@Serializable
data class Implementation(
    val name: String? = null,
    val title: String? = null,
    val version: String? = null,
)

@Serializable
data class FileSystemCapabilities(
    val readTextFile: Boolean = false,
    val writeTextFile: Boolean = false,
)

@Serializable
data class ClientCapabilities(
    val fs: FileSystemCapabilities = FileSystemCapabilities(),
    val terminal: Boolean = false,
)

@Serializable
data class InitializeRequest(
    val protocolVersion: Int = ACP_PROTOCOL_VERSION,
    val clientCapabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: Implementation? = null,
)

@Serializable
data class PromptCapabilities(
    val image: Boolean = false,
    val audio: Boolean = false,
    val embeddedContext: Boolean = false,
)

/** Присутствие ключа = возможность объявлена (в ACP это `{}`-маркеры). */
@Serializable
data class SessionCapabilities(
    val list: JsonElement? = null,
    val resume: JsonElement? = null,
    val close: JsonElement? = null,
    val delete: JsonElement? = null,
    val fork: JsonElement? = null,
    val additionalDirectories: JsonElement? = null,
)

@Serializable
data class McpCapabilities(val http: Boolean = false, val sse: Boolean = false)

@Serializable
data class AgentAuthCapabilities(val logout: JsonElement? = null)

@Serializable
data class AgentCapabilities(
    val loadSession: Boolean = false,
    val promptCapabilities: PromptCapabilities = PromptCapabilities(),
    val mcpCapabilities: McpCapabilities = McpCapabilities(),
    val sessionCapabilities: SessionCapabilities = SessionCapabilities(),
    val auth: AgentAuthCapabilities = AgentAuthCapabilities(),
) {
    fun has(name: String): Boolean = when (name) {
        "list" -> sessionCapabilities.list != null
        "resume" -> sessionCapabilities.resume != null
        "close" -> sessionCapabilities.close != null
        "delete" -> sessionCapabilities.delete != null
        "fork" -> sessionCapabilities.fork != null
        "additionalDirectories" -> sessionCapabilities.additionalDirectories != null
        else -> false
    }
}

@Serializable
data class AuthMethod(
    val type: String,
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val args: List<String> = emptyList(),
    val env: JsonObject? = null,
)

@Serializable
data class InitializeResponse(
    val protocolVersion: Int,
    val agentCapabilities: AgentCapabilities = AgentCapabilities(),
    val authMethods: List<AuthMethod> = emptyList(),
    val agentInfo: Implementation? = null,
)

// ───────────────────────────────────────────────────────────────── sessions

@Serializable
data class McpHeader(val name: String, val value: String)

@Serializable
data class McpServer(
    val name: String,
    val type: String? = null,          // "http" | "sse" | "stdio" | "acp"
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val headers: List<McpHeader> = emptyList(),
)

@Serializable
data class NewSessionRequest(
    val cwd: String,
    val additionalDirectories: List<String> = emptyList(),
    val mcpServers: List<McpServer> = emptyList(),
)

@Serializable
data class SessionMode(val id: String, val name: String, val description: String? = null)

@Serializable
data class SessionModeState(val currentModeId: String, val availableModes: List<SessionMode> = emptyList())

@Serializable
data class ConfigOptionValue(val value: String, val name: String)

@Serializable
data class SessionConfigOption(
    val id: String,
    val name: String,
    val type: String? = null,           // "select" | "boolean"
    val category: String? = null,
    val currentValue: JsonElement? = null,
    val options: List<ConfigOptionValue> = emptyList(),
)

@Serializable
data class NewSessionResponse(
    val sessionId: String,
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption>? = null,
)

@Serializable
data class SessionInfo(
    val sessionId: String,
    val cwd: String? = null,
    val title: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ListSessionsResponse(val sessions: List<SessionInfo> = emptyList(), val nextCursor: String? = null)

// ───────────────────────────────────────────────────────────────── prompt

@Serializable
data class TextContent(val type: String = "text", val text: String)

@Serializable
data class ImageContent(val type: String = "image", val data: String, val mimeType: String)

@Serializable
data class ResourceLink(
    val type: String = "resource_link",
    val uri: String,
    val name: String? = null,
    val mimeType: String? = null,
)

/** ContentBlock — union. Держим как закрытый набор: audio у Kimi false. */
@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val uri: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
) {
    companion object {
        fun text(s: String) = ContentBlock("text", text = s)
        fun image(base64: String, mime: String) = ContentBlock("image", data = base64, mimeType = mime)
        fun resourceLink(uri: String, name: String? = null, mime: String? = null) =
            ContentBlock("resource_link", uri = uri, name = name, mimeType = mime)
    }
}

@Serializable
enum class StopReason {
    @SerialName("end_turn") EndTurn,
    @SerialName("max_tokens") MaxTokens,
    @SerialName("max_turn_requests") MaxTurnRequests,
    @SerialName("refusal") Refusal,
    @SerialName("cancelled") Cancelled,
}

@Serializable
data class PromptResponse(val stopReason: StopReason)

// ───────────────────────────────────────────────────────────────── tool calls

@Serializable
data class ToolCallLocation(val path: String, val line: Int? = null)

@Serializable
data class Diff(val path: String, val oldText: String? = null, val newText: String)

@Serializable
data class PlanEntry(val content: String, val priority: String = "medium", val status: String = "pending")

@Serializable
data class Cost(val amount: Double, val currency: String)

/**
 * Один из 11 вариантов `session/update` (схема v1).
 * Неизвестные варианты сохраняем как Unknown — клиент обязан деградировать, а не падать.
 */
@Serializable
data class SessionUpdate(
    val sessionUpdate: String,
    // ContentChunk
    val content: JsonElement? = null,
    // ToolCall / ToolCallUpdate
    val toolCallId: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val locations: List<ToolCallLocation> = emptyList(),
    val contentItems: List<JsonElement>? = null,
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
    // Plan
    val entries: List<PlanEntry> = emptyList(),
    // usage_update
    val used: Long? = null,
    val size: Long? = null,
    val cost: Cost? = null,
    // config / mode / commands
    val configOptions: List<SessionConfigOption>? = null,
    val currentModeId: String? = null,
    val availableModes: List<SessionMode>? = null,
    val availableCommands: List<AvailableCommand>? = null,
)

@Serializable
data class AvailableCommand(val name: String, val description: String? = null)

/** Уведомление целиком: {sessionId, update}. */
@Serializable
data class SessionNotification(val sessionId: String, val update: SessionUpdate)

// ───────────────────────────────────────────────────────────────── permission

@Serializable
data class PermissionOption(val optionId: String, val name: String, val kind: String)

@Serializable
data class RequestPermissionRequest(
    val sessionId: String,
    val toolCall: JsonObject? = null,
    val options: List<PermissionOption> = emptyList(),
)

@Serializable
data class PermissionOutcome(val outcome: String, val optionId: String? = null)

@Serializable
data class RequestPermissionResult(val outcome: PermissionOutcome)

// ───────────────────────────────────────────────────────────────── fs / terminal

@Serializable
data class ReadTextFileRequest(val sessionId: String, val path: String, val line: Int? = null, val limit: Int? = null)

@Serializable
data class ReadTextFileResponse(val content: String)

@Serializable
data class WriteTextFileRequest(val sessionId: String, val path: String, val content: String)

@Serializable
data class EnvVariable(val name: String, val value: String)

@Serializable
data class CreateTerminalRequest(
    val sessionId: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: List<EnvVariable> = emptyList(),
    val cwd: String? = null,
    val outputByteLimit: Int? = null,
)

@Serializable
data class CreateTerminalResponse(val terminalId: String)

@Serializable
data class TerminalExitStatus(val exitCode: Int? = null, val signal: String? = null)

@Serializable
data class TerminalOutputResponse(val output: String, val truncated: Boolean = false, val exitStatus: TerminalExitStatus? = null)

// ───────────────────────────────────────────────────────────────── elicitation

@Serializable
data class ElicitationRequest(
    val sessionId: String,
    val mode: String = "form",
    val message: String? = null,
    val requestedSchema: JsonObject? = null,
)

@Serializable
data class ElicitationResponse(val action: String, val content: JsonObject? = null)

// ───────────────────────────────────────────────────────────────── ошибки

/** Коды ACP/JSON-RPC, которые клиент обязан различать. */
object AcpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val AUTH_REQUIRED = -32000
    const val RESOURCE_NOT_FOUND = -32002
}

class AcpException(val code: Int, override val message: String, val data: JsonElement? = null) :
    Exception("ACP $code: $message")
