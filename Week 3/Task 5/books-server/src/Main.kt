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

// MCP-сервер книг (День 20, без оркестратора).
// LLM зовёт его напрямую: search_books -> book_details.
// Fallback-логика на стороне LLM текстом: "если книг нет (count=0), ищи статьи".
// Запуск: ./kotlin run --module=books-server [-- <port>]  (default 3001)
// URL: http://127.0.0.1:3001/mcp
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3001
    val api = BooksApi()
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { api.close() }
        println("[books-server] stopped")
    })

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-books-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.addTool(
        name = "search_books",
        description = "BOOKS-ONLY. Search books via Open Library (free, no key). " +
            "Use for 'только книги' constraint. " +
            "Returns {count, books:[{key,title,authors,year,languages,hasFullText}]}. " +
            "If count=0 there are no books — per user instruction fall back to science/news servers. " +
            "Next step optionally: book_details(query) for preview info.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Book topic, e.g. Dyatlov Pass, Перевал Дятлова")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max books, 1-30. Default 10.")
                }
                putJsonObject("lang") {
                    put("type", "string")
                    put("description", "Optional ISO language filter, e.g. rus, eng. Omit for any.")
                }
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: query")
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 10
        if (limit !in 1..30) return@addTool err("limit must be 1-30")
        val lang = request.arguments?.get("lang")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        try {
            ok(json.encodeToString(api.search(query, limit, lang)))
        } catch (e: Exception) {
            err("Open Library request failed: ${e.message}")
        }
    }

    mcpServer.addTool(
        name = "book_details",
        description = "BOOKS-ONLY. Book details via Open Library lookup (free, no key). " +
            "Input: key from search_books (e.g. /works/OL166894W), edition key (/books/OL...M), or ISBN. " +
            "Returns raw Open Library JSON (description, subjects, covers).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Open Library key (/works/OL...W, /books/OL...M) or ISBN, e.g. /works/OL166894W")
                }
            },
            required = listOf("id"),
        ),
    ) { request ->
        val id = request.arguments?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: id (key from search_books or ISBN)")
        try {
            ok(api.details(id))
        } catch (e: Exception) {
            err("Open Library lookup failed: ${e.message}")
        }
    }

    println("[books-server] serving MCP on http://127.0.0.1:$port/mcp")
    println("[books-server] tools: search_books, book_details")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}

private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
