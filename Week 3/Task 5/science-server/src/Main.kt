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

// MCP-сервер научных статей (День 20, без оркестратора).
// LLM зовёт напрямую: search_works -> work_details.
// Запуск: ./kotlin run --module=science-server [-- <port>]  (default 3003)
// URL: http://127.0.0.1:3003/mcp
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3003
    val api = ScienceApi()
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { api.close() }
        println("[science-server] stopped")
    })

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-science-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.addTool(
        name = "search_works",
        description = "ARTICLES-ONLY. Search scholarly works via OpenAlex (free, no key). " +
            "Use for 'только статьи' constraint. " +
            "Returns {count, works:[{title,authors,year,doi,openAlexId,citedBy,openAccessUrl}]}. " +
            "If count=0 fall back to other servers per user instruction. " +
            "Next step optionally: work_details(doi).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Scholarly topic, e.g. Dyatlov Pass avalanche hypothermia")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max works, 1-25. Default 10.")
                }
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: query")
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 10
        if (limit !in 1..25) return@addTool err("limit must be 1-25")
        try {
            ok(json.encodeToString(api.search(query, limit)))
        } catch (e: Exception) {
            err("OpenAlex request failed: ${e.message}")
        }
    }

    mcpServer.addTool(
        name = "work_details",
        description = "ARTICLES-ONLY. Work metadata via Crossref by DOI (free, no key). " +
            "Input: DOI from search_works (with or without https://doi.org/ prefix). Returns raw Crossref JSON.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("doi") {
                    put("type", "string")
                    put("description", "DOI, e.g. 10.1038/nature12345")
                }
            },
            required = listOf("doi"),
        ),
    ) { request ->
        var doi = request.arguments?.get("doi")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@addTool err("Missing required arg: doi")
        doi = doi.removePrefix("https://doi.org/").removePrefix("http://doi.org/").removePrefix("doi:").trim()
        if (doi.isBlank() || !doi.contains("/")) return@addTool err("Bad DOI: '$doi'")
        try {
            ok(api.detailsByDoi(doi))
        } catch (e: Exception) {
            err("Crossref request failed: ${e.message}")
        }
    }

    println("[science-server] serving MCP on http://127.0.0.1:$port/mcp")
    println("[science-server] tools: search_works, work_details")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}

private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
