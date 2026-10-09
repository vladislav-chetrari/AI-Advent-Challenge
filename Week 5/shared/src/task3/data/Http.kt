package task3.data

/**
 * Файловые операции остаются expect/actual (java.io недоступен в common).
 * Весь сетевой транспорт — Ktor в common-коде, см. task2.data.Network.
 */
expect fun deleteFile(path: String)
