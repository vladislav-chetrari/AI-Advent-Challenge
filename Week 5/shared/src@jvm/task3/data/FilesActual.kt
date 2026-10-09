package task3.data

import java.io.File

actual fun fileSizeBytes(path: String): Long {
    val f = File(path)
    return if (f.isFile) f.length() else -1
}

actual fun readFileHead(path: String, n: Int): ByteArray {
    return try {
        File(path).inputStream().use { input ->
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(buf, got, n - got)
                if (r < 0) break
                got += r
            }
            buf.copyOf(got)
        }
    } catch (_: Exception) {
        ByteArray(0)
    }
}
