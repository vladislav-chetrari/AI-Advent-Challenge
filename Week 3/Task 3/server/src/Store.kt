import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable

@Serializable
data class Watch(
    val id: Long,
    val city: String, // нормализованный ключ: lowercase(trim)
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val intervalMs: Long,
    val createdAt: Long,
    val lastRunAt: Long? = null,
)

@Serializable
data class WatchSummary(
    val city: String,
    val displayName: String,
    val interval: String,
    val readings: Int,
    val hours: Int,
    val minTemp: Double? = null,
    val maxTemp: Double? = null,
    val avgTemp: Double? = null,
    val avgWind: Double? = null,
    val lastTs: Long? = null,
    val lastTemp: Double? = null,
    val lastWind: Double? = null,
)

// Единственное персистентное состояние сервера. Всё в SQLite, в памяти — ничего,
// поэтому процесс можно убивать в любой момент: при старте читаем watches и продолжаем.
class Store(dbFile: File) {
    private val lock = Any()
    private val conn: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        dbFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        synchronized(lock) {
            conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            conn.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE IF NOT EXISTS watches(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      city TEXT UNIQUE NOT NULL,
                      display_name TEXT NOT NULL,
                      lat REAL NOT NULL,
                      lon REAL NOT NULL,
                      interval_ms INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      last_run_at INTEGER
                    )
                    """.trimIndent(),
                )
            }
            conn.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE IF NOT EXISTS readings(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      watch_id INTEGER NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
                      ts INTEGER NOT NULL,
                      temp REAL NOT NULL,
                      wind REAL NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            conn.createStatement().use {
                it.execute("CREATE INDEX IF NOT EXISTS idx_readings_watch_ts ON readings(watch_id, ts)")
            }
        }
    }

    fun nowMs(): Long = System.currentTimeMillis()

    fun cityKey(city: String): String = city.trim().lowercase()

    fun upsertWatch(city: String, displayName: String, lat: Double, lon: Double, intervalMs: Long, now: Long): Watch {
        val key = cityKey(city)
        synchronized(lock) {
            conn.prepareStatement("SELECT id, created_at, last_run_at FROM watches WHERE city = ?").use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val id = rs.getLong(1)
                        val createdAt = rs.getLong(2)
                        val lastRun = rs.getLong(3).let { if (rs.wasNull()) null else it }
                        conn.prepareStatement(
                            "UPDATE watches SET display_name = ?, lat = ?, lon = ?, interval_ms = ? WHERE id = ?",
                        ).use { up ->
                            up.setString(1, displayName)
                            up.setDouble(2, lat)
                            up.setDouble(3, lon)
                            up.setLong(4, intervalMs)
                            up.setLong(5, id)
                            up.executeUpdate()
                        }
                        return Watch(id, key, displayName, lat, lon, intervalMs, createdAt, lastRun)
                    }
                }
            }
            conn.prepareStatement(
                "INSERT INTO watches(city, display_name, lat, lon, interval_ms, created_at) VALUES(?,?,?,?,?,?)",
            ).use { ins ->
                ins.setString(1, key)
                ins.setString(2, displayName)
                ins.setDouble(3, lat)
                ins.setDouble(4, lon)
                ins.setLong(5, intervalMs)
                ins.setLong(6, now)
                ins.executeUpdate()
            }
            conn.prepareStatement("SELECT id FROM watches WHERE city = ?").use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return Watch(rs.getLong(1), key, displayName, lat, lon, intervalMs, now, null)
                }
            }
        }
    }

    fun listWatches(): List<Watch> = synchronized(lock) {
        conn.prepareStatement(
            "SELECT id, city, display_name, lat, lon, interval_ms, created_at, last_run_at FROM watches ORDER BY city",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Watch>()
                while (rs.next()) {
                    val lastRun = rs.getLong(8).let { if (rs.wasNull()) null else it }
                    out += Watch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6), rs.getLong(7), lastRun)
                }
                out
            }
        }
    }

    fun findByCity(city: String): Watch? {
        val key = cityKey(city)
        synchronized(lock) {
            conn.prepareStatement(
                "SELECT id, city, display_name, lat, lon, interval_ms, created_at, last_run_at FROM watches WHERE city = ?",
            ).use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val lastRun = rs.getLong(8).let { if (rs.wasNull()) null else it }
                    return Watch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6), rs.getLong(7), lastRun)
                }
            }
        }
    }

    fun deleteByCity(city: String): Boolean {
        val key = cityKey(city)
        synchronized(lock) {
            conn.prepareStatement("DELETE FROM watches WHERE city = ?").use { ps ->
                ps.setString(1, key)
                return ps.executeUpdate() > 0
            }
        }
    }

    fun getDueWatches(now: Long): List<Watch> = synchronized(lock) {
        conn.prepareStatement(
            "SELECT id, city, display_name, lat, lon, interval_ms, created_at, last_run_at FROM watches " +
                "WHERE last_run_at IS NULL OR last_run_at + interval_ms <= ?",
        ).use { ps ->
            ps.setLong(1, now)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Watch>()
                while (rs.next()) {
                    val lastRun = rs.getLong(8).let { if (rs.wasNull()) null else it }
                    out += Watch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6), rs.getLong(7), lastRun)
                }
                out
            }
        }
    }

    // Пишем замер и только при успехе двигаем last_run_at — при ошибке задача останется due и ретраится.
    fun addReading(watchId: Long, ts: Long, temp: Double, wind: Double) {
        synchronized(lock) {
            conn.prepareStatement("INSERT INTO readings(watch_id, ts, temp, wind) VALUES(?,?,?,?)").use { ps ->
                ps.setLong(1, watchId)
                ps.setLong(2, ts)
                ps.setDouble(3, temp)
                ps.setDouble(4, wind)
                ps.executeUpdate()
            }
            conn.prepareStatement("UPDATE watches SET last_run_at = ? WHERE id = ?").use { ps ->
                ps.setLong(1, ts)
                ps.setLong(2, watchId)
                ps.executeUpdate()
            }
        }
    }

    fun summary(watch: Watch, hours: Int): WatchSummary {
        val since = nowMs() - hours * 3_600_000L
        synchronized(lock) {
            conn.prepareStatement(
                "SELECT COUNT(*), MIN(temp), MAX(temp), AVG(temp), AVG(wind) FROM readings WHERE watch_id = ? AND ts >= ?",
            ).use { ps ->
                ps.setLong(1, watch.id)
                ps.setLong(2, since)
                ps.executeQuery().use { rs ->
                    rs.next()
                    val count = rs.getInt(1)
                    if (count == 0) {
                        return WatchSummary(watch.city, watch.displayName, Interval.format(watch.intervalMs), 0, hours)
                    }
                    val minT = rs.getDouble(2)
                    val maxT = rs.getDouble(3)
                    val avgT = rs.getDouble(4)
                    val avgW = rs.getDouble(5)
                    conn.prepareStatement(
                        "SELECT ts, temp, wind FROM readings WHERE watch_id = ? AND ts >= ? ORDER BY ts DESC LIMIT 1",
                    ).use { last ->
                        last.setLong(1, watch.id)
                        last.setLong(2, since)
                        last.executeQuery().use { lr ->
                            lr.next()
                            return WatchSummary(
                                city = watch.city,
                                displayName = watch.displayName,
                                interval = Interval.format(watch.intervalMs),
                                readings = count,
                                hours = hours,
                                minTemp = minT,
                                maxTemp = maxT,
                                avgTemp = avgT,
                                avgWind = avgW,
                                lastTs = lr.getLong(1),
                                lastTemp = lr.getDouble(2),
                                lastWind = lr.getDouble(3),
                            )
                        }
                    }
                }
            }
        }
    }

    fun close() = conn.close()

    companion object {
        fun defaultDbFile(): File {
            val dir = File(System.getProperty("user.home"), ".ai-advent-week3-task3")
            return File(dir, "weather.db")
        }
    }
}
