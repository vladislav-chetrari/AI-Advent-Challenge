package core.rag

import core.llm.ApiKeyProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Запоминает выключенные из RAG документы между запусками.
// Единственный источник документов — статьи Википедии.
@Serializable
private data class SourcesState(val inactive: Set<String> = emptySet())

class SourcesStore(appDir: File = ApiKeyProvider.appDir()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "sources.json")

    fun loadState(): Loaded {
        return try {
            if (!file.isFile) return Loaded(emptySet())
            val s = json.decodeFromString<SourcesState>(file.readText())
            Loaded(s.inactive)
        } catch (_: Exception) {
            Loaded(emptySet())
        }
    }

    data class Loaded(val inactive: Set<String>)

    fun save(inactive: Set<String> = loadState().inactive) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(SourcesState.serializer(), SourcesState(inactive)))
        } catch (_: Exception) {
        }
    }

    fun setInactive(source: String, off: Boolean) {
        val cur = loadState()
        val next = if (off) cur.inactive + source else cur.inactive - source
        save(next)
    }
}
