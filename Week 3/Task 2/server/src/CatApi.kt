import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatFact(val fact: String, val length: Int)

@Serializable
data class CatFactsPage(
    val data: List<CatFact> = emptyList(),
    val current_page: Int = 1,
    val last_page: Int = 1,
)

@Serializable
data class CatBreed(
    val breed: String,
    val country: String = "",
    val origin: String = "",
    val coat: String = "",
    val pattern: String = "",
)

@Serializable
data class CatBreedsPage(
    val data: List<CatBreed> = emptyList(),
    val current_page: Int = 1,
    val last_page: Int = 1,
)

// Тонкий клиент к https://catfact.ninja (публичный, без ключа).
// Покрывает 3 REST-эндпоинта, под каждый — свой MCP-тул в Main.kt.
class CatApi {
    private val base = "https://catfact.ninja"
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun randomFact(maxLength: Int? = null): CatFact {
        return http.get("$base/fact") {
            if (maxLength != null) parameter("max_length", maxLength)
        }.body()
    }

    suspend fun facts(limit: Int = 5, maxLength: Int? = null, page: Int = 1): CatFactsPage {
        return http.get("$base/facts") {
            parameter("limit", limit.coerceIn(1, 100))
            parameter("page", page.coerceAtLeast(1))
            if (maxLength != null) parameter("max_length", maxLength)
        }.body()
    }

    suspend fun breeds(limit: Int = 5, page: Int = 1): CatBreedsPage {
        return http.get("$base/breeds") {
            parameter("limit", limit.coerceIn(1, 100))
            parameter("page", page.coerceAtLeast(1))
        }.body()
    }

    fun close() = http.close()
}
