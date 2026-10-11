package desktop.ui

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Локальные чаты десктоп-клиента Task 5.
// Сервер stateless (история шлётся в каждом запросе), поэтому переписка
// хранится здесь — как в Week 4 (chats.json), но без RAG-метаданных:
// сервер сам ищет контекст и прикладывает «Источники:».
@Serializable
data class LocalChat(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class LocalMessage(
    val role: String, // user | assistant
    val content: String,
    val id: String = newChatId(),
    // Служебная строка: "[in=.. out=.. total=..]" или "HTTP 429 [rate_limited]".
    val meta: String = "",
)

@Serializable
data class ChatState(
    val chats: List<LocalChat> = emptyList(),
    val messages: Map<String, List<LocalMessage>> = emptyMap(),
)

fun newChatId(): String = java.util.UUID.randomUUID().toString().take(8)

class ChatStore(appDir: File = task5AppDir()) {
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
                if (file.isFile) json.decodeFromString(ChatState.serializer(), file.readText()) else ChatState()
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

    fun upsertChat(chat: LocalChat) {
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

    fun appendMessage(chatId: String, msg: LocalMessage, cap: Int = 200) {
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

// Настройки подключения к своему серверу (только localhost по умолчанию).
// Хранятся в ~/.ai-advent-task5/settings.json, env-оверрайды — как в CLI:
// TASK5_BASE_URL / TASK5_API_KEY.
@Serializable
data class DesktopSettings(
    val baseUrl: String = "http://localhost:8080",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 512,
    val system: String = "",
)

class DesktopSettingsStore(appDir: File = task5AppDir()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "settings.json")

    fun load(): DesktopSettings {
        val disk = try {
            if (file.isFile) json.decodeFromString(DesktopSettings.serializer(), file.readText()) else DesktopSettings()
        } catch (_: Exception) {
            DesktopSettings()
        }
        // Env поверх файла — как в CLI (common(): --base-url / TASK5_BASE_URL / дефолт).
        val envBase = System.getenv("TASK5_BASE_URL")?.trim()?.takeIf { it.isNotBlank() }
        val envKey = System.getenv("TASK5_API_KEY")
        return disk.copy(
            baseUrl = envBase ?: disk.baseUrl.trim().trimEnd('/').ifBlank { "http://localhost:8080" },
            apiKey = envKey ?: disk.apiKey,
        )
    }

    fun save(s: DesktopSettings) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(DesktopSettings.serializer(), s))
        } catch (_: Exception) {
        }
    }
}

fun task5AppDir(): File = File(System.getProperty("user.home"), ".ai-advent-task5")
