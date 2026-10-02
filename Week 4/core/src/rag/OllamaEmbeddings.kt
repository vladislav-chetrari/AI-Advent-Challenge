package core.rag

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Эмбеддинги через локальную Ollama: POST {baseUrl}/api/embed {model, input[]}.
//
// Почему это отдельная реализация EmbeddingProvider: RagService разницы не видит,
// меняется только источник векторов. Вектора Ollama НЕ нормированы —
// нормируем под косинус=dot, как требует VectorStore.
//
// Какая модель (совет для русского корпуса):
// - bge-m3 (1024) — мультиязычная, русский хороший. Выбор по умолчанию. ~1.2 ГБ.
// - mxbai-embed-large (1024) — чуть быстрее, русский средний. ~670 МБ.
// - nomic-embed-text (768) — лёгкая и быстрая, но уклон в английский. ~270 МБ.
//
// Запуск: `ollama serve` + `ollama pull bge-m3`. Проверка: `curl localhost:11434/api/tags`.
class OllamaEmbedder(
    val model: String = "bge-m3",
    private val baseUrl: String = System.getenv("WEEK4_OLLAMA_URL")?.trimEnd('/')
        ?.takeIf { it.isNotBlank() } ?: "http://localhost:11434",
    private val batchSize: Int = 32,
) : EmbeddingProvider {
    override val name: String = "ollama:$model"

    // Dim узнаём по факту от модели (map — лишь стартовое предположение).
    @Volatile
    override var dim: Int = KNOWN_DIMS[model] ?: 1024
        private set

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { requestTimeoutMillis = 300_000 }
        }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(batchSize)) {
            out += embedBatch(batch)
        }
        return out
    }

    private suspend fun embedBatch(batch: List<String>): List<FloatArray> {
        val resp: EmbedResponse = try {
            val http = client.post("$baseUrl/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(EmbedRequest(model = model, input = batch))
            }
            if (!http.status.isSuccess()) {
                throw IllegalStateException("Ollama HTTP ${http.status.value}. Запущена? (ollama serve, ollama pull $model)")
            }
            http.body()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Ollama недоступна на $baseUrl: ${e.message}. Проверь `ollama serve` и `ollama pull $model`.",
                e,
            )
        }
        if (resp.embeddings.size != batch.size) {
            throw IllegalStateException("Ollama вернула ${resp.embeddings.size} векторов на ${batch.size} текстов")
        }
        return resp.embeddings.map { arr ->
            if (dim != arr.size) dim = arr.size
            l2normalize(arr.toFloatArray())
        }
    }

    fun close() = client.close()

    companion object {
        val KNOWN_DIMS = mapOf(
            "bge-m3" to 1024,
            "mxbai-embed-large" to 1024,
            "nomic-embed-text" to 768,
            "snowflake-arctic-embed" to 1024,
            "all-minilm" to 384,
        )
    }
}

@Serializable
private data class EmbedRequest(val model: String, val input: List<String>)

@Serializable
private data class EmbedResponse(val embeddings: List<List<Float>> = emptyList())
