package task3.data

import kotlin.math.sqrt

/** L2-нормализация (косинус = dot, как требует брутфорс-поиск). */
fun l2normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += (x * x).toDouble()
    val n = sqrt(s).toFloat()
    if (n > 1e-9f) for (i in v.indices) v[i] /= n
    return v
}

/**
 * Детерминированный фолбэк без модели: хэши символьных триграмм.
 * Используется только в FakeEmbedBridge (десктоп-превью).
 */
fun hashingEmbed(text: String, dim: Int): FloatArray {
    val v = FloatArray(dim)
    val t = "  ${text.lowercase()}  "
    for (i in 0 until t.length - 2) {
        val h = (t[i].code * 31 * 31 + t[i + 1].code * 31 + t[i + 2].code)
        val idx = (h and 0x7fffffff) % dim
        v[idx] += if ((h ushr 16) and 1 == 0) 1f else -1f
    }
    return l2normalize(v)
}

/** Точный top-K по косинусу (вектора уже нормализованы). */
fun cosineTopK(
    query: FloatArray,
    candidates: List<Pair<Int, FloatArray>>,
    topK: Int,
): List<Pair<Int, Float>> {
    if (candidates.isEmpty() || topK <= 0) return emptyList()
    return candidates
        .map { (idx, v) ->
            var dot = 0f
            val n = minOf(query.size, v.size)
            for (d in 0 until n) dot += query[d] * v[d]
            idx to dot
        }
        .sortedByDescending { it.second }
        .take(topK.coerceIn(1, candidates.size))
}

fun FloatArray.toBlob(): ByteArray {
    val out = ByteArray(size * 4)
    for (i in indices) {
        val bits = this[i].toBits()
        out[i * 4] = (bits and 0xFF).toByte()
        out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
        out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
        out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
    }
    return out
}

fun ByteArray.toFloatArray(): FloatArray {
    val out = FloatArray(size / 4)
    for (i in out.indices) {
        val bits = (this[i * 4].toInt() and 0xFF) or
            ((this[i * 4 + 1].toInt() and 0xFF) shl 8) or
            ((this[i * 4 + 2].toInt() and 0xFF) shl 16) or
            ((this[i * 4 + 3].toInt() and 0xFF) shl 24)
        out[i] = Float.fromBits(bits)
    }
    return out
}
