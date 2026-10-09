package task3.domain

/** Выбор LLM: одна локальная .litertlm из каталога или облачный DeepSeek. */
sealed interface LlmChoice {
    data class Local(val id: String) : LlmChoice
    data object DeepSeek : LlmChoice

    companion object {
        fun fromStored(value: String?): LlmChoice? = when {
            value.isNullOrBlank() -> null
            value == "deepseek" -> DeepSeek
            else -> Local(value)
        }

        fun LlmChoice?.toStored(): String = when (this) {
            null -> ""
            DeepSeek -> "deepseek"
            is Local -> id
        }
    }
}

/** Документ базы знаний для UI. */
data class DocInfo(
    val source: String,
    val title: String,
    val chunks: Int,
    val chars: Long,
    val active: Boolean = true,
    val indexing: Boolean = false,
    val error: String? = null,
)

/** Найденный чанк + косинусная близость. */
data class ScoredChunk(
    val title: String,
    val section: String,
    val source: String,
    val text: String,
    val score: Float,
)
