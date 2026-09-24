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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// MCP-сервер вокруг https://catfact.ninja (День 17).
// Запуск: ./kotlin run --module=server  (порт по умолчанию 3000, можно первым аргументом)
// Кодинг-агент подключается сам: http://127.0.0.1:3000/mcp  (Streamable HTTP)
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3000
    val api = CatApi()
    val json = Json { prettyPrint = false }

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-catfact-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    // Tool 1: случайный факт. Опциональный max_length (catfact.ninja режет по длине).
    mcpServer.addTool(
        name = "get_random_cat_fact",
        description = "Returns one random cat fact from catfact.ninja. Optional arg: max_length (max fact length in chars).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("max_length") {
                    put("type", "integer")
                    put("description", "Maximum fact length in characters, e.g. 140. Omit for any length.")
                }
            },
        ),
    ) { request ->
        val maxLength = request.arguments?.get("max_length")?.jsonPrimitive?.intOrNull
        if (maxLength != null && maxLength <= 0) {
            return@addTool CallToolResult(
                content = listOf(TextContent("max_length must be positive")),
                isError = true,
            )
        }
        runCatching { api.randomFact(maxLength) }.fold(
            onSuccess = { CallToolResult(content = listOf(TextContent(json.encodeToString(it)))) },
            onFailure = {
                CallToolResult(
                    content = listOf(TextContent("catfact.ninja unavailable: ${it.message}")),
                    isError = true,
                )
            },
        )
    }

    // Tool 2: пачка фактов с пагинацией.
    mcpServer.addTool(
        name = "get_cat_facts",
        description = "Returns a page of cat facts. Use limit (1-100, default 5), page (>=1), optional max_length.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many facts to return, 1-100. Default: 5.")
                }
                putJsonObject("max_length") {
                    put("type", "integer")
                    put("description", "Maximum fact length in characters. Omit for any length.")
                }
                putJsonObject("page") {
                    put("type", "integer")
                    put("description", "Page number, >= 1. Default: 1.")
                }
            },
        ),
    ) { request ->
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 5
        val page = request.arguments?.get("page")?.jsonPrimitive?.intOrNull ?: 1
        val maxLength = request.arguments?.get("max_length")?.jsonPrimitive?.intOrNull
        if (limit !in 1..100) {
            return@addTool CallToolResult(
                content = listOf(TextContent("limit must be 1-100")),
                isError = true,
            )
        }
        runCatching { api.facts(limit, maxLength, page) }.fold(
            onSuccess = { CallToolResult(content = listOf(TextContent(json.encodeToString(it)))) },
            onFailure = {
                CallToolResult(
                    content = listOf(TextContent("catfact.ninja unavailable: ${it.message}")),
                    isError = true,
                )
            },
        )
    }

    // Tool 3: породы кошек с пагинацией.
    mcpServer.addTool(
        name = "get_cat_breeds",
        description = "Returns cat breeds (breed, country, origin, coat, pattern). Use limit (1-100, default 5), page (>=1).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many breeds to return, 1-100. Default: 5.")
                }
                putJsonObject("page") {
                    put("type", "integer")
                    put("description", "Page number, >= 1. Default: 1.")
                }
            },
        ),
    ) { request ->
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 5
        val page = request.arguments?.get("page")?.jsonPrimitive?.intOrNull ?: 1
        if (limit !in 1..100) {
            return@addTool CallToolResult(
                content = listOf(TextContent("limit must be 1-100")),
                isError = true,
            )
        }
        runCatching { api.breeds(limit, page) }.fold(
            onSuccess = { CallToolResult(content = listOf(TextContent(json.encodeToString(it)))) },
            onFailure = {
                CallToolResult(
                    content = listOf(TextContent("catfact.ninja unavailable: ${it.message}")),
                    isError = true,
                )
            },
        )
    }

    println("[catfact-server] serving MCP on http://127.0.0.1:$port/mcp")
    println("[catfact-server] tools: get_random_cat_fact, get_cat_facts, get_cat_breeds")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}
