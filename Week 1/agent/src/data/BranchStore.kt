package agent.data

import agent.domain.Branch
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Task 5: реестр веток — JSON рядом с chat.db, по образцу ModelStore.
// Сами сообщения веток лежат в SQLite под conversationId branch:<id>.
object BranchStore {
    const val FILE_NAME: String = "branches.json"

    private val json: Json = Json { ignoreUnknownKeys = true }

    fun fileFor(dir: File?): File = File(dir, FILE_NAME)

    fun load(dir: File?): List<Branch> {
        return try {
            val f = fileFor(dir)
            if (!f.isFile) return emptyList()
            json.decodeFromString(ListSerializer(Branch.serializer()), f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(dir: File?, branches: List<Branch>) {
        try {
            val f = fileFor(dir)
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(ListSerializer(Branch.serializer()), branches))
        } catch (_: Exception) {
        }
    }
}
