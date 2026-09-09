package app.ui

import agent.Agent
import agent.AgentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiMessage(val role: String, val text: String)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val status: String? = null,
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
        val restored = agent.historySnapshot().map { (role, text) -> UiMessage(role, text) }
        _state.update { it.copy(messages = restored) }
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
                    is AgentResult.Success ->
                        _state.update { it.copy(messages = it.messages + UiMessage("assistant", r.text)) }
                    is AgentResult.Failure ->
                        _state.update { it.copy(status = r.error.userMessage) }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun clearHistory() {
        agent.clearHistory()
        _state.update { it.copy(messages = emptyList(), status = null) }
    }
}
