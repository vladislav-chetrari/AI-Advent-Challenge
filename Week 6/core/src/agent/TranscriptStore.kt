package core.agent

import core.llm.ChatMsg
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ===== Task 4: транскрипт прогона — это и есть состояние агента =====
//
// Продолжение «с того же места» невозможно, если помнить только факты: модель
// начинает думать заново и повторяет работу. Поэтому на диск пишется ровно тот
// список сообщений, который уходит провайдеру (система, цель, ответы ассистента
// с вызовами тулов, результаты тулов). Продолжение подставляет этот список
// обратно и цикл идёт дальше — как opencode, где сессия хранит сообщения и
// `prompt({resume})` продолжает их, а не переписывает.
//
// Файл на прогон: ~/.ai-advent-week6/agent_runs/<runId>.json.

@Serializable
data class SavedTranscript(
    val runId: String,
    val goal: String = "",
    val messages: List<ChatMsg> = emptyList(),
    val updatedAt: Long = 0,
)

class TranscriptStore(dir: File = core.llm.ApiKeyProvider.appDir()) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val root = File(dir, "agent_runs")

    private fun file(runId: String) = File(root, "$runId.json")

    // Пишем после каждого шага: падение процесса должно стоить максимум
    // одного незавершённого вызова тула, а не всего прогона.
    fun save(runId: String, goal: String, messages: List<ChatMsg>) {
        try {
            root.mkdirs()
            val payload = SavedTranscript(
                runId = runId,
                goal = goal,
                messages = completeTail(messages),
                updatedAt = System.currentTimeMillis(),
            )
            file(runId).writeText(json.encodeToString(SavedTranscript.serializer(), payload))
        } catch (_: Exception) {
        }
    }

    fun load(runId: String): SavedTranscript? = try {
        val f = file(runId)
        if (f.isFile) json.decodeFromString<SavedTranscript>(f.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun delete(runId: String) {
        try {
            file(runId).delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        // Хвост транскрипта должен заканчиваться парой «вызов тула → результат».
        // Если процесс умер между assistant(tool_calls) и ответом тула, провайдер
        // такой следующий запрос отвергнет (на DeepSeek/Ollama он падает целиком).
        // Поэтому недописанный хвост отбрасывается — агент просто позовёт тул снова.
        fun completeTail(messages: List<ChatMsg>): List<ChatMsg> {
            val openCall = messages.indexOfLast { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
            if (openCall < 0) return messages
            val answered = messages.drop(openCall + 1).count { it.role == "tool" }
            val expected = messages[openCall].toolCalls?.size ?: 0
            return if (answered >= expected) messages else messages.take(openCall)
        }
    }
}