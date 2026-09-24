import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Один тикер на все задачи: каждую секунду забираем due-задачи из SQLite и опрашиваем Open-Meteo.
// Отдельный launch на задачу не делаем — иначе при 100 городах потечёт и потеряется состояние при рестарте.
class Scheduler(
    private val store: Store,
    private val api: WeatherApi,
    private val tickMs: Long = 1_000,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        println("[scheduler] tick=${tickMs}ms")
        while (isActive) {
            try {
                tick()
            } catch (e: Exception) {
                println("[scheduler] tick failed: ${e.message}")
            }
            delay(tickMs)
        }
    }

    suspend fun tick() {
        val now = store.nowMs()
        val due = store.getDueWatches(now)
        if (due.isNotEmpty()) println("[scheduler] due=${due.size}")
        for (w in due) {
            try {
                val s = api.fetch(w.lat, w.lon)
                store.addReading(w.id, store.nowMs(), s.temp, s.wind)
                println("[scheduler] ${w.city}: temp=${s.temp} wind=${s.wind}")
            } catch (e: Exception) {
                // last_run_at не двигаем — задача останется due и попробует снова на следующем тике.
                println("[scheduler] ${w.city} failed: ${e.message}")
            }
        }
    }
}
