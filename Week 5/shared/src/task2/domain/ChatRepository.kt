package task2.domain

import kotlinx.coroutines.flow.StateFlow

/** Статус движка для UI. */
sealed interface EngineStatus {
    data object Idle : EngineStatus
    data class Downloading(val progress: Int) : EngineStatus
    data object LoadingModel : EngineStatus
    data object Ready : EngineStatus
    data object Generating : EngineStatus
    data class Error(val message: String) : EngineStatus
}

/** SSOT-репозиторий чата. Единственный источник правды — messages. */
interface ChatRepository {
    val messages: StateFlow<List<ChatMessage>>
    val status: StateFlow<EngineStatus>

    suspend fun ensureModel(model: AiModel, modelsDir: String)
    suspend fun send(model: AiModel, modelsDir: String, prompt: String)
    fun cancel()
    /** Suspend — чистка идёт и в БД. */
    suspend fun clear()
}
