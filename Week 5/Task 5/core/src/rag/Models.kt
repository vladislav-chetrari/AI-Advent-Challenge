package core.rag

// Общие модели RAG (порт task3.domain.Models без UI-зависимостей).
data class ChatMsg(val role: String, val text: String)

data class ScoredChunk(
    val title: String,
    val section: String,
    val source: String,
    val text: String,
    val score: Float,
)

data class DocInfo(
    val source: String,
    val title: String,
    val chunks: Int,
    val chars: Long,
    val active: Boolean,
)

data class StoredChunk(
    val id: String,
    val source: String,
    val title: String,
    val section: String,
    val ord: Int,
    val text: String,
    val dim: Int,
    val embedding: FloatArray,
)

data class StoredDoc(
    val source: String,
    val title: String,
    val active: Boolean,
)
