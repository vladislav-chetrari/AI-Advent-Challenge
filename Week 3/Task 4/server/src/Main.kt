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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// MCP-сервер пайплайна (День 19): search_quakes -> summarize_quakes -> save_report_to_file.
// Композиция — на стороне агента: выход search (quakes[]) — вход summarize,
// выход summarize (markdown) — вход save. Сервер тулзы друг друга не зовёт.
// Данные живые: USGS Earthquake API, без ключа.
// Запуск: ./kotlin run --module=server [-- <port>]
// Агент: http://127.0.0.1:3001/mcp
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 3001
    val api = QuakeApi()
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { api.close() }
        println("[quake-server] stopped")
    })

    val mcpServer = Server(
        serverInfo = Implementation(name = "week3-quake-pipeline", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    // 1/3 — получает данные. Выход {count, quakes[]} — вход для summarize_quakes.
    // Период задаётся либо парой start_date/end_date (YYYY-MM-DD, любой период
    // из каталога USGS), либо шорткатом days (последние N дней). Даты приоритетнее.
    // География — настоящий фильтр на стороне USGS (до limit):
    //  - place (обычно город) резолвится через геокодинг в круг 50 км по умолчанию;
    //    радиус меняется через maxradiuskm; страны — в прямоугольник;
    //  - либо задаётся явно через min/maxlatitude/longitude или circle.
    // У USGS НЕТ параметра place, только координаты:
    // https://earthquake.usgs.gov/fdsnws/event/1/
    mcpServer.addTool(
        name = "search_quakes",
        description = "STEP 1/3 Search earthquakes via USGS for ANY period. " +
            "Use start_date/end_date (YYYY-MM-DD) for arbitrary ranges, " +
            "or days for the last N days. " +
            "place (usually a city, e.g. Bucharest) is geocoded to a real USGS circle " +
            "filter (50 km by default, override with maxradiuskm); countries resolve to a rectangle. " +
            "Explicit bbox/circle params override place. " +
            "Returns {count, quakes:[{id,mag,place,time,ts,url,lat,lon,depthKm}]}. " +
            "Pass quakes[] into summarize_quakes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("min_magnitude") {
                    put("type", "number")
                    put("description", "Minimum magnitude, 0-10. Default 4.5.")
                }
                putJsonObject("days") {
                    put("type", "integer")
                    put("description", "Shorthand: last N days, 1-3650. Ignored if start_date/end_date given. Default 7.")
                }
                putJsonObject("start_date") {
                    put("type", "string")
                    put("description", "Range start, YYYY-MM-DD, e.g. 2011-03-01. Optional.")
                }
                putJsonObject("end_date") {
                    put("type", "string")
                    put("description", "Range end, YYYY-MM-DD, e.g. 2011-04-01. Defaults to today. Optional.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max events, 1-20000. Default 20. Applied AFTER geo filter on USGS side.")
                }
                putJsonObject("place") {
                    put("type", "string")
                    put("description", "City/place name, e.g. Bucharest. Geocoded to USGS circle (50 km default). Default empty.")
                }
                putJsonObject("min_latitude") {
                    put("type", "number")
                    put("description", "Explicit rectangle south, -90..90. Requires max_latitude/min_longitude/max_longitude. Overrides place.")
                }
                putJsonObject("max_latitude") {
                    put("type", "number")
                    put("description", "Explicit rectangle north, -90..90.")
                }
                putJsonObject("min_longitude") {
                    put("type", "number")
                    put("description", "Explicit rectangle west.")
                }
                putJsonObject("max_longitude") {
                    put("type", "number")
                    put("description", "Explicit rectangle east.")
                }
                putJsonObject("latitude") {
                    put("type", "number")
                    put("description", "Circle center lat, -90..90. Requires longitude+maxradiuskm. Overrides place.")
                }
                putJsonObject("longitude") {
                    put("type", "number")
                    put("description", "Circle center lon, -180..180.")
                }
                putJsonObject("maxradiuskm") {
                    put("type", "number")
                    put("description", "Circle radius km, 0..20001.6. With place: overrides the default 50 km city radius. With latitude/longitude: required.")
                }
                putJsonObject("orderby") {
                    put("type", "string")
                    put("description", "Order: time|time-asc|magnitude|magnitude-asc. Default time.")
                }
            },
        ),
    ) { request ->
        val minMag = request.arguments?.get("min_magnitude")?.jsonPrimitive?.doubleOrNull ?: 4.5
        val days = request.arguments?.get("days")?.jsonPrimitive?.intOrNull ?: 7
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 20
        val place = request.arguments?.get("place")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val startRaw = request.arguments?.get("start_date")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val endRaw = request.arguments?.get("end_date")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val minLat = request.arguments?.get("min_latitude")?.jsonPrimitive?.doubleOrNull
        val maxLat = request.arguments?.get("max_latitude")?.jsonPrimitive?.doubleOrNull
        val minLon = request.arguments?.get("min_longitude")?.jsonPrimitive?.doubleOrNull
        val maxLon = request.arguments?.get("max_longitude")?.jsonPrimitive?.doubleOrNull
        val clat = request.arguments?.get("latitude")?.jsonPrimitive?.doubleOrNull
        val clon = request.arguments?.get("longitude")?.jsonPrimitive?.doubleOrNull
        val crad = request.arguments?.get("maxradiuskm")?.jsonPrimitive?.doubleOrNull
        val orderby = request.arguments?.get("orderby")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "time"
        if (minMag !in 0.0..10.0) {
            return@addTool err("min_magnitude must be 0-10")
        }
        if (days !in 1..3650) {
            return@addTool err("days must be 1-3650")
        }
        if (limit !in 1..20000) {
            return@addTool err("limit must be 1-20000")
        }
        if (orderby !in setOf("time", "time-asc", "magnitude", "magnitude-asc")) {
            return@addTool err("orderby must be time|time-asc|magnitude|magnitude-asc")
        }
        val hasRect = listOf(minLat, maxLat, minLon, maxLon).any { it != null }
        val hasLatLon = listOf(clat, clon).any { it != null }
        if (hasRect && listOf(minLat, maxLat, minLon, maxLon).any { it == null }) {
            return@addTool err("rectangle needs all: min_latitude, max_latitude, min_longitude, max_longitude")
        }
        if (hasLatLon && listOf(clat, clon, crad).any { it == null }) {
            return@addTool err("circle needs all: latitude, longitude, maxradiuskm")
        }
        if (crad != null && place == null && !hasLatLon) {
            return@addTool err("maxradiuskm without place needs latitude+longitude")
        }
        if (crad != null && crad !in 0.0..20001.6) {
            return@addTool err("maxradiuskm must be 0..20001.6")
        }
        // maxradiuskm вместе с place (без явных latitude/longitude) —
        // переопределение дефолтного радиуса города (50 км).
        val today = java.time.LocalDate.now()
        val (start, end) = try {
            if (startRaw != null || endRaw != null) {
                val e = endRaw?.let { java.time.LocalDate.parse(it.trim()) } ?: today
                val s = startRaw?.let { java.time.LocalDate.parse(it.trim()) } ?: e.minusDays(30)
                if (e.isAfter(today)) return@addTool err("end_date ($e) is in the future")
                if (s.isAfter(e)) return@addTool err("start_date ($s) is after end_date ($e)")
                s to e
            } else {
                today.minusDays(days.toLong()) to today
            }
        } catch (e: java.time.format.DateTimeParseException) {
            return@addTool err("bad date, use YYYY-MM-DD: ${e.parsedString}")
        }
        // География: explicit params приоритетнее place.
        var rect: GeoRect? = null
        var circle: GeoCircle? = null
        var resolved: ResolvedArea? = null
        var geoWarning: String? = null
        if (hasRect) {
            rect = GeoRect(south = minLat!!, north = maxLat!!, west = minLon!!, east = maxLon!!)
            resolved = ResolvedArea(rect = rect, label = place ?: "explicit rectangle", source = "explicit")
        } else if (hasLatLon) {
            circle = GeoCircle(lat = clat!!, lon = clon!!, radiusKm = crad!!)
            resolved = ResolvedArea(circle = circle, label = place ?: "explicit circle", source = "explicit")
        } else if (place != null) {
            val area = try {
                PlaceGeocoder.resolve(place, crad)
            } catch (e: Exception) {
                null
            }
            if (area != null) {
                rect = area.rect
                circle = area.circle
                resolved = area
            } else {
                geoWarning = "place '$place' not geocoded, fallback to substring filter (may miss events, see USGS docs: no place param)"
            }
        }
        try {
            // place передаём дальше только для fallback-substring (без гео).
            // С гео substring отключён внутри QuakeApi — фильтр реальный, на стороне USGS.
            val quakes = api.search(minMag, start, end, limit, place, rect, circle, orderby)
            ok(json.encodeToString(buildJsonObject {
                put("count", quakes.size)
                put("start_date", start.toString())
                put("end_date", end.toString())
                if (resolved != null) {
                    val r = resolved.rect
                    if (r != null) {
                        putJsonObject("resolved_rect") {
                            put("south", r.south)
                            put("north", r.north)
                            put("west", r.west)
                            put("east", r.east)
                        }
                    }
                    val c = resolved.circle
                    if (c != null) {
                        putJsonObject("resolved_circle") {
                            put("lat", c.lat)
                            put("lon", c.lon)
                            put("radiusKm", c.radiusKm)
                        }
                    }
                    put("resolved_label", resolved.label)
                    put("resolved_source", resolved.source)
                }
                if (geoWarning != null) put("warning", geoWarning)
                put("quakes", json.encodeToJsonElement(JsonArray.serializer(), JsonArray(quakes.map {
                    json.encodeToJsonElement(Quake.serializer(), it)
                })))
            }))
        } catch (e: Exception) {
            err("USGS request failed: ${e.message}")
        }
    }

    // 2/3 — обрабатывает. Вход — quakes[] из search_quakes, выход {summary, markdown} — вход для save.
    mcpServer.addTool(
        name = "summarize_quakes",
        description = "STEP 2/3 Summarize quakes from search_quakes. " +
            "Input: quakes array (paste output of search_quakes). " +
            "Returns {summary:{count,maxMag,avgMag,...}, markdown}. Pass markdown into save_report_to_file.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("quakes") {
                    put("type", "array")
                    put("description", "Quake objects from search_quakes output")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Short label for the report header, e.g. 'M5+ last 7d'.")
                }
            },
            required = listOf("quakes"),
        ),
    ) { request ->
        val arr = request.arguments?.get("quakes")?.jsonArray
        if (arr == null) {
            return@addTool err("Missing required arg: quakes (array from search_quakes)")
        }
        if (arr.size > 2000) {
            return@addTool err("too many quakes (${arr.size}, max 2000), narrow search_quakes limit")
        }
        try {
            val quakes = arr.map { json.decodeFromJsonElement(Quake.serializer(), it) }
            val query = request.arguments?.get("query")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "last ${quakes.size} events"
            val summary = Summarizer.summarize(quakes)
            val markdown = Summarizer.renderMarkdown(query, summary)
            ok(json.encodeToString(buildJsonObject {
                put("summary", json.encodeToJsonElement(QuakeSummary.serializer(), summary))
                put("markdown", markdown)
            }))
        } catch (e: Exception) {
            err("summarize failed: ${e.message}")
        }
    }

    // 3/3 — сохраняет. Вход — markdown из summarize_quakes.
    mcpServer.addTool(
        name = "save_report_to_file",
        description = "STEP 3/3 Save report markdown to reports/<filename>. " +
            "Input: content (markdown from summarize_quakes), filename (e.g. quakes-japan.md).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Markdown from summarize_quakes")
                }
                putJsonObject("filename") {
                    put("type", "string")
                    put("description", "File name, e.g. quakes-report.md (.md/.txt/.json only)")
                }
            },
            required = listOf("content"),
        ),
    ) { request ->
        val content = request.arguments?.get("content")?.let {
            if (it is JsonObject) null else it.jsonPrimitive.contentOrNull
        }
        if (content.isNullOrBlank()) {
            return@addTool err("Missing required arg: content (markdown from summarize_quakes)")
        }
        val filename = request.arguments?.get("filename")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "report.md"
        try {
            val file = FileStore.save(content, filename)
            ok(json.encodeToString(buildJsonObject {
                put("path", file.absolutePath)
                put("bytes", file.length())
            }))
        } catch (e: IllegalArgumentException) {
            err(e.message ?: "bad file args")
        } catch (e: Exception) {
            err("save failed: ${e.message}")
        }
    }

    println("[quake-server] serving MCP on http://127.0.0.1:$port/mcp")
    println("[quake-server] tools: search_quakes, summarize_quakes, save_report_to_file")
    println("[quake-server] pipeline: search_quakes -> summarize_quakes -> save_report_to_file")
    embeddedServer(CIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = true)
}

private fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
private fun err(text: String) = CallToolResult(content = listOf(TextContent(text)), isError = true)
