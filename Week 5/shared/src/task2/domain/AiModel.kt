package task2.domain

/**
 * Зашитый каталог GGUF-моделей (без произвольных URL — KISS).
 * Дефолт для задачи: qwen3-1.7b-q4.
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
