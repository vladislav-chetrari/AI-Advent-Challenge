package app.ui

import agent.Agent
import agent.AgentError
import agent.AgentResult
import agent.domain.LlmModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiMessage(
    val role: String,
    val text: String,
    val tokens: Int = 0,
    val costUsd: Double = 0.0,
)

data class TokenStatsUi(
    val sessionTokens: Long = 0,
    val sessionCostUsd: Double = 0.0,
    val contextTokens: Int = 0,
    val contextLimit: Int = 0,
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    // Ошибка хранится типом, текст резолвится в Screen через ресурсы (локализация + plurals).
    val status: AgentError? = null,
    val tokens: TokenStatsUi = TokenStatsUi(),
    val models: List<LlmModel> = LlmModel.ALL,
    val selectedModelId: String = LlmModel.DEEPSEEK.id,
)

// MVVM: ViewModel владеет состоянием и юзкейсом ask(), Screen только рисует.
// Agent (с SQLite-историей) инжектится снаружи — ViewModel не знает про БД/HTTP.
class ChatViewModel(
    private val agent: Agent,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        // Восстановление контекста после рестарта: история уже загружена Agent из SQLite.
        refreshAll()
    }

    private fun refreshAll() {
        val restored = agent.historyWithTokens().map {
            UiMessage(it.role, it.content, it.tokens, it.costUsd)
        }
        val s = agent.statsSnapshot()
        val model = agent.currentModel
        _state.update {
            it.copy(
                messages = restored,
                tokens = TokenStatsUi(s.sessionTokens, s.sessionCostUsd, s.contextTokens, model.contextLimit),
                selectedModelId = model.id,
            )
        }
    }

    fun selectModel(id: String) {
        if (_state.value.busy) return
        agent.switchModel(LlmModel.byId(id))
        refreshAll()
        _state.update { it.copy(status = null) }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun send() {
        val q = _state.value.input.trim()
        if (q.isEmpty() || _state.value.busy) return
        _state.update { it.copy(input = "", status = null, busy = true, messages = it.messages + UiMessage("user", q)) }
        scope.launch {
            try {
                when (val r = agent.ask(q)) {
                    is AgentResult.Success -> refreshAll()
                    is AgentResult.Failure ->
                        _state.update {
                            // Откат оптимистичного user-бабла: история в Agent уже откачена.
                            it.copy(
                                messages = it.messages.dropLast(1),
                                status = r.error,
                            )
                        }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun clearHistory() {
        agent.clearHistory()
        refreshAll()
        _state.update { it.copy(status = null) }
    }
}
