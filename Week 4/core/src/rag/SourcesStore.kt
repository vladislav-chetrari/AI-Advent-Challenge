package core.rag

import core.llm.ApiKeyProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Запоминает выбранные пользователем источники корпуса между запусками:
// вкладка Index восстанавливает список файлов/папок.
@Serializable
private data class SourcesState(val roots: List<String> = emptyList(), val inactive: Set<String> = emptySet())

class SourcesStore(appDir: File = ApiKeyProvider.appDir()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "sources.json")

    fun load(): List<String> = loadState().roots

    fun loadState(): Loaded {
        return try {
            if (!file.isFile) return Loaded(emptyList(), emptySet())
            val s = json.decodeFromString<SourcesState>(file.readText())
            Loaded(s.roots.filter { it.isNotBlank() }, s.inactive)
        } catch (_: Exception) {
            Loaded(emptyList(), emptySet())
        }
    }

    data class Loaded(val roots: List<String>, val inactive: Set<String>)

    fun save(roots: List<String>, inactive: Set<String> = loadState().inactive) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SourcesState.serializer(), SourcesState(roots, inactive)))
        } catch (_: Exception) {
        }
    }

    fun setInactive(source: String, off: Boolean) {
        val cur = loadState()
        val next = if (off) cur.inactive + source else cur.inactive - source
        save(cur.roots, next)
    }
}
