package task2.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import task2.data.ModelCatalog
import task2.domain.AiModel
import task2.domain.ChatRepository
import task2.domain.EngineStatus

/** MVVM: экран зависит только от UiState + sendIntent. */
data class ChatUiState(
    val messages: List<task2.domain.ChatMessage> = emptyList(),
    val status: EngineStatus = EngineStatus.Idle,
    val input: String = "",
    val model: AiModel = ModelCatalog.DEFAULT,
    val modelsDir: String = "",
)

sealed interface ChatIntent {
    data class InputChanged(val text: String) : ChatIntent
    data object Send : ChatIntent
    data object Cancel : ChatIntent
    data object Clear : ChatIntent
    data class ModelSelected(val model: AiModel) : ChatIntent
    data object Retry : ChatIntent
}

class ChatViewModel(
    private val repo: ChatRepository,
    private val scope: CoroutineScope,
    modelsDir: String,
) {
    private val _ui = MutableStateFlow(ChatUiState(modelsDir = modelsDir))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            combine(repo.messages, repo.status) { m, s -> m to s }.collect { (m, s) ->
                _ui.value = _ui.value.copy(messages = m, status = s)
            }
        }
        // Прогрев: докачать дефолтную модель заранее (Qwen2.5-1.5B-Q8).
        scope.launch {
            runCatching { repo.ensureModel(_ui.value.model, _ui.value.modelsDir) }
        }
    }

    fun sendIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.InputChanged -> _ui.value = _ui.value.copy(input = intent.text)
            ChatIntent.Send -> {
                val text = _ui.value.input
                if (text.isBlank()) return
                _ui.value = _ui.value.copy(input = "")
                job?.cancel()
                job = scope.launch {
                    repo.send(_ui.value.model, _ui.value.modelsDir, text)
                }
            }
            ChatIntent.Cancel -> {
                job?.cancel()
                repo.cancel()
            }
            ChatIntent.Clear -> scope.launch { repo.clear() }
            is ChatIntent.ModelSelected -> {
                _ui.value = _ui.value.copy(model = intent.model)
                job?.cancel()
                job = scope.launch {
                    runCatching { repo.ensureModel(intent.model, _ui.value.modelsDir) }
                }
            }
            ChatIntent.Retry -> {
                job = scope.launch {
                    runCatching { repo.ensureModel(_ui.value.model, _ui.value.modelsDir) }
                }
            }
        }
    }
}
