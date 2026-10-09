package task2.domain

/**
 * Зашитый каталог .litertlm-моделей под LiteRT-LM (без произвольных URL — KISS).
 * Дефолт для задачи: qwen25-1.5b-q8.
 */
data class AiModel(
    val id: String,
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
    val sha256: String = "",
    val defaultCtx: Int = 2048,
)
