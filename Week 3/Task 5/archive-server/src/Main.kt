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

// MCP-сервер архивов (День 20, без оркестратора).
// LLM зовёт напрямую: search_archive -> archive_details.
// Запуск: ./kotlin run --module=archive-server [-- <port>]  (default 3002)
// URL: http://127.0.0.1:3002/mcp
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3002
    val api = ArchiveApi()
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { api.close() }
        println("[archive-server] stopped")
    })

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-archive-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.addTool(
        name = "search_archive",
        description = "ARCHIVE-ONLY. Search scans/docs/audio in Internet Archive (free, no key). " +
            "Use for 'архивы/газеты-сканы/первоисточники' constraint. " +
            "Returns {count, items:[{identifier,title,date,downloads,mediatype,url}]}. " +
            "If count=0 fall back to other servers per user instruction. " +
            "Next step optionally: archive_details(identifier).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Archive topic, e.g. Dyatlov Pass, Перевал Дятлова")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max items, 1-30. Default 10.")
                }
                putJsonObject("mediatype") {
                    put("type", "string")
                    put("description", "Optional: texts, movies, audio, image. Omit for any.")
                }
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: query")
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 10
        if (limit !in 1..30) return@addTool err("limit must be 1-30")
        val mediatype = request.arguments?.get("mediatype")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        try {
            ok(json.encodeToString(api.search(query, limit, mediatype)))
        } catch (e: Exception) {
            err("Internet Archive request failed: ${e.message}")
        }
    }

    mcpServer.addTool(
        name = "archive_details",
        description = "ARCHIVE-ONLY. Item metadata + file list from Internet Archive (free, no key). " +
            "Input: identifier from search_archive (e.g. dyatlovpass1959). Returns raw metadata JSON.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("identifier") {
                    put("type", "string")
                    put("description", "Archive.org item identifier from search_archive")
                }
            },
            required = listOf("identifier"),
        ),
    ) { request ->
        val id = request.arguments?.get("identifier")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: identifier")
        try {
            ok(api.details(id))
        } catch (e: Exception) {
            err("Internet Archive metadata failed: ${e.message}")
        }
    }

    println("[archive-server] serving MCP on http://127.0.0.1:$port/mcp")
    println("[archive-server] tools: search_archive, archive_details")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}

private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
