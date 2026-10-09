package task3.data

/** Размер файла в байтах (-1, если файла нет). */
expect fun fileSizeBytes(path: String): Long

/** Первые n байт файла (пусто, если не читается). */
expect fun readFileHead(path: String, n: Int): ByteArray

/**
 * Быстрая проверка целостности GGUF без натива: magic + версия + размер.
 * Возвращает null, если файл похож на целый, иначе текст ошибки для UI
 * (с размером и первыми байтами — для удалённой диагностики).
 */
fun validateGguf(path: String, expectedSizeMb: Int): String? {
    val size = fileSizeBytes(path)
    if (size <= 0) return "Файл пустой или недоступен — скачай заново"
    val expected = expectedSizeMb.toLong() * 1024 * 1024
    if (size < expected * 8 / 10) {
        return "Файл оборван: ${size / 1024} КБ из ~$expectedSizeMb МБ — удали и скачай заново"
    }
    val head = readFileHead(path, 16)
    if (head.size < 8) return "Файл не читается — скачай заново"
    val magic = head[0].toInt().and(0xFF) == 'G'.code &&
        head[1].toInt().and(0xFF) == 'U'.code &&
        head[2].toInt().and(0xFF) == 'G'.code &&
        head[3].toInt().and(0xFF) == 'F'.code
    if (!magic) {
        val ascii = head.take(16).joinToString("") { b ->
            val c = b.toInt().and(0xFF)
            if (c in 32..126) c.toChar().toString() else "·"
        }
        val hex = head.take(8).joinToString(" ") { b -> b.toInt().and(0xFF).toString(16).padStart(2, '0') }
        return "Это не GGUF (размер ${size / 1024} КБ, начало: «$ascii» [$hex]) — удали и скачай заново"
    }
    val version = (head[4].toLong().and(0xFF)) or
        (head[5].toLong().and(0xFF) shl 8) or
        (head[6].toLong().and(0xFF) shl 16) or
        (head[7].toLong().and(0xFF) shl 24)
    if (version != 3L) return "Неподдерживаемая версия GGUF v$version (нужна v3)"
    return null
}
