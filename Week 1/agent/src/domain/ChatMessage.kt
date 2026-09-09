package agent.domain

import kotlinx.serialization.Serializable

// Domain-модель. Единственный источник типов сообщений для всех слоёв (DIP).
// Токены — только по факту ответа API (Task 3):
// - у assistant: tokens = completion_tokens этого ответа, cost — по выходному тарифу;
// - у user: tokens дописываются ПОСЛЕ ответа ассистента как дельта prompt_tokens, cost — по входному тарифу.
//   До ответа токены user = 0 и в UI не показываются. Старые сообщения без замера хранят 0.
// - promptTokens/completionTokens/totalTokens — сырой снапшот usage последнего ответа (нужен для
//   наполнения контекстного окна), хранится только в assistant-сообщениях.
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val tokens: Int = 0,
    val costUsd: Double = 0.0,
)
