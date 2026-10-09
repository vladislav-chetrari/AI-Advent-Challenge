package task3

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import task2.data.ModelCatalog
import task2.llama.FakeLlamaBridge
import task3.data.EmbedCatalog
import task3.data.RagRepository
import task3.db.RagDatabase
import task3.domain.LlmChoice
import task3.llama.FakeEmbedBridge

/** Удаление выбранной модели снимает выбор (а не висит на отсутствующем файле). */
class DeleteDeselectTest {
    private fun repo(modelsDir: String, db: RagDatabase): RagRepository = RagRepository(
        genBridge = FakeLlamaBridge(),
        embBridge = FakeEmbedBridge(),
        dao = db.ragDao(),
        modelsDir = modelsDir,
        scope = CoroutineScope(Dispatchers.Default),
    )

    @Test
    fun deleteSelectedLlmClearsChoice() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "rag-deldeselect-${System.nanoTime()}").apply { mkdirs() }
        val modelsDir = File(dir, "models").apply { mkdirs() }.absolutePath
        val db = Room.databaseBuilder<RagDatabase>(
            name = File(modelsDir, "rag.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).build()
        try {
            val r = repo(modelsDir, db)
            r.init()
            val m = ModelCatalog.QWEN3_06B_INT4
            File("$modelsDir/llm/${m.fileName}").apply { parentFile.mkdirs(); writeText("fake") }
            r.selectLlm(LlmChoice.Local(m.id))
            r.deleteLlm(m)
            assertNull(r.llmChoice.value, "выбор LLM должен сняться после удаления файла")
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteSelectedEmbedClearsChoice() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "rag-deldeselect-${System.nanoTime()}").apply { mkdirs() }
        val modelsDir = File(dir, "models").apply { mkdirs() }.absolutePath
        val db = Room.databaseBuilder<RagDatabase>(
            name = File(modelsDir, "rag.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).build()
        try {
            val r = repo(modelsDir, db)
            r.init()
            val m = EmbedCatalog.EMBEDGEMMA_270M
            File("$modelsDir/embed/${m.fileName}").apply { parentFile.mkdirs(); writeText("fake") }
            r.selectEmbed(m.id)
            r.deleteEmbed(m)
            assertNull(r.embedId.value, "выбор эмбеддинга должен сняться после удаления файла")
        } finally {
            db.close()
        }
    }
}
