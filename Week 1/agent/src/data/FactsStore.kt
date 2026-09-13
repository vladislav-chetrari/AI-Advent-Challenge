package agent.data

import java.io.File
import kotlinx.serialization.json.Json

// Task 5: facts per conversation/branch — JSON-файл рядом с chat.db.
// main/default -> facts.json, ветка branch:X -> facts_branch_X.json.
object FactsStore {
    const val FILE_NAME: String = "facts.json"

    private val json: Json = Json { ignoreUnknownKeys = true }

    fun fileFor(dir: File?, conversationId: String): File {
        val safe = conversationId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(60).ifBlank { "default" }
        val name = if (safe == "default") FILE_NAME else "facts_$safe.json"
        return File(dir, name)
    }

    fun load(dir: File?, conversationId: String): Map<String, String> {
        return try {
            val f = fileFor(dir, conversationId)
            if (!f.isFile) return emptyMap()
            val el = json.parseToJsonElement(f.readText())
            (el as? kotlinx.serialization.json.JsonObject)
                ?.entries?.associate { (k, v) ->
                    k to when (v) {
                        is kotlinx.serialization.json.JsonPrimitive ->
                            if (v.isString) v.content else v.toString()
                        else -> v.toString()
                    }
                } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(dir: File?, conversationId: String, facts: Map<String, String>) {
        try {
            val f = fileFor(dir, conversationId)
            f.parentFile?.mkdirs()
            val obj = kotlinx.serialization.json.buildJsonObject {
                facts.toSortedMap().forEach { (k, v) ->
                    put(k.take(80), kotlinx.serialization.json.JsonPrimitive(v.take(500)))
                }
            }
            f.writeText(obj.toString())
        } catch (_: Exception) {
        }
    }
}
