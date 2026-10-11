package core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Тонкий клиент Ollama Embeddings: POST /api/embed {model, input} ->
// {embeddings: [[float]]}. Батчирование внутри (по 16, как в Task 3/4):
// длинные списки режем сами, вызывающий отдаёт всё сразу.
class EmbedClient(
    val model: String = EmbedDefaults.MODEL,
    baseUrl: String = "http://localhost:11434",
) {
    private val base: String = baseUrl.trimEnd('/')
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
    }

    suspend fun embed(texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty()) { "embed: пустой список текстов" }
        require(texts.size <= MAX_INPUTS) { "embed: слишком много текстов (${texts.size} > $MAX_INPUTS)" }
        val out = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(BATCH)) {
            out += postBatch(batch)
        }
        return out
    }

    // Зонд размерности модели (проверка после смены модели).
    suspend fun probeDim(): Int = embed(listOf("probe")).first().size

    private suspend fun postBatch(batch: List<String>): List<FloatArray> {
        val http = client.post("$base/api/embed") {
            contentType(ContentType.Application.Json)
            setBody(OllamaEmbedRequest(model = model, input = batch))
        }
        if (!http.status.isSuccess()) {
            val detail = runCatching { http.bodyAsText().take(300) }.getOrDefault("")
            throw IllegalStateException("Ollama embed HTTP ${http.status.value}: $detail")
        }
        val raw = runCatching { http.bodyAsText() }.getOrNull()
            ?: throw IllegalStateException("Ollama embed: пустой ответ")
        val resp = try {
            json.decodeFromString(OllamaEmbedResponse.serializer(), raw)
        } catch (e: Exception) {
            throw IllegalStateException("Ollama embed: не-JSON ответ")
        }
        if (resp.embeddings.size != batch.size) {
            throw IllegalStateException("Ollama embed: векторов ${resp.embeddings.size}, текстов ${batch.size}")
        }
        return resp.embeddings.map { list -> FloatArray(list.size) { i -> list[i].toFloat() } }
    }

    fun close() = client.close()

    companion object {
        const val BATCH: Int = 16
        const val MAX_INPUTS: Int = 64
        const val MAX_CHARS: Int = 8000
    }
}

@Serializable
private data class OllamaEmbedRequest(
    val model: String,
    val input: List<String>,
    // Короткий keep_alive: embedding-модель не должна вытеснять LLM из VRAM
    // надолго (на RTX 3060 12GB каждая сотня МБ на счету).
    val keep_alive: String = "5m",
)

@Serializable
private data class OllamaEmbedResponse(
    val embeddings: List<List<Double>> = emptyList(),
)
