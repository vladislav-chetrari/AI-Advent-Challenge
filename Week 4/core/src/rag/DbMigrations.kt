package core.rag

import java.io.File
import java.sql.DriverManager
import org.flywaydb.core.Flyway

// Схема SQLite живёт в SQL-миграциях (core/resources/db/migration),
// применяется через Flyway при каждом старте — вместо
// Exposed SchemaUtils.createMissingTablesAndColumns.
//
// Старые БД, созданные до Flyway (таблица chunk с PK только по id),
// не мигрируем: там лишь перестраиваемые чанки, файл удаляется
// и схема создаётся чисто через V1. JSON-файлы (чаты, источники)
// при этом не трогаем.
object DbMigrations {
    fun migrate(dbFile: File) {
        dbFile.parentFile?.mkdirs()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (_: ClassNotFoundException) {
        }
        if (isLegacy(dbFile, url)) {
            try {
                dbFile.delete()
            } catch (_: Exception) {
            }
        }
        Flyway.configure()
            .dataSource(url, null, null)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    // Legacy = есть таблица chunk, но нет истории Flyway.
    private fun isLegacy(dbFile: File, url: String): Boolean {
        if (!dbFile.isFile || dbFile.length() == 0L) return false
        var conn: java.sql.Connection? = null
        return try {
            conn = DriverManager.getConnection(url)
            val md = conn.metaData
            var hasHistory = false
            var hasChunk = false
            md.getTables(null, null, "flyway_schema_history", null).use { rs ->
                if (rs.next()) hasHistory = true
            }
            md.getTables(null, null, "chunk", null).use { rs ->
                if (rs.next()) hasChunk = true
            }
            hasChunk && !hasHistory
        } catch (_: Exception) {
            false
        } finally {
            try {
                conn?.close()
            } catch (_: Exception) {
            }
        }
    }
}
