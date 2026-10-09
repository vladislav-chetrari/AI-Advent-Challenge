package task3.llama

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Эмбеддинги через LiteRT-LM EmbeddingEngine (бандл EmbeddingGemma
 * .litertlm, токенизатор внутри — никакого ручного BPE).
 * CPU-бэкенд, normalize=true (косинус = dot, как ждёт брутфорс-поиск),
 * окно 512 токенов (чанк 1000 символов влезает целиком).
 */
class LiteRtEmbedBridge : EmbedBridge {
    private var engine: EmbeddingEngine? = null
    private var modelDim: Int = 768

    override val isReady: Boolean get() = engine?.isInitialized() == true

    /** Фактическая размерность модели (узнаём зондом при загрузке). */
    override val dim: Int get() = modelDim

    override suspend fun load(modelPath: String, nThreads: Int) =
        withContext(Dispatchers.IO) {
            close()
            val e = EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    maxInputLength = 512,
                ),
            )
            try {
                e.initialize()
            } catch (t: Throwable) {
                runCatching { e.close() }
                throw IllegalStateException("LiteRT не смог открыть $modelPath: ${t.message}")
            }
            // dim в каталоге — для подписи в UI, истина узнаётся здесь.
            modelDim = e.computeEmbedding(listOf(InputData.Text("проверка"))).embedding.size
            engine = e
        }

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.IO) {
            val e = engine?.takeIf { it.isInitialized() }
                ?: throw IllegalStateException("Embedding-модель не загружена")
            e.computeEmbeddingBatch(
                texts.map { listOf(InputData.Text(it)) },
                EmbeddingOptions(normalize = true),
            ).map { it.embedding }
        }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}

actual fun createEmbedBridge(): EmbedBridge = LiteRtEmbedBridge()
