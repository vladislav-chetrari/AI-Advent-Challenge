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

// MCP-сервер прессы и базы (День 20, без оркестратора).
// LLM зовёт напрямую: wiki_overview -> search_news.
// Запуск: ./kotlin run --module=press-server [-- <port>]  (default 3004)
// URL: http://127.0.0.1:3004/mcp
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3004
    val api = PressApi()
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { api.close() }
        println("[press-server] stopped")
    })

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-press-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.addTool(
        name = "wiki_overview",
        description = "REFERENCE. Short intro + canonical URL via Wikipedia (free, no key). " +
            "Use as step 1 to fix spelling/entities before searching other servers. " +
            "Args: topic (e.g. Перевал Дятлова, Dyatlov Pass), lang ru|en, default ru.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("topic") {
                    put("type", "string")
                    put("description", "Article title, e.g. Перевал Дятлова")
                }
                putJsonObject("lang") {
                    put("type", "string")
                    put("description", "Wiki language: ru or en. Default ru.")
                }
            },
            required = listOf("topic"),
        ),
    ) { request ->
        val topic = request.arguments?.get("topic")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: topic")
        val lang = request.arguments?.get("lang")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "ru"
        if (lang != "ru" && lang != "en") return@addTool err("lang must be ru|en")
        try {
            ok(api.wiki(topic, lang))
        } catch (e: Exception) {
            err("Wikipedia request failed: ${e.message}")
        }
    }

    mcpServer.addTool(
        name = "search_news",
        description = "NEWS-ONLY. Search world press via GDELT DOC 2.0 (free, no key, 65 languages). " +
            "Use for 'новости/упоминания в прессе' constraint, usually as last fallback step. " +
            "Returns {count, articles:[{title,url,domain,date,language}]}. GDELT is rate-limited, cache results.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "News query, e.g. Dyatlov Pass")
                }
                putJsonObject("maxrecords") {
                    put("type", "integer")
                    put("description", "Max articles, 1-100. Default 20.")
                }
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: query")
        val maxRecords = request.arguments?.get("maxrecords")?.jsonPrimitive?.intOrNull ?: 20
        if (maxRecords !in 1..100) return@addTool err("maxrecords must be 1-100")
        try {
            ok(json.encodeToString(api.searchNews(query, maxRecords)))
        } catch (e: Exception) {
            err("GDELT request failed (rate-limited?): ${e.message}")
        }
    }

    println("[press-server] serving MCP on http://127.0.0.1:$port/mcp")
    println("[press-server] tools: wiki_overview, search_news")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}

private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
