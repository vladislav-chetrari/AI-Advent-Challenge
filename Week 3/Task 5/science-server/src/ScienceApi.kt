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
data class WorkHit(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val year: Int? = null,
    val doi: String? = null,
    val openAlexId: String = "",
    val citedBy: Int = 0,
    val openAccessUrl: String? = null,
)

@Serializable
data class WorksResult(
    val count: Int = 0,
    val works: List<WorkHit> = emptyList(),
)

// Тонкий клиент: OpenAlex (поиск, без ключа) + Crossref (детали по DOI, без ключа).
class ScienceApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    suspend fun search(query: String, limit: Int): WorksResult {
        val raw: JsonObject = http.get("https://api.openalex.org/works") {
            parameter("search", query)
            parameter("per-page", limit.coerceIn(1, 25))
            parameter("mailto", "ai-advent-demo@example.com")
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo; mailto:ai-advent-demo@example.com)")
        }.body()
        val total = (raw["meta"] as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull ?: 0
        val arr = raw["results"]?.jsonArray ?: return WorksResult(count = total)
        val works = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val authorships = o["authorships"]?.jsonArray
            val authors = authorships?.mapNotNull {
                ((it as? JsonObject)?.get("author") as? JsonObject)?.get("display_name")?.jsonPrimitive?.contentOrNull
            } ?: emptyList()
            val oa = (o["open_access"] as? JsonObject)?.get("oa_url")?.jsonPrimitive?.contentOrNull
                ?: (o["primary_location"] as? JsonObject)?.get("landing_page_url")?.jsonPrimitive?.contentOrNull
            WorkHit(
                title = title,
                authors = authors,
                year = o["publication_year"]?.jsonPrimitive?.intOrNull,
                doi = o["doi"]?.jsonPrimitive?.contentOrNull,
                openAlexId = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                citedBy = o["cited_by_count"]?.jsonPrimitive?.intOrNull ?: 0,
                openAccessUrl = oa,
            )
        }
        return WorksResult(count = total, works = works)
    }

    suspend fun detailsByDoi(doi: String): String {
        val raw: JsonObject = http.get("https://api.crossref.org/works/$doi") {
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo; mailto:ai-advent-demo@example.com)")
        }.body()
        return json.encodeToString(JsonObject.serializer(), raw)
    }

    fun close() = http.close()
}
