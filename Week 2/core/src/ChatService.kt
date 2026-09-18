package core

import core.data.Store
import core.domain.AppState
import core.domain.Chat
import core.domain.ChatMessage
import core.domain.DISTILL_SYSTEM
import core.domain.MemoryDoc
import core.domain.Project
import core.domain.PromptBuilder
import core.domain.Scope
import core.domain.Task
import core.domain.newId
import core.domain.parseDistillJson
import core.network.ApiKeyProvider
import core.network.LlmClient
import core.network.LlmResult

sealed interface AskResult {
    data class Success(val text: String, val promptTokens: Int = 0) : AskResult
    data class Failure(val message: String) : AskResult
}

// Фасад: дерево + память + LLM. История — в Store.state, контент памяти — в .md.
class ChatService(
    val store: Store = Store(Store.defaultDir()),
    private var llm: LlmClient = LlmClient(),
) {
    var lastPromptTokens: Int = 0
        private set
    var lastSystemPrompt: String = PromptBuilder.BASE
        private set

    fun state(): AppState = store.state

    // --- создание сущностей дерева ---

    fun createProject(name: String): Project {
        val p = Project(newId(), name.trim().take(60).ifBlank { "project" })
        store.update { it.copy(projects = it.projects + p) }
        // Стартовые доки проекта чтобы было куда сохранять
        createMemoryDoc("tech-stack", Scope.PROJECT, p.id)
        createMemoryDoc("project-goal", Scope.PROJECT, p.id)
        return p
    }

    fun createTask(projectId: String, name: String): Task {
        val t = Task(newId(), projectId, name.trim().take(60).ifBlank { "task" })
        store.update { it.copy(tasks = it.tasks + t) }
        createMemoryDoc("task-state", Scope.TASK, t.id)
        return t
    }

    fun createChat(name: String, scope: Scope, parentId: String?): Chat {
        val c = Chat(newId(), name.trim().take(60).ifBlank { "chat" }, scope, parentId)
        store.update { it.copy(chats = it.chats + c, messages = it.messages + (c.id to emptyList())) }
        return c
    }

    fun createMemoryDoc(title: String, scope: Scope, parentId: String?): MemoryDoc {
        val clean = title.trim().take(60).ifBlank { "memory" }
        // Дедуп по title в том же scope+parent
        store.state.memoryDocs.firstOrNull {
            it.scope == scope && it.parentId == parentId && it.title == clean
        }?.let { return it }
        val d = MemoryDoc(newId(), clean, scope, parentId)
        store.update { it.copy(memoryDocs = it.memoryDocs + d) }
        store.writeDocContent(d.id, "# $clean\n")
        return d
    }

    fun toggleDocActive(docId: String) {
        store.update { s ->
            s.copy(memoryDocs = s.memoryDocs.map { if (it.id == docId) it.copy(active = !it.active) else it })
        }
    }

    fun deleteDoc(docId: String) {
        store.update { s -> s.copy(memoryDocs = s.memoryDocs.filterNot { it.id == docId }) }
        store.deleteDocContent(docId)
    }

    fun docContent(doc: MemoryDoc): String = store.readDocContent(doc)

    // --- релевантные доки с наследованием ---

    fun relevantDocs(chat: Chat): List<MemoryDoc> {
        val s = store.state
        val general = s.memoryDocs.filter { it.scope == Scope.GENERAL && it.active }
        return when (chat.scope) {
            Scope.GENERAL -> general.sortedBy { it.title }
            Scope.PROJECT -> {
                val proj = s.memoryDocs.filter {
                    it.scope == Scope.PROJECT && it.parentId == chat.parentId && it.active
                }
                (general + proj).sortedBy { it.scope.ordinal * 1000 + it.title.hashCode() % 1000 }
                    .sortedWith(compareBy({ it.scope.ordinal }, { it.title }))
            }
            Scope.TASK -> {
                val task = s.memoryDocs.filter {
                    it.scope == Scope.TASK && it.parentId == chat.parentId && it.active
                }
                val taskObj = s.tasks.firstOrNull { it.id == chat.parentId }
                val proj = if (taskObj != null) s.memoryDocs.filter {
                    it.scope == Scope.PROJECT && it.parentId == taskObj.projectId && it.active
                } else emptyList()
                (general + proj + task).sortedWith(compareBy({ it.scope.ordinal }, { it.title }))
            }
        }
    }

    fun buildSystemPrompt(chat: Chat): String {
        val docs = relevantDocs(chat).map { it.title to store.readDocContent(it) }
        return PromptBuilder.buildSystemPrompt(docs)
    }

    fun clearChat(chatId: String) {
        store.update { it.copy(messages = it.messages + (chatId to emptyList())) }
    }

    // --- ask: sliding window + facts injection ---

    suspend fun ask(chat: Chat, prompt: String): AskResult {
        val clean = prompt.trim()
        if (clean.isEmpty()) return AskResult.Failure("Пустой запрос.")
        val apiKey = ApiKeyProvider.resolve()
        if (apiKey.isNullOrBlank()) return AskResult.Failure("DEEPSEEK_API_KEY не найден (env или .env).")

        val system = buildSystemPrompt(chat)
        lastSystemPrompt = system
        val live = store.state.messages[chat.id].orEmpty() + ChatMessage("user", clean)
        store.update { it.copy(messages = it.messages + (chat.id to live)) }

        val toSend = PromptBuilder.effectiveHistory(system, live)
        return when (val r = llm.complete(toSend, apiKey)) {
            is LlmResult.Ok -> {
                lastPromptTokens = r.usage.promptTokens
                val updated = store.state.messages[chat.id].orEmpty() + ChatMessage("assistant", r.text)
                store.update { it.copy(messages = it.messages + (chat.id to updated)) }
                AskResult.Success(r.text, r.usage.promptTokens)
            }
            is LlmResult.HttpError -> {
                // откат user-сообщения
                val cur = store.state.messages[chat.id].orEmpty()
                if (cur.lastOrNull()?.role == "user") {
                    store.update { it.copy(messages = it.messages + (chat.id to cur.dropLast(1))) }
                }
                AskResult.Failure(
                    if (r.code == 401) "DeepSeek отклонил ключ (401)."
                    else "DeepSeek API: HTTP ${r.code} ${r.detail.take(200)}"
                )
            }
            is LlmResult.Empty -> AskResult.Failure("Пустой ответ модели.")
            is LlmResult.NetworkError -> {
                val cur = store.state.messages[chat.id].orEmpty()
                if (cur.lastOrNull()?.role == "user") {
                    store.update { it.copy(messages = it.messages + (chat.id to cur.dropLast(1))) }
                }
                AskResult.Failure("Сеть: ${r.detail.take(200)}")
            }
        }
    }

    // --- distill + commit (ПКМ "сохранить в память") ---

    suspend fun distill(raw: String): List<String> {
        val apiKey = ApiKeyProvider.resolve() ?: return listOf(raw.trim().take(500))
        val r = llm.complete(
            listOf(
                ChatMessage("system", DISTILL_SYSTEM),
                ChatMessage("user", raw.trim().take(2000)),
            ),
            apiKey,
        )
        val ok = r as? LlmResult.Ok ?: return listOf(raw.trim().take(500))
        val parsed = parseDistillJson(ok.text)
        return parsed.ifEmpty { listOf(raw.trim().take(500)) }
    }

    fun commitFact(docId: String, fact: String) {
        store.appendFact(docId, fact)
    }
}
