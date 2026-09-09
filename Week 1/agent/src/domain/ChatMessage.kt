package agent.domain

import kotlinx.serialization.Serializable

// Domain-модель. Единственный источник типов сообщений для всех слоёв (DIP).
@Serializable
data class ChatMessage(val role: String, val content: String)
