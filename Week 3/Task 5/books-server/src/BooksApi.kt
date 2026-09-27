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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class BookHit(
    val key: String = "",
    val title: String = "",
    val authors: List<String> = emptyList(),
    val year: Int? = null,
    val languages: List<String> = emptyList(),
    val hasFullText: Boolean = false,
)

@Serializable
data class BooksResult(
    val count: Int = 0,
    val books: List<BookHit> = emptyList(),
)

// Тонкий клиент к Open Library (публичный, без ключа).
// Поиск — search.json, детали — прямой lookup ключа/ISBN.
class BooksApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun search(query: String, limit: Int, lang: String?): BooksResult {
        val raw: JsonObject = http.get("https://openlibrary.org/search.json") {
            parameter("q", query)
            parameter("limit", limit.coerceIn(1, 30))
            parameter("fields", "key,title,author_name,first_publish_year,language,ia")
            if (!lang.isNullOrBlank()) parameter("language", lang)
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo)")
        }.body()
        val docs = raw["docs"] as? JsonArray ?: return BooksResult()
        val total = (raw["numFound"] as? JsonPrimitive)?.intOrNull ?: docs.size
        val books = docs.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun strs(k: String) = (o[k] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val ia = strs("ia")
            BookHit(
                key = str("key").orEmpty(),
                title = str("title").orEmpty(),
                authors = strs("author_name"),
                year = (o["first_publish_year"] as? JsonPrimitive)?.intOrNull,
                languages = strs("language"),
                hasFullText = ia.isNotEmpty(),
            )
        }.filter { it.title.isNotBlank() }
        return BooksResult(count = total, books = books)
    }

    // Детали: lookup ключа Open Library (/works/OL..W, /books/OL..M) или ISBN.
    suspend fun details(id: String): String {
        val path = when {
            id.startsWith("/works/") || id.startsWith("/books/") || id.startsWith("/authors/") -> id
            id.matches(Regex("[0-9Xx-]{10,17}")) -> "/isbn/${id.filter { it.isDigit() || it == 'X' || it == 'x' }}"
            id.startsWith("OL") -> if (id.endsWith("M")) "/books/$id" else "/works/$id"
            else -> "/works/$id"
        }.trim()
        val raw: JsonObject = http.get("https://openlibrary.org$path.json") {
            header("User-Agent", "ai-advent-week3-task5/1.0 (demo)")
        }.body()
        return json.encodeToString(JsonObject.serializer(), raw)
    }

    fun close() = http.close()
}
