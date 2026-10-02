package core.rag

import java.util.LinkedHashMap
import kotlin.math.sqrt

// Провайдер эмбеддингов: текст -> нормализованный вектор.
// Единственная реализация для продакшена — локальная модель через Ollama
// (см. OllamaEmbeddings.kt). RagService разницы не видит.
interface EmbeddingProvider {
    val name: String
    val dim: Int
    suspend fun embed(texts: List<String>): List<FloatArray>
}

// Детерминированный провайдер без модели: хэши символьных триграмм
// со знаковой проекцией + L2-норма. Качество — слабое, в проде НЕ используется.
// Нужен только для тестов без запущенной Ollama.
class HashingEmbedder(override val dim: Int = 384) : EmbeddingProvider {
    override val name: String = "hashing-fallback"

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { text ->
            val v = FloatArray(dim)
            val t = "  ${text.lowercase()}  "
            for (i in 0 until t.length - 2) {
                val h = (t[i].code * 31 * 31 + t[i + 1].code * 31 + t[i + 2].code)
                val idx = (h and 0x7fffffff) % dim
                v[idx] += if ((h ushr 16) and 1 == 0) 1f else -1f
            }
            l2normalize(v)
        }
}

fun l2normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += (x * x).toDouble()
    val n = sqrt(s).toFloat()
    if (n > 1e-9f) for (i in v.indices) v[i] /= n
    return v
}

// Кэш эмбеддингов по тексту: при сравнении 2 стратегий одни и те же куски
// текста не считаются дважды; при повторной индексации без изменений — тоже.
// LRU на 20k записей, потокобезопасный.
class CachedEmbedder(
    private val delegate: EmbeddingProvider,
    private val maxEntries: Int = 20_000,
) : EmbeddingProvider {
    override val name: String get() = delegate.name
    override val dim: Int get() = delegate.dim

    private val cache = object : LinkedHashMap<String, FloatArray>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > maxEntries
    }
    private val lock = Any()

    @Volatile var hits: Int = 0
        private set
    @Volatile var misses: Int = 0
        private set

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val result = arrayOfNulls<FloatArray>(texts.size)
        val missing = mutableListOf<String>()
        val missingIdx = mutableListOf<Int>()
        synchronized(lock) {
            texts.forEachIndexed { i, t ->
                val v = cache[t]
                if (v != null) {
                    hits++
                    result[i] = v
                } else {
                    misses++
                    missing += t
                    missingIdx += i
                }
            }
        }
        if (missing.isNotEmpty()) {
            val computed = delegate.embed(missing)
            synchronized(lock) {
                computed.forEachIndexed { k, v ->
                    cache[missing[k]] = v
                    result[missingIdx[k]] = v
                }
            }
        }
        return result.map { it!! }
    }

    fun stats(): String = "cache hits=$hits misses=$misses size=${cache.size}"
}
