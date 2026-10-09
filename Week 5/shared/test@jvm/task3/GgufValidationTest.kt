package task3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import task3.data.validateGguf

class GgufValidationTest {
    private fun tmp(content: ByteArray): String {
        val f = File.createTempFile("gguf-check", ".gguf").also { it.deleteOnExit() }
        f.writeBytes(content)
        return f.absolutePath
    }

    private fun header(version: Int = 3): ByteArray {
        val head = ByteArray(12)
        head[0] = 'G'.code.toByte()
        head[1] = 'U'.code.toByte()
        head[2] = 'G'.code.toByte()
        head[3] = 'F'.code.toByte()
        head[4] = (version and 0xFF).toByte()
        return head
    }

    @Test
    fun validHeaderPasses() {
        // 25 МБ нулей с корректным заголовком — размер и magic в порядке.
        val content = ByteArray(25 * 1024 * 1024)
        header().copyInto(content)
        assertNull(validateGguf(tmp(content), 25))
    }

    @Test
    fun truncatedFileFails() {
        val content = ByteArray(5 * 1024 * 1024)
        header().copyInto(content)
        val err = validateGguf(tmp(content), 25)
        assertTrue(err != null && "оборван" in err, "ожидалась жалоба на обрыв, а там: $err")
    }

    @Test
    fun htmlInsteadOfGgufFails() {
        val content = ByteArray(25 * 1024 * 1024)
        "<html>Not Found</html>".encodeToByteArray().copyInto(content)
        val err = validateGguf(tmp(content), 25)
        assertTrue(err != null && "GGUF" in err, "ожидалась жалоба на magic, а там: $err")
    }

    @Test
    fun wrongVersionFails() {
        val content = ByteArray(25 * 1024 * 1024)
        header(version = 2).copyInto(content)
        val err = validateGguf(tmp(content), 25)
        assertTrue(err != null && "v2" in err, "ожидалась жалоба на версию, а там: $err")
    }
}
