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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// MCP-сервер слежения за погодой (День 18).
// Сценарий: LLM зовёт watch_weather(city, interval) -> сервер геокодит город,
// кладёт задачу в SQLite и фоновый Scheduler опрашивает Open-Meteo по интервалу.
// Перезапуск безопасен: всё состояние в SQLite, в памяти ничего нет.
// Запуск: ./kotlin run --module=server [-- <port> [dbPath]]
// Агент подключается: http://127.0.0.1:3000/mcp
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3000
    val dbFile = args.getOrNull(1)?.let { File(it) }
        ?: System.getenv("WEATHER_DB")?.let { File(it) }
        ?: Store.defaultDbFile()

    val store = Store(dbFile)
    val api = WeatherApi()
    val json = Json { prettyPrint = false }
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val scheduler = Scheduler(store, api)
    scheduler.start(scope)

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { scope.cancel() }
        runCatching { api.close() }
        runCatching { store.close() }
        println("[weather-server] stopped, db=$dbFile")
    })

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-weather-scheduler", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    mcpServer.addTool(
        name = "watch_weather",
        description = "Start watching weather in a city with a given interval. " +
            "Args: city (e.g. Kazan, Berlin), interval (e.g. 30s, 10m, 1h; plain number = minutes, min 30s). " +
            "Geocodes the city, stores the task in SQLite, fetches immediately, then polls in background.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name in any language, e.g. Kazan")
                }
                putJsonObject("interval") {
                    put("type", "string")
                    put("description", "Poll interval: 30s, 10m, 1h, 1d. Plain number = minutes.")
                }
            },
            required = listOf("city", "interval"),
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.contentOrNull
        val intervalRaw = request.arguments?.get("interval")?.jsonPrimitive?.contentOrNull
        if (city.isNullOrBlank() || intervalRaw.isNullOrBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Missing required args: city, interval")),
                isError = true,
            )
        }
        val intervalMs = try {
            Interval.parse(intervalRaw)
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(
                content = listOf(TextContent(e.message ?: "Bad interval")),
                isError = true,
            )
        }
        val place = try {
            api.geocode(city)
        } catch (e: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Geocoding failed for '$city': ${e.message}")),
                isError = true,
            )
        }
        val watch = store.upsertWatch(city, place.displayName, place.lat, place.lon, intervalMs, store.nowMs())
        // Первый замер сразу, чтобы summary был непустым; при ошибке — фон ретраит.
        val firstFetchNote = try {
            val s = api.fetch(watch.lat, watch.lon)
            store.addReading(watch.id, store.nowMs(), s.temp, s.wind)
            "first reading ok: temp=${s.temp} wind=${s.wind}"
        } catch (e: Exception) {
            "first fetch failed (scheduler will retry): ${e.message}"
        }
        val refreshed = store.findByCity(city) ?: watch
        CallToolResult(
            content = listOf(
                TextContent(
                    json.encodeToString(refreshed) + "\n$firstFetchNote",
                ),
            ),
        )
    }

    mcpServer.addTool(
        name = "list_watches",
        description = "Lists all weather watch tasks with intervals and last run time.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(json.encodeToString(store.listWatches()))))
    }

    mcpServer.addTool(
        name = "unwatch_weather",
        description = "Stops watching a city and deletes its task (readings are deleted too). Arg: city.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name as given to watch_weather")
                }
            },
            required = listOf("city"),
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.contentOrNull
        if (city.isNullOrBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Missing required arg: city")),
                isError = true,
            )
        }
        val deleted = store.deleteByCity(city)
        CallToolResult(content = listOf(TextContent(if (deleted) "stopped watching '$city'" else "no watch for '$city'")))
    }

    mcpServer.addTool(
        name = "get_weather_summary",
        description = "Returns aggregated weather for a watched city: count, min/max/avg temp, avg wind, last reading. " +
            "Args: city (required), hours (optional, default 24).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "City name as given to watch_weather")
                }
                putJsonObject("hours") {
                    put("type", "integer")
                    put("description", "Lookback window in hours, 1-720. Default: 24.")
                }
            },
            required = listOf("city"),
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.contentOrNull
        if (city.isNullOrBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Missing required arg: city")),
                isError = true,
            )
        }
        val hours = request.arguments?.get("hours")?.jsonPrimitive?.intOrNull ?: 24
        if (hours !in 1..720) {
            return@addTool CallToolResult(
                content = listOf(TextContent("hours must be 1-720")),
                isError = true,
            )
        }
        val watch = store.findByCity(city)
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("No watch for '$city'. Call watch_weather first.")),
                isError = true,
            )
        CallToolResult(content = listOf(TextContent(json.encodeToString(store.summary(watch, hours)))))
    }

    println("[weather-server] db=$dbFile")
    println("[weather-server] serving MCP on http://127.0.0.1:$port/mcp")
    println("[weather-server] tools: watch_weather, list_watches, unwatch_weather, get_weather_summary")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}
