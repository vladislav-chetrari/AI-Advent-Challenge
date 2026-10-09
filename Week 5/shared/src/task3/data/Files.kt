package task3.data

/** Размер файла в байтах (-1, если файла нет). */
expect fun fileSizeBytes(path: String): Long

/**
 * Быстрая проверка целостности скачанной модели без натива: только размер.
 * Формат-специфичных проверок нет осознанно (.litertlm — opaque-бандл,
 * рантайм сам ругнётся на битый файл при load).
 * Возвращает null, если файл похож на целый, иначе текст ошибки для UI
 * (с размером — для удалённой диагностики).
 */
fun validateModelFile(path: String, expectedSizeMb: Int): String? {
    val size = fileSizeBytes(path)
    if (size <= 0) return "Файл пустой или недоступен — скачай заново"
    val expected = expectedSizeMb.toLong() * 1024 * 1024
    if (size < expected * 8 / 10) {
        return "Файл оборван: ${size / 1024 / 1024} МБ из ~$expectedSizeMb МБ — удали и скачай заново"
    }
    return null
}
