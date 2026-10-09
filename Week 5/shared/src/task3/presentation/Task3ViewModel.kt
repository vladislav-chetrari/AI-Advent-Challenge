package task3.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import task2.data.ModelCatalog
import task2.domain.AiModel
import task2.domain.ChatMessage
import task2.domain.EngineStatus
import task3.data.EmbedCatalog
import task3.data.EmbedModel
import task3.data.EmbedState
import task3.data.DownloadProgress
import task3.data.RagRepository
import task3.domain.DocInfo
import task3.domain.LlmChoice

enum class Tab { CHAT, DOCS, SETTINGS }

data class Task3UiState(
    val tab: Tab = Tab.CHAT,
    // чат
    val messages: List<ChatMessage> = emptyList(),
    val llmStatus: EngineStatus = EngineStatus.Idle,
    val input: String = "",
    val llmChoice: LlmChoice? = null,
    val llmLabel: String = "Модель не выбрана",
    val canChat: Boolean = false,
    val chatHint: String = "Выбери модель в Настройках",
    val showClearDialog: Boolean = false,
    // документы
    val docs: List<DocInfo> = emptyList(),
    val indexProgress: Map<String, Float?> = emptyMap(),
    val canAdd: Boolean = false,
    val docsHint: String = "Сначала выбери embedding-модель в Настройках",
    val showWikiDialog: Boolean = false,
    val wikiInput: String = "",
    val viewingSource: String? = null,
    val viewingText: String = "",
    // настройки
    val embedId: String? = null,
    val embedStatus: EmbedState = EmbedState.Unset,
    val downloadedLlm: Set<String> = emptySet(),
    val downloadedEmbed: Set<String> = emptySet(),
    val downloading: Map<String, DownloadProgress> = emptyMap(),
    val hasDeepSeekKey: Boolean = false,
    val showKeyDialog: Boolean = false,
    val keyInput: String = "",
    // экран моделей
    val showLlmScreen: Boolean = false,
    val showEmbedScreen: Boolean = false,
)

sealed interface Task3Intent {
    data class TabSelected(val tab: Tab) : Task3Intent
    data class InputChanged(val text: String) : Task3Intent
    data object Send : Task3Intent
    data object Cancel : Task3Intent
    data object ClearRequested : Task3Intent
    data object ClearDismissed : Task3Intent
    data object ClearConfirmed : Task3Intent
    data object Retry : Task3Intent
    // документы
    data object WikiOpen : Task3Intent
    data object WikiDismissed : Task3Intent
    data class WikiInputChanged(val text: String) : Task3Intent
    data object WikiConfirmed : Task3Intent
    data class DocActiveToggled(val source: String, val active: Boolean) : Task3Intent
    data class DocDelete(val source: String) : Task3Intent
    data class DocView(val source: String) : Task3Intent
    data object DocViewDismissed : Task3Intent
    // настройки
    data object LlmScreenOpen : Task3Intent
    data object LlmScreenClosed : Task3Intent
    data object EmbedScreenOpen : Task3Intent
    data object EmbedScreenClosed : Task3Intent
    data class LlmSelected(val choice: LlmChoice) : Task3Intent
    data class LlmDownload(val model: AiModel) : Task3Intent
    data class LlmDelete(val model: AiModel) : Task3Intent
    data class EmbedSelected(val id: String) : Task3Intent
    data class EmbedDownload(val model: EmbedModel) : Task3Intent
    data class EmbedDelete(val model: EmbedModel) : Task3Intent
    data object KeyDialogOpen : Task3Intent
    data object KeyDialogDismissed : Task3Intent
    data class KeyInputChanged(val text: String) : Task3Intent
    data object KeySaved : Task3Intent
    data object KeyDeleted : Task3Intent
}

