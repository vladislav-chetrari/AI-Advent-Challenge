package task3.data

/**
 * Локальная embedding-модель под LiteRT-LM EmbeddingEngine:
 * EmbeddingGemma 270M (.litertlm-бандл, токенизатор внутри),
 * мультиязычная (русская Вики — её профиль), 165 МБ, выход 768d.
 * Префиксов query/document не нужно — bridge отдаёт тексты как есть.
 */
data class EmbedModel(
    val id: String,
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
    /** Размерность вектора (проверяется после загрузки зондом). */
    val dim: Int,
    /** Префикс для документов (EmbeddingGemma не нужен). */
    val docPrefix: String = "",
    /** Префикс для поискового запроса. */
    val queryPrefix: String = "",
)

object EmbedCatalog {
    val EMBEDGEMMA_270M = EmbedModel(
        id = "embedgemma-270m",
        label = "EmbeddingGemma 270M (~165 МБ)",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        sizeMb = 157,
        dim = 768,
    )

    val ALL: List<EmbedModel> = listOf(EMBEDGEMMA_270M)
    val DEFAULT: EmbedModel = EMBEDGEMMA_270M

    fun resolve(id: String?): EmbedModel? = ALL.firstOrNull { it.id == id }
}
