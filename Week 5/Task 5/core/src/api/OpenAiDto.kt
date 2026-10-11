package core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable

// ---- OpenAI-совместимые DTO (chat + embeddings + models) ----

@Serializable
data class ChatMessageDto(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String = "",
    val messages: List<ChatMessageDto> = emptyList(),
    val temperature: Double = 0.7,
    val max_tokens: Int = 512,
    val stream: Boolean = false,
)

@Serializable
data class ChatChoiceMessage(val role: String = "assistant", val content: String = "")

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatChoiceMessage = ChatChoiceMessage(),
    val finish_reason: String = "stop",
)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0,
)

@Serializable
data class ChatResponse(
    val id: String = "",
    val `object`: String = "chat.completion",
    val created: Long = 0,
    val model: String = "",
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage = Usage(),
)

@Serializable
data class EmbedRequest(
    val model: String = "",
    // OpenAI: string | string[]. Принимаем JsonElement и разбираем руками.
    val input: JsonElement? = null,
)

@Serializable
data class EmbedData(
    val index: Int = 0,
    val `object`: String = "embedding",
    val embedding: List<Float> = emptyList(),
)

@Serializable
data class EmbedResponse(
    val `object`: String = "list",
    val data: List<EmbedData> = emptyList(),
    val model: String = "",
    val usage: Usage = Usage(),
)

@Serializable
data class ModelItem(
    val id: String = "",
    val `object`: String = "model",
    val owned_by: String = "local",
)

@Serializable
data class ModelsResponse(
    val `object`: String = "list",
    val data: List<ModelItem> = emptyList(),
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val llm: String = "",
    val embed: String = "",
    val ollama_ok: Boolean = false,
    val rag_docs: Int = 0,
    val rag_chunks: Int = 0,
)

// ---- RAG-документы (сверх OpenAI-минимума, свой неймспейс /v1/docs) ----

@Serializable
data class DocsRequest(
    // "text" — прямой текст в input (+ title), "wiki" — название/URL статьи в input
    val type: String = "text",
    val title: String = "",
    val input: String = "",
)

@Serializable
data class DocsResponse(val id: String = "", val title: String = "", val chunks: Int = 0)

@Serializable
data class DocListItem(
    val id: String = "",
    val title: String = "",
    val chunks: Int = 0,
    val chars: Long = 0,
    val active: Boolean = true,
)

// ---- Ошибки в OpenAI-обёртке ----

@Serializable
data class ErrorBody(val code: String = "", val message: String = "")

@Serializable
data class ErrorEnvelope(val error: ErrorBody = ErrorBody())
