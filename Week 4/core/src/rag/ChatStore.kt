package core.rag

import core.llm.ApiKeyProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Чаты и сообщения (как дерево Week 2, но минимально):
// state.json рядом с rag.db, тексты документов НЕ дублируются (лежат в файлах).
@Serializable
data class RagChat(
    val id: String,
    val name: String,
    val ragEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class RagMessage(
    val role: String, // user | assistant
    val content: String,
    val id: String = newChatId(),
    // подписи источников RAG-ответа: "[S1] title / section"
    val sources: List<String> = emptyList(),
)

@Serializable
data class ChatState(
    val chats: List<RagChat> = emptyList(),
    val messages: Map<String, List<RagMessage>> = emptyMap(),
)

fun newChatId(): String = java.util.UUID.randomUUID().toString().take(8)

class ChatStore(appDir: File = ApiKeyProvider.appDir()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "chats.json")
    private val lock = Any()

    @Volatile
    var state: ChatState = ChatState()
        private set

    init {
        file.parentFile?.mkdirs()
        load()
    }

    fun load() {
        synchronized(lock) {
            state = try {
                if (file.isFile) json.decodeFromString<ChatState>(file.readText()) else ChatState()
            } catch (_: Exception) {
                ChatState()
            }
        }
    }

    private fun persistLocked() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ChatState.serializer(), state))
        } catch (_: Exception) {
        }
    }

    fun upsertChat(chat: RagChat) {
        synchronized(lock) {
            val chats = state.chats.filter { it.id != chat.id } + chat
            state = state.copy(chats = chats.sortedBy { it.createdAt })
            persistLocked()
        }
    }

    fun deleteChat(id: String) {
        synchronized(lock) {
            state = state.copy(
                chats = state.chats.filter { it.id != id },
                messages = state.messages - id,
            )
            persistLocked()
        }
    }

    fun appendMessage(chatId: String, msg: RagMessage, cap: Int = 200) {
        synchronized(lock) {
            val cur = state.messages[chatId].orEmpty() + msg
            state = state.copy(messages = state.messages + (chatId to cur.takeLast(cap)))
            persistLocked()
        }
    }

    fun clearMessages(chatId: String) {
        synchronized(lock) {
            state = state.copy(messages = state.messages + (chatId to emptyList()))
            persistLocked()
        }
    }
}
