package task3.data

/**
 * Каталог локальных embedding-GGUF под llama.cpp (pooling MEAN).
 * Обе модели BERT-подобные, на телефоне считаются быстро.
 */
data class EmbedModel(
    val id: String,
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
    /** Размерность вектора (проверяется после загрузки через JNI). */
    val dim: Int,
    /** Префикс nomic для документов (у minilm префиксов нет). */
    val docPrefix: String = "",
    /** Префикс для поискового запроса. */
    val queryPrefix: String = "",
)

object EmbedCatalog {
    val NOMIC_V15_Q8 = EmbedModel(
        id = "nomic-v15-q8",
        label = "Nomic v1.5 Q8 (точнее, ~150 МБ)",
        fileName = "nomic-embed-text-v1.5.Q8_0.gguf",
        downloadUrl = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q8_0.gguf",
        sizeMb = 146,
        dim = 768,
        docPrefix = "search_document: ",
        queryPrefix = "search_query: ",
    )
    val MINILM_L6_Q8 = EmbedModel(
        id = "minilm-l6-q8",
        label = "MiniLM-L6 Q8 (лёгкая, ~25 МБ)",
        fileName = "all-MiniLM-L6-v2-q8_0.gguf",
        // Конверсия prithivida битая (нет bert.context_length — llama.cpp не грузит).
        // Mungert собран штатным convert_hf_to_gguf: метаданные полные.
        downloadUrl = "https://huggingface.co/Mungert/all-MiniLM-L6-v2-GGUF/resolve/main/all-MiniLM-L6-v2-q8_0.gguf",
        sizeMb = 25,
        dim = 384,
    )

    val ALL: List<EmbedModel> = listOf(NOMIC_V15_Q8, MINILM_L6_Q8)
    val DEFAULT: EmbedModel = NOMIC_V15_Q8

    fun resolve(id: String?): EmbedModel? = ALL.firstOrNull { it.id == id }
}
