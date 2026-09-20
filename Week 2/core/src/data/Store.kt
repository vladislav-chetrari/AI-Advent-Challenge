package core.data

import core.domain.AppState
import core.domain.InvariantDoc
import core.domain.MemoryDoc
import java.io.File
import kotlinx.serialization.json.Json

// Файловое хранилище MVP: state.json + memory/<docId>.md
// Краткосрочная (messages) — в state.json, рабочая/долговременная (.md) — отдельными файлами.
class Store(private val dir: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val stateFile: File get() = File(dir, "state.json")
    private val memoryDir: File get() = File(dir, "memory")
    // Task 4: инварианты лежат отдельно от памяти диалога
    private val invariantsDir: File get() = File(dir, "invariants")

    @Volatile
    var state: AppState = AppState()
        private set

    private val lock = Any()

    init {
        dir.mkdirs()
        memoryDir.mkdirs()
        invariantsDir.mkdirs()
        load()
    }

    fun load() {
        synchronized(lock) {
            state = try {
                if (stateFile.exists()) json.decodeFromString<AppState>(stateFile.readText()) else AppState()
            } catch (_: Exception) {
                AppState()
            }
        }
    }

    private fun persistLocked() {
        try {
            dir.mkdirs()
            stateFile.writeText(json.encodeToString(AppState.serializer(), state))
        } catch (_: Exception) { }
    }

    fun update(fn: (AppState) -> AppState) {
        synchronized(lock) {
            state = fn(state)
            persistLocked()
        }
    }

    // --- memory .md ---

    fun readDocContent(doc: MemoryDoc): String {
        return try {
            val f = File(memoryDir, "${doc.id}.md")
            if (f.exists()) f.readText() else ""
        } catch (_: Exception) { "" }
    }

    fun writeDocContent(docId: String, content: String) {
        try {
            memoryDir.mkdirs()
            File(memoryDir, "$docId.md").writeText(content)
        } catch (_: Exception) { }
    }

    fun appendFact(docId: String, fact: String) {
        val clean = fact.trim().take(500)
        if (clean.isEmpty()) return
        try {
            memoryDir.mkdirs()
            val f = File(memoryDir, "$docId.md")
            val prev = if (f.exists()) f.readText() else ""
            val line = "- $clean\n"
            // Дедуплика по точному совпадению строки
            if (!prev.contains(line.trim())) {
                f.writeText(if (prev.isBlank()) "# memory\n\n$line" else prev.trimEnd() + "\n$line")
            }
        } catch (_: Exception) { }
    }

    fun deleteDocContent(docId: String) {
        try { File(memoryDir, "$docId.md").delete() } catch (_: Exception) { }
    }

    // --- invariants .md (Task 4): автосейв из редактора, без дистилляции ---

    fun readInvariantContent(doc: InvariantDoc): String {
        return try {
            val f = File(invariantsDir, "${doc.id}.md")
            if (f.exists()) f.readText() else ""
        } catch (_: Exception) { "" }
    }

    fun writeInvariantContent(docId: String, content: String) {
        try {
            invariantsDir.mkdirs()
            File(invariantsDir, "$docId.md").writeText(content)
        } catch (_: Exception) { }
    }

    fun deleteInvariantContent(docId: String) {
        try { File(invariantsDir, "$docId.md").delete() } catch (_: Exception) { }
    }

    companion object {
        fun defaultDir(): File = File(System.getProperty("user.home"), ".ai-advent-week2")
    }
}
