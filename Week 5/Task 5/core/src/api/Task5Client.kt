package core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Ошибка Task 5 сервера (HTTP-статус + OpenAI-код из ErrorEnvelope).
class Task5Exception(val httpStatus: Int, val code: String, override val message: String) : Exception(message)

@Serializable
private data class DeletedResponse(val deleted: String = "")

// Тонкий HTTP-клиент Task 5 сервера (OpenAI-совместимый API).
// Вся сериализация внутри — CLI-модуль дергает только эти suspend-функции.
class Task5Client(
    baseUrl: String = "http://localhost:8080",
    private val apiKey: String = "",
) {
    private val base: String = baseUrl.trimEnd('/')
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
    }

    private fun apiKeyHeader(): String? = apiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    private suspend fun fail(status: Int, raw: String): Nothing {
        val env = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), raw) }.getOrNull()
        throw Task5Exception(status, env?.error?.code ?: "http_$status", env?.error?.message?.ifBlank { raw.take(300) } ?: raw.take(300))
    }

    suspend fun health(): HealthResponse {
        val http = client.get("$base/health") {
            apiKeyHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        if (!http.status.isSuccess()) fail(http.status.value, runCatching { http.bodyAsText() }.getOrDefault(""))
        return json.decodeFromString(HealthResponse.serializer(), http.bodyAsText())
    }

    suspend fun models(): ModelsResponse {
        val http = client.get("$base/v1/models") {
            apiKeyHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        if (!http.status.isSuccess()) fail(http.status.value, runCatching { http.bodyAsText() }.getOrDefault(""))
        return json.decodeFromString(ModelsResponse.serializer(), http.bodyAsText())
    }

    suspend fun chat(
        messages: List<ChatMessageDto>,
        model: String = "",
        temperature: Double = 0.7,
        maxTokens: Int = 512,
    ): ChatResponse {
        val http = client.post("$base/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            apiKeyHeader()?.let { header(HttpHeaders.Authorization, it) }
            setBody(ChatRequest(model = model, messages = messages, temperature = temperature, max_tokens = maxTokens))
        }
        if (!http.status.isSuccess()) fail(http.status.value, runCatching { http.bodyAsText() }.getOrDefault(""))
        return json.decodeFromString(ChatResponse.serializer(), http.bodyAsText())
    }

    suspend fun embed(model: String, texts: List<String>): EmbedResponse {
        // input собираем руками: string при одном тексте, массив при нескольких.
        val input = if (texts.size == 1) JsonPrimitive(texts[0])
        else kotlinx.serialization.json.JsonArray(texts.map { JsonPrimitive(it) })
        val body = buildJsonObject {
            put("model", model)
            put("input", input)
        }
        val http = client.post("$base/v1/embeddings") {
            contentType(ContentType.Application.Json)
            apiKeyHeader()?.let { header(HttpHeaders.Authorization, it) }
            setBody(body.toString())
        }
        if (!http.status.isSuccess()) fail(http.status.value, runCatching { http.bodyAsText() }.getOrDefault(""))
        return json.decodeFromString(EmbedResponse.serializer(), http.bodyAsText())
    }

    suspend fun docsAdd(type: String, title: String, input: String): DocsResponse {
        val http = client.post("$base/v1/docs") {
            contentType(ContentType.Application.Json)
            apiKeyHeader()?.let { header(HttpHeaders.Authorization, it) }
            setBody(DocsRequest(type = type, title = title, input = input))
        }
        if (!http.status.isSuccess()) fail(http.status.value, runCatching { http.bodyAsText() }.getOrDefault(""))
        return json.decodeFromString(DocsResponse.serializer(), http.bodyAsText())
    }

    suspend fun docsList(): List<DocListItem> {
        val http = client.get("$base/v1/docs") {
            apiKeyHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        if (!http.status.isSuccess()) fail(http.status.value, runCatching { http.bodyAsText() }.getOrDefault(""))
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(DocListItem.serializer()),
            http.bodyAsText(),
        )
    }

    suspend fun docsDelete(id: String): String {
        // id содержит слэши (wikipedia/ru/...) — кодируем посегментно.
        val path = id.split("/").joinToString("/") { it.encodeURLPathPart() }
        val http = client.delete("$base/v1/docs/$path") {
            apiKeyHeader()?.let { header(HttpHeaders.Authorization, it) }
        }
        if (!http.status.isSuccess()) fail(http.status.value, runCatching { http.bodyAsText() }.getOrDefault(""))
        return json.decodeFromString(DeletedResponse.serializer(), http.bodyAsText()).deleted
    }

    fun close() = client.close()
}
