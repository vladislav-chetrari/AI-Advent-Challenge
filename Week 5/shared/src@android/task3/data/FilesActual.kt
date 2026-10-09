package task3.data

import java.io.File

actual fun fileSizeBytes(path: String): Long {
    val f = File(path)
    return if (f.isFile) f.length() else -1
}
