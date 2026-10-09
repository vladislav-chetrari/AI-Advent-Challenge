package task2.data

import java.io.File

actual fun platformFileExists(path: String): Boolean = File(path).let { it.exists() && it.length() > 0 }

actual fun platformCpuThreads(): Int = maxOf(4, Runtime.getRuntime().availableProcessors() - 2)

actual fun nowMillis(): Long = System.currentTimeMillis()
