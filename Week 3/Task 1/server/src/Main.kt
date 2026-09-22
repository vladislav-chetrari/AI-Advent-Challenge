import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Standalone time MCP-сервер на Streamable HTTP.
// Запуск: ./kotlin run --module=server  (порт по умолчанию 3000, можно передать первым аргументом)
// Никакой связи с клиентом: клиент подключается сам по http://127.0.0.1:3000/mcp
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3000

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-time-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    // Tool 1: текущее время. Опциональный аргумент timezone (IANA, напр. Europe/Moscow).
    mcpServer.addTool(
        name = "get_current_time",
        description = "Returns current time as ISO-8601. Optional arg: timezone (IANA name, e.g. Europe/Moscow).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("timezone") {
                    put("type", "string")
                    put("description", "IANA timezone, e.g. Europe/Moscow. Default: UTC.")
                }
            },
        ),
    ) { request ->
        val tzArg = request.arguments?.get("timezone")?.jsonPrimitive?.contentOrNull
        val zone = runCatching { ZoneId.of(tzArg ?: "UTC") }.getOrElse { ZoneId.of("UTC") }
        val now: ZonedDateTime = ZonedDateTime.ofInstant(Instant.now(), zone)
        val text = buildString {
            append("now=").append(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            append(" zone=").append(zone.id)
            append(" epoch=").append(now.toEpochSecond())
        }
        CallToolResult(content = listOf(TextContent(text)))
    }

    // Tool 2: конвертация метки времени между зонами.
    mcpServer.addTool(
        name = "convert_time",
        description = "Converts ISO-8601 time from one timezone to another.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("time") {
                    put("type", "string")
                    put("description", "ISO-8601 instant, e.g. 2026-09-22T10:00:00Z")
                }
                putJsonObject("to_timezone") {
                    put("type", "string")
                    put("description", "Target IANA timezone, e.g. Europe/Moscow")
                }
            },
            required = listOf("time", "to_timezone"),
        ),
    ) { request ->
        val args = request.arguments
        val timeArg = args?.get("time")?.jsonPrimitive?.contentOrNull
        val toTzArg = args?.get("to_timezone")?.jsonPrimitive?.contentOrNull
        if (timeArg == null || toTzArg == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Missing required args: time, to_timezone")),
                isError = true,
            )
        }
        val result = runCatching {
            val zone = ZoneId.of(toTzArg)
            val instant = Instant.parse(timeArg)
            ZonedDateTime.ofInstant(instant, zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        result.fold(
            onSuccess = { CallToolResult(content = listOf(TextContent("converted=$it zone=$toTzArg"))) },
            onFailure = {
                CallToolResult(
                    content = listOf(TextContent("Bad input: ${it.message}")),
                    isError = true,
                )
            },
        )
    }

    println("[time-server] serving MCP on http://127.0.0.1:$port/mcp")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}
