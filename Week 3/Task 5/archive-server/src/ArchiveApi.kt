import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ArchiveHit(
    val identifier: String = "",
    val title: String = "",
    val date: String? = null,
    val downloads: Long = 0,
    val mediatype: String = "",
    val url: String = "",
)

@Serializable
data class ArchiveResult(
    val count: Int = 0,
    val items: List<ArchiveHit> = emptyList(),
)

// Тонкий клиент к Internet Archive AdvancedSearch (публичный, без ключа).
class ArchiveApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    suspend fun search(query: String, limit: Int, mediatype: String?): ArchiveResult {
        val q = buildString {
            append("($query)")
            if (!mediatype.isNullOrBlank()) append(" AND mediatype:$mediatype")
        }
        val raw: JsonObject = http.get("https://archive.org/advancedsearch.php") {
            parameter("q", q)
            parameter("fl[]", "identifier")
            parameter("fl[]", "title")
            parameter("fl[]", "date")
            parameter("fl[]", "downloads")
            parameter("fl[]", "mediatype")
            parameter("rows", limit.coerceIn(1, 30))
            parameter("output", "json")
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo)")
        }.body()
        val response = raw["response"] as? JsonObject ?: return ArchiveResult()
        val total = (response["numFound"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
        val docs = response["docs"]?.jsonArray ?: return ArchiveResult(count = total)
        val items = docs.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["identifier"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (id.isBlank()) return@mapNotNull null
            ArchiveHit(
                identifier = id,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                date = o["date"]?.jsonPrimitive?.contentOrNull,
                downloads = o["downloads"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
                mediatype = o["mediatype"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                url = "https://archive.org/details/$id",
            )
        }
        return ArchiveResult(count = total, items = items)
    }

    suspend fun details(identifier: String): String {
        val raw: JsonObject = http.get("https://archive.org/metadata/$identifier") {
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo)")
        }.body()
        return json.encodeToString(JsonObject.serializer(), raw)
    }

    fun close() = http.close()
}
