package app.ui

import agent.Agent
import agent.AgentError
import agent.AgentResult
import agent.domain.Branch
import agent.domain.LlmModel
import agent.domain.StrategyType
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
    // Task 4: сжатие контекста. N задаёт пользователь, минимум 1.
    val compressionEnabled: Boolean = true,
    val keepLastN: Int = 10,
    val summaryText: String = "",
    // Task 5: стратегии контекста + facts + ветки.
    val strategy: StrategyType = StrategyType.SLIDING,
    val facts: Map<String, String> = emptyMap(),
    val branches: List<Branch> = emptyList(),
    val activeBranchId: String? = null,
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
        val c = agent.compressionSnapshot()
        val t5 = agent.strategySnapshot()
        _state.update {
            it.copy(
                messages = restored,
                tokens = TokenStatsUi(s.sessionTokens, s.sessionCostUsd, s.contextTokens, model.contextLimit),
                selectedModelId = model.id,
                compressionEnabled = c.enabled,
                keepLastN = c.keepLastN.coerceAtLeast(1),
                summaryText = c.summaryText,
                strategy = t5.strategy,
                facts = t5.facts,
                branches = t5.branches,
                activeBranchId = t5.activeBranchId,
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

    fun setCompressionEnabled(enabled: Boolean) {
        if (_state.value.busy) return
        agent.setCompressionEnabled(enabled)
        refreshAll()
    }

    // --- Task 5 ---

    fun selectStrategy(strategy: StrategyType) {
        if (_state.value.busy) return
        agent.setStrategy(strategy)
        refreshAll()
        _state.update { it.copy(status = null) }
    }

    fun createBranch(name: String) {
        if (_state.value.busy) return
        // Пустое имя — автогенерация уникального имени на стороне Agent.
        agent.createBranch(name)
        refreshAll()
        _state.update { it.copy(status = null) }
    }

    fun deleteBranch(branchId: String) {
        if (_state.value.busy) return
        agent.deleteBranch(branchId)
        refreshAll()
        _state.update { it.copy(status = null) }
    }

    fun switchBranch(branchId: String?) {
        if (_state.value.busy) return
        agent.switchBranch(branchId)
        refreshAll()
        _state.update { it.copy(status = null) }
    }

    fun removeFact(key: String) {
        if (_state.value.busy) return
        agent.removeFact(key)
        refreshAll()
    }

    // Поле N: только цифры, минимум 1, пустой ввод игнорируем (поле отскакивает назад).
    // N задаётся на пустой чат: при непустой истории смена окна порвала бы инвариант
    // "(N-1) живых + summary", поэтому игнорируем.
    fun setKeepLastNText(raw: String) {
        if (_state.value.busy) return
        if (_state.value.messages.isNotEmpty()) return
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return
        val n = digits.toIntOrNull()?.coerceIn(1, 500) ?: return
        agent.setKeepLastN(n)
        refreshAll()
    }
}
