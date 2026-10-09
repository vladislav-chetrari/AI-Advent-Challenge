package task3

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import task2.domain.EngineStatus
import task3.data.EmbedState
import task3.data.RagRepository
import task3.db.RagDatabase
import task3.domain.LlmChoice
import task3.llama.EmbedBridge
import task2.llama.LlamaBridge

/**
 * Старый .so без новых символов кидает UnsatisfiedLinkError (это Error,
 * не Exception). Раньше он пролетал сквозь catch(Exception) и ронял
 * приложение при добавлении статьи. Проверяем: ошибка видна, краша нет.
 */
class RagStaleNativeTest {
    private class StaleEmbedBridge : EmbedBridge {
        override val isReady: Boolean = false
        override val dim: Int = 0
        override suspend fun load(modelPath: String, nThreads: Int) {
            throw UnsatisfiedLinkError("fake missing symbol nativeEmbInit")
        }

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            throw UnsatisfiedLinkError("fake missing symbol nativeEmbEmbed")
        }

        override fun close() {}
    }

    private class StaleGenBridge : LlamaBridge {
        override val isReady: Boolean = false
        override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) {
            throw UnsatisfiedLinkError("fake missing symbol nativeInit")
        }

        override fun generate(prompt: String): Flow<String> = flowOf()
        override fun cancel() {}
        override fun close() {}
    }

    private fun repo(modelsDir: String, db: RagDatabase): RagRepository = RagRepository(
        genBridge = StaleGenBridge(),
        embBridge = StaleEmbedBridge(),
        dao = db.ragDao(),
        modelsDir = modelsDir,
        scope = CoroutineScope(Dispatchers.Default),
    )

    @Test
    fun staleEmbedLibSurfacesErrorInsteadOfCrash() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "rag-stale-${System.nanoTime()}").apply { mkdirs() }
        val modelsDir = File(dir, "models").apply { mkdirs() }.absolutePath
        val db = Room.databaseBuilder<RagDatabase>(
            name = File(modelsDir, "rag.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).build()
        try {
            val r = repo(modelsDir, db)
            r.init()
            File("$modelsDir/embed/nomic-embed-text-v1.5.Q8_0.gguf").apply {
                parentFile.mkdirs()
                writeText("fake")
            }
            r.selectEmbed("nomic-v15-q8")
            // Не должно кидать — статус уходит в Error с подсказкой про пересборку.
            r.ensureEmbedLoaded()
            val st = r.embedStatus.value
            assertTrue(st is EmbedState.Error, "ожидался Error, а там $st")
        } finally {
            db.close()
        }
    }

    @Test
    fun staleGenLibSurfacesErrorInChatInsteadOfCrash() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "rag-stale-${System.nanoTime()}").apply { mkdirs() }
        val modelsDir = File(dir, "models").apply { mkdirs() }.absolutePath
        val db = Room.databaseBuilder<RagDatabase>(
            name = File(modelsDir, "rag.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).build()
        try {
            val r = repo(modelsDir, db)
            r.init()
            File("$modelsDir/llm/Qwen_Qwen3-1.7B-Q4_K_M.gguf").apply {
                parentFile.mkdirs()
                writeText("fake")
            }
            r.selectLlm(LlmChoice.Local("qwen3-1.7b-q4"))
            // Не должно кидать — ошибка окажется текстом в чате и статусом.
            r.send("привет")
            val st = r.llmStatus.value
            assertTrue(st is EngineStatus.Error, "ожидался Error, а там $st")
            assertTrue(r.messages.value.any { it.text.startsWith("Ошибка:") })
        } finally {
            db.close()
        }
    }
}
