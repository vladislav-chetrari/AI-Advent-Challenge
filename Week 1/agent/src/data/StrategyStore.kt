package agent.data

import agent.domain.StrategyType
import java.io.File

// Task 5: выбор стратегии — текстовый файл рядом с chat.db, по образцу ModelStore.
object StrategyStore {
    const val FILE_NAME: String = "strategy.txt"

    fun fileFor(dir: File?): File = File(dir, FILE_NAME)

    fun load(dir: File?): StrategyType {
        return try {
            val f = fileFor(dir)
            if (!f.isFile) return StrategyType.SLIDING
            StrategyType.byId(f.readText().trim().ifEmpty { null })
        } catch (_: Exception) {
            StrategyType.SLIDING
        }
    }

    fun save(dir: File?, strategy: StrategyType) {
        try {
            val f = fileFor(dir)
            f.parentFile?.mkdirs()
            f.writeText(strategy.id)
        } catch (_: Exception) {
        }
    }
}
