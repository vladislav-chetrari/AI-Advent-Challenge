package task2.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Один запомненный факт о собеседнике (долговременная память). */
@Serializable
data class ConversationFact(
    val text: String,
    val createdAt: Long,
)

/**
 * Хранилище фактов. SSOT фактов — facts.
 * Реализация персистит в JSON, переживает перезапуск приложения.
 */
interface MemoryStore {
    val facts: StateFlow<List<ConversationFact>>
    suspend fun load()
    suspend fun addFacts(texts: List<String>)
    suspend fun clear()
}
