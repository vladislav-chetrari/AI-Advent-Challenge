package core.project

import core.llm.ApiKeyProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Реестр проектов (Week 6 Task 1): имя -> локальная папка (+ git-URL для pull).
// JSON-файл как ChatStore/SourcesStore; вектора живут в SQLite, здесь только метаданные.
@Serializable
data class ProjectEntry(
    val name: String,
    val localPath: String,
    val gitUrl: String = "",
    val indexedCommit: String = "",
    val updatedAt: Long = 0,
)

@Serializable
private data class ProjectState(
    val projects: List<ProjectEntry> = emptyList(),
    val selected: String? = null,
)

class ProjectStore(appDir: File = ApiKeyProvider.appDir()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "projects.json")
    private val lock = Any()

    @Volatile
    private var state: ProjectState = ProjectState()

    init {
        file.parentFile?.mkdirs()
        load()
    }

    fun load() {
        synchronized(lock) {
            state = try {
                if (file.isFile) json.decodeFromString<ProjectState>(file.readText()) else ProjectState()
            } catch (_: Exception) {
                ProjectState()
            }
        }
    }

    private fun persistLocked() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ProjectState.serializer(), state))
        } catch (_: Exception) {
        }
    }

    fun all(): List<ProjectEntry> = synchronized(lock) {
        state.projects.sortedBy { it.name.lowercase() }
    }

    fun get(name: String): ProjectEntry? = synchronized(lock) {
        state.projects.firstOrNull { it.name == name }
    }

    fun selected(): String? = synchronized(lock) { state.selected }

    fun select(name: String?) {
        synchronized(lock) {
            val sel = name?.takeIf { n -> state.projects.any { it.name == n } }
            state = state.copy(selected = sel)
            persistLocked()
        }
    }

    fun upsert(entry: ProjectEntry) {
        synchronized(lock) {
            val rest = state.projects.filter { it.name != entry.name } + entry
            val sel = state.selected ?: entry.name
            state = state.copy(
                projects = rest.sortedBy { it.name.lowercase() },
                selected = sel.takeIf { s -> rest.any { it.name == s } } ?: entry.name,
            )
            persistLocked()
        }
    }

    fun remove(name: String) {
        synchronized(lock) {
            state = state.copy(
                projects = state.projects.filter { it.name != name },
                selected = state.selected.takeIf { it != name },
            )
            persistLocked()
        }
    }
}
