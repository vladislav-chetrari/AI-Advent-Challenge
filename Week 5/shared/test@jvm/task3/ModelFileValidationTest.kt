package task3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import task3.data.validateModelFile

class ModelFileValidationTest {
    private fun tmp(sizeBytes: Int): String {
        val f = File.createTempFile("model-check", ".litertlm").also { it.deleteOnExit() }
        f.writeBytes(ByteArray(sizeBytes))
        return f.absolutePath
    }

    @Test
    fun fullSizePasses() {
        // 160 МБ нулей при ожидаемых 157 — размер в порядке.
        assertNull(validateModelFile(tmp(160 * 1024 * 1024), 157))
    }

    @Test
    fun truncatedFileFails() {
        val err = validateModelFile(tmp(5 * 1024 * 1024), 157)
        assertTrue(err != null && "оборван" in err, "ожидалась жалоба на обрыв, а там: $err")
    }

    @Test
    fun missingFileFails() {
        val err = validateModelFile("/nonexistent/model.litertlm", 157)
        assertTrue(err != null && "недоступен" in err, "ожидалась жалоба на отсутствие, а там: $err")
    }
}
