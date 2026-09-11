package agent.data

import agent.domain.LlmModel
import java.io.File

// Хранилище последнего выбора модели — обычный текстовый файл рядом с chat.db
// (вне диалоговой БД, переживает рестарты; в тестах лежит во временной папке).
object ModelStore {
    const val FILE_NAME: String = "selected-model.txt"

    fun fileFor(dir: File?): File = File(dir, FILE_NAME)

    fun load(dir: File?): LlmModel {
        return try {
            val f = fileFor(dir)
            if (!f.isFile) return LlmModel.DEEPSEEK
            LlmModel.byId(f.readText().trim().ifEmpty { null })
        } catch (_: Exception) {
            LlmModel.DEEPSEEK
        }
    }

    fun save(dir: File?, model: LlmModel) {
        try {
            val f = fileFor(dir)
            f.parentFile?.mkdirs()
            f.writeText(model.id)
        } catch (_: Exception) {
            // Выбор модели — не критично, молча игнорируем.
        }
    }
}