class Task3ViewModel(
    private val repo: RagRepository,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(Task3UiState())
    val ui: StateFlow<Task3UiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch { repo.init() }
        scope.launch {
            combine(
                repo.messages, repo.llmStatus, repo.llmChoice,
                repo.docs, repo.indexProgress, repo.embedId, repo.embedStatus,
                repo.downloadedLlm, repo.downloadedEmbed, repo.downloading, repo.deepSeekKey,
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val messages = args[0] as List<ChatMessage>
                val llmStatus = args[1] as EngineStatus
                val choice = args[2] as LlmChoice?
                val docs = args[3] as List<DocInfo>
                val progress = args[4] as Map<String, Float?>
                val embedId = args[5] as String?
                val embedStatus = args[6] as EmbedState
                val dlLlm = args[7] as Set<String>
                val dlEmbed = args[8] as Set<String>
                val downloading = args[9] as Map<String, DownloadProgress>
                val key = args[10] as String
                collect(
                    messages, llmStatus, choice, docs, progress,
                    embedId, embedStatus, dlLlm, dlEmbed, downloading, key,
                )
            }.collect { partial ->
                val cur = _ui.value
                _ui.value = cur.copy(
                    messages = partial.messages,
                    llmStatus = partial.llmStatus,
                    llmChoice = partial.choice,
                    llmLabel = partial.label,
                    canChat = partial.canChat,
                    chatHint = partial.hint,
                    docs = partial.docs,
                    indexProgress = partial.progress,
                    canAdd = partial.canAdd,
                    docsHint = partial.docsHint,
                    embedId = partial.embedId,
                    embedStatus = partial.embedStatus,
                    downloadedLlm = partial.dlLlm,
                    downloadedEmbed = partial.dlEmbed,
                    downloading = partial.downloading,
                    hasDeepSeekKey = partial.key.isNotBlank(),
                )
            }
        }
    }

    private data class Partial(
        val messages: List<ChatMessage>,
        val llmStatus: EngineStatus,
        val choice: LlmChoice?,
        val label: String,
        val canChat: Boolean,
        val hint: String,
        val docs: List<DocInfo>,
        val progress: Map<String, Float?>,
        val canAdd: Boolean,
        val docsHint: String,
        val embedId: String?,
        val embedStatus: EmbedState,
        val dlLlm: Set<String>,
        val dlEmbed: Set<String>,
        val downloading: Map<String, DownloadProgress>,
        val key: String,
    )

    private fun collect(
        messages: List<ChatMessage>,
        llmStatus: EngineStatus,
        choice: LlmChoice?,
        docs: List<DocInfo>,
        progress: Map<String, Float?>,
        embedId: String?,
        embedStatus: EmbedState,
        dlLlm: Set<String>,
        dlEmbed: Set<String>,
        downloading: Map<String, DownloadProgress>,
        key: String,
    ): Partial {
        val (label, canChat, hint) = when (choice) {
            null -> Triple("Модель не выбрана", false, "Выбери модель в Настройках")
            is LlmChoice.DeepSeek -> if (key.isBlank()) {
                Triple("DeepSeek (нет ключа)", false, "Введи API-ключ в Настройках")
            } else {
                Triple("DeepSeek (облако)", true, "")
            }
            is LlmChoice.Local -> {
                val m = ModelCatalog.resolve(choice.id)
                if (choice.id !in dlLlm) {
                    Triple("${m.label} (не скачана)", false, "Скачай модель в Настройках")
                } else {
                    Triple(m.label, llmStatus !is EngineStatus.Error || true, "")
                }
            }
        }
        val emb = EmbedCatalog.resolve(embedId)
        val canAdd = emb != null && embedId in dlEmbed
        val docsHint = when {
            emb == null -> "Сначала выбери embedding-модель в Настройках"
            embedId !in dlEmbed -> "Скачай embedding-модель в Настройках"
            else -> ""
        }
        return Partial(
            messages, llmStatus, choice, label, canChat, hint,
            docs, progress, canAdd, docsHint, embedId, embedStatus,
            dlLlm, dlEmbed, downloading, key,
        )
    }

    fun sendIntent(intent: Task3Intent) {
        when (intent) {
            is Task3Intent.TabSelected -> _ui.value = _ui.value.copy(tab = intent.tab)
            is Task3Intent.InputChanged -> _ui.value = _ui.value.copy(input = intent.text)
            Task3Intent.Send -> {
                val text = _ui.value.input
                if (text.isBlank() || !_ui.value.canChat) return
                _ui.value = _ui.value.copy(input = "")
                job?.cancel()
                job = scope.launch { repo.send(text) }
            }
            Task3Intent.Cancel -> {
                job?.cancel()
                repo.cancel()
            }
            Task3Intent.ClearRequested -> _ui.value = _ui.value.copy(showClearDialog = true)
            Task3Intent.ClearDismissed -> _ui.value = _ui.value.copy(showClearDialog = false)
            Task3Intent.ClearConfirmed -> {
                _ui.value = _ui.value.copy(showClearDialog = false)
                scope.launch { repo.clearChat() }
            }
            Task3Intent.Retry -> scope.launch {
                val c = _ui.value.llmChoice
                if (c is LlmChoice.Local) runCatching { repo.ensureLlmLoaded(ModelCatalog.resolve(c.id)) }
            }
            // документы
            Task3Intent.WikiOpen -> {
                if (!_ui.value.canAdd) return
                _ui.value = _ui.value.copy(showWikiDialog = true, wikiInput = "")
            }
            Task3Intent.WikiDismissed -> _ui.value = _ui.value.copy(showWikiDialog = false)
            is Task3Intent.WikiInputChanged -> _ui.value = _ui.value.copy(wikiInput = intent.text)
            Task3Intent.WikiConfirmed -> {
                val input = _ui.value.wikiInput.trim()
                if (input.isEmpty() || !_ui.value.canAdd) return
                _ui.value = _ui.value.copy(showWikiDialog = false)
                scope.launch { repo.addWikiDoc(input) }
            }
            is Task3Intent.DocActiveToggled -> scope.launch { repo.setDocActive(intent.source, intent.active) }
            is Task3Intent.DocDelete -> scope.launch { repo.deleteDoc(intent.source) }
            is Task3Intent.DocView -> scope.launch {
                val text = repo.docPreview(intent.source)
                _ui.value = _ui.value.copy(viewingSource = intent.source, viewingText = text)
            }
            Task3Intent.DocViewDismissed -> _ui.value = _ui.value.copy(viewingSource = null, viewingText = "")
            // настройки
            Task3Intent.LlmScreenOpen -> _ui.value = _ui.value.copy(showLlmScreen = true)
            Task3Intent.LlmScreenClosed -> _ui.value = _ui.value.copy(showLlmScreen = false)
            Task3Intent.EmbedScreenOpen -> _ui.value = _ui.value.copy(showEmbedScreen = true)
            Task3Intent.EmbedScreenClosed -> _ui.value = _ui.value.copy(showEmbedScreen = false)
            is Task3Intent.LlmSelected -> scope.launch { repo.selectLlm(intent.choice) }
            is Task3Intent.LlmDownload -> {
                // Синхронный гард: scope.launch стартует позже, а второй тап
                // до первого прогресса иначе запустит параллельную докачку.
                if (intent.model.id in _ui.value.downloading) return
                _ui.value = _ui.value.copy(
                    downloading = _ui.value.downloading + (intent.model.id to DownloadProgress(0, null)),
                )
                scope.launch { repo.downloadLlm(intent.model) }
            }
            is Task3Intent.LlmDelete -> scope.launch { repo.deleteLlm(intent.model) }
            is Task3Intent.EmbedSelected -> scope.launch { repo.selectEmbed(intent.id) }
            is Task3Intent.EmbedDownload -> {
                if (intent.model.id in _ui.value.downloading) return
                _ui.value = _ui.value.copy(
                    downloading = _ui.value.downloading + (intent.model.id to DownloadProgress(0, null)),
                )
                scope.launch { repo.downloadEmbed(intent.model) }
            }
            is Task3Intent.EmbedDelete -> scope.launch { repo.deleteEmbed(intent.model) }
            Task3Intent.KeyDialogOpen -> _ui.value = _ui.value.copy(showKeyDialog = true, keyInput = "")
            Task3Intent.KeyDialogDismissed -> _ui.value = _ui.value.copy(showKeyDialog = false)
            is Task3Intent.KeyInputChanged -> _ui.value = _ui.value.copy(keyInput = intent.text)
            Task3Intent.KeySaved -> {
                val k = _ui.value.keyInput.trim()
                if (k.isEmpty()) return
                _ui.value = _ui.value.copy(showKeyDialog = false)
                scope.launch { repo.saveDeepSeekKey(k) }
            }
            Task3Intent.KeyDeleted -> scope.launch { repo.saveDeepSeekKey("") }
        }
    }
}
