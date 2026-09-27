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
data class NewsHit(
    val title: String = "",
    val url: String = "",
    val domain: String = "",
    val date: String? = null,
    val language: String? = null,
)

@Serializable
data class NewsResult(
    val count: Int = 0,
    val articles: List<NewsHit> = emptyList(),
)

// Тонкий клиент: GDELT DOC 2.0 (новости, без ключа) + Wikipedia (overview, без ключа).
class PressApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    suspend fun searchNews(query: String, maxRecords: Int): NewsResult {
        val raw: JsonObject = http.get("https://api.gdeltproject.org/api/v2/doc/doc") {
            parameter("query", query)
            parameter("mode", "artlist")
            parameter("maxrecords", maxRecords.coerceIn(1, 100))
            parameter("format", "json")
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo)")
        }.body()
        val arr = raw["articles"]?.jsonArray ?: return NewsResult()
        val articles = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            NewsHit(
                title = title,
                url = url,
                domain = o["domain"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                date = o["seendate"]?.jsonPrimitive?.contentOrNull,
                language = o["language"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return NewsResult(count = articles.size, articles = articles)
    }

    suspend fun wiki(topic: String, lang: String): String {
        val host = if (lang == "en") "en.wikipedia.org" else "ru.wikipedia.org"
        val raw: JsonObject = http.get("https://$host/w/api.php") {
            parameter("action", "query")
            parameter("prop", "extracts|info")
            parameter("exintro", "true")
            parameter("explaintext", "true")
            parameter("inprop", "url")
            parameter("titles", topic)
            parameter("format", "json")
            parameter("formatversion", "2")
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo)")
        }.body()
        return json.encodeToString(JsonObject.serializer(), raw)
    }

    fun close() = http.close()
}
