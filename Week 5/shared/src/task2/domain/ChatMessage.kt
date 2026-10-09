package task2.domain

import kotlinx.serialization.Serializable

/** Одна реплика в чате. SSOT хранится в ChatRepository.messages. */
@Serializable
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
) {
    @Serializable
    enum class Role { USER, ASSISTANT, SYSTEM }
}
