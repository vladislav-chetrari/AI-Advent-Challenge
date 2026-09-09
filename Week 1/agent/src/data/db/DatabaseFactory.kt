package agent.data.db

import java.io.File
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

// SRP: только открытие файла + автопрогон версионированных миграций. SQL — в resources/db/migration.
object DatabaseFactory {
    fun defaultDbFile(): File {
        val dir = File(System.getProperty("user.home"), ".ai-advent-week1")
        return File(dir, "chat.db")
    }

    fun open(dbFile: File = defaultDbFile()): Database {
        dbFile.parentFile?.mkdirs()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        Flyway.configure()
            .dataSource(url, null, null)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return Database.connect(url, driver = "org.sqlite.JDBC")
    }
}
