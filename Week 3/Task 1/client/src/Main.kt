import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

// Standalone MCP-клиент на Streamable HTTP. Сервер НЕ поднимает —
// он должен уже работать: ./kotlin run --module=server
// Запуск: ./kotlin run --module=client [-- http://127.0.0.1:3000/mcp]
fun main(args: Array<String>) = runBlocking {
    val url = args.firstOrNull() ?: "http://127.0.0.1:3000/mcp"
    println("[client] connecting to $url ...")

    val httpClient = HttpClient()
    val transport = StreamableHttpClientTransport(client = httpClient, url = url)
    val client = Client(clientInfo = Implementation(name = "week3-task1-client", version = "1.0.0"))
    try {
        withTimeout(60.seconds) { client.connect(transport) }
        println("[client] CONNECTED. server=${client.serverVersion?.name} ${client.serverVersion?.version}")

        // Главная проверка задачи: список инструментов корректно возвращается.
        val tools = withTimeout(30.seconds) { client.listTools().tools }
        println("[client] TOOLS count=${tools.size}")
        tools.forEachIndexed { i, t ->
            println("  [$i] name=${t.name}")
            println("      description=${t.description}")
            println("      inputSchema=${t.inputSchema}")
        }
        check(tools.isNotEmpty()) { "MCP server returned empty tools list!" }
        check(tools.any { it.name == "get_current_time" }) { "Expected tool 'get_current_time' missing!" }

        // Бонус-проверка (не требуется задачей, но убеждает на видео): вызов тула.
        val call = withTimeout(30.seconds) {
            client.callTool(
                name = "get_current_time",
                arguments = mapOf("timezone" to "Europe/Moscow"),
            )
        }
        val text = (call.content.firstOrNull() as? TextContent)?.text
        println("[client] callTool get_current_time -> $text")
        check(!text.isNullOrBlank()) { "callTool returned empty content" }

        println("[client] OK: connection established, tools listed, tool called.")
    } finally {
        runCatching { client.close() }
        httpClient.close()
        println("[client] bye.")
    }
}
