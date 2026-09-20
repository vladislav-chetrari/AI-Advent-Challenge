package core.domain

import kotlinx.serialization.Serializable

// Scope памяти. Task всегда внутри Project.
enum class Scope(val id: String) {
    GENERAL("general"),
    PROJECT("project"),
    TASK("task"),
}

@Serializable
data class Project(val id: String, val name: String)

@Serializable
data class Task(val id: String, val projectId: String, val name: String)

@Serializable
data class Chat(
    val id: String,
    val name: String,
    val scope: Scope,
    // parentId: projectId для PROJECT, taskId для TASK, null для GENERAL
    val parentId: String? = null,
)

@Serializable
data class MemoryDoc(
    val id: String,
    val title: String,
    val scope: Scope,
    val parentId: String? = null,
    val active: Boolean = true,
)

@Serializable
data class ChatMessage(
    val role: String, // user | assistant
    val content: String,
)

// Профиль пользователя (День 12). null activeProfileId = Аноним (по умолчанию),
// в промпт ничего не инжектится.
@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val style: String = "",
    val format: String = "",
    val constraints: String = "",
)

@Serializable
data class AppState(
    val projects: List<Project> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val chats: List<Chat> = emptyList(),
    // chatId -> сообщения (без system, только живые)
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val memoryDocs: List<MemoryDoc> = emptyList(),
    val profiles: List<UserProfile> = emptyList(),
    // null = Аноним: профиль не инжектится в system prompt
    val activeProfileId: String? = null,
    // Task 3: FSM состояние задач
    val taskStates: Map<String, TaskState> = emptyMap(),
)

fun newId(): String = java.util.UUID.randomUUID().toString().take(8)
