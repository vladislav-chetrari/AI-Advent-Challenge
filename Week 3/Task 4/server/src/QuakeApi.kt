import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Quake(
    val id: String,
    val mag: Double,
    val place: String,
    val time: String, // ISO-8601
    val ts: Long, // epoch ms
    val url: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val depthKm: Double = Double.NaN,
)

@Serializable
private data class UsgsProps(
    val mag: Double? = null,
    val place: String? = null,
    val time: Long? = null,
    val url: String? = null,
)

@Serializable
private data class UsgsGeom(val coordinates: List<Double> = emptyList())

@Serializable
private data class UsgsFeature(
    val id: String = "",
    val properties: UsgsProps? = null,
    val geometry: UsgsGeom? = null,
)

@Serializable
private data class UsgsCollection(val features: List<UsgsFeature> = emptyList())

// Тонкий клиент к USGS Earthquake API (без ключа).
// Один метод на внешнюю нужду: поиск за произвольный период [start, end]
// с фильтром по магнитуде/географии. USGS принимает любые даты (каталог с ~1900 г.),
// ограничение сверху не ставим: выдача всё равно режется параметром limit.
//
// ВАЖНО: у USGS НЕТ параметра place. География задаётся только:
//  - прямоугольником: minlatitude/maxlatitude/minlongitude/maxlongitude
//  - кругом: latitude/longitude + maxradiuskm
// См. https://earthquake.usgs.gov/fdsnws/event/1/
// Поэтому place-фильтр substring применяется только как вторичный (AND)
// поверх уже географически отфильтрованной выдачи, а не вместо неё.
class QuakeApi {
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun search(
        minMag: Double,
        start: LocalDate,
        end: LocalDate,
        limit: Int,
        place: String?,
        rect: GeoRect? = null,
        circle: GeoCircle? = null,
        orderby: String = "time",
    ): List<Quake> {
        require(!end.isBefore(start)) { "end_date ($end) is before start_date ($start)" }
        if (rect != null) {
            require(rect.north >= rect.south) { "max_latitude must be >= min_latitude" }
            require(rect.south >= -90 && rect.north <= 90) { "latitude must be -90..90" }
            require(rect.east >= rect.west) { "max_longitude must be >= min_longitude" }
        }
        if (circle != null) {
            require(circle.lat in -90.0..90.0) { "latitude must be -90..90" }
            require(circle.lon in -180.0..180.0) { "longitude must be -180..180" }
            require(circle.radiusKm in 0.0..20001.6) { "maxradiuskm must be 0..20001.6" }
        }
        require(orderby in setOf("time", "time-asc", "magnitude", "magnitude-asc")) {
            "orderby must be time|time-asc|magnitude|magnitude-asc"
        }
        val resp: UsgsCollection = http.get("https://earthquake.usgs.gov/fdsnws/event/1/query") {
            parameter("format", "geojson")
            parameter("starttime", start.toString()) // YYYY-MM-DD
            parameter("endtime", end.toString())
            parameter("minmagnitude", minMag)
            parameter("limit", limit)
            parameter("orderby", orderby)
            // Реальный гео-фильтр на стороне USGS (до limit, а не после).
            if (rect != null) {
                parameter("minlatitude", rect.south)
                parameter("maxlatitude", rect.north)
                parameter("minlongitude", rect.west)
                parameter("maxlongitude", rect.east)
            }
            if (circle != null) {
                parameter("latitude", circle.lat)
                parameter("longitude", circle.lon)
                parameter("maxradiuskm", circle.radiusKm)
            }
        }.body()
        // Вторичный substring-фильтр — только когда НЕТ гео-фильтра (fallback,
        // геокодинг не сработал). Когда rect/circle заданы, география уже
        // отфильтрована на стороне USGS до limit, substring не нужен и вреден
        // (режет соседние регионы по хрупкому полю place).
        val hasGeo = rect != null || circle != null
        val filter = if (hasGeo) "" else place?.trim()?.lowercase().orEmpty()
        return resp.features.mapNotNull { f ->
            val p = f.properties ?: return@mapNotNull null
            val mag = p.mag ?: return@mapNotNull null
            if (mag.isNaN()) return@mapNotNull null
            val pname = p.place.orEmpty()
            if (filter.isNotEmpty() && !pname.lowercase().contains(filter)) return@mapNotNull null
            val ts = p.time ?: 0L
            val coords = f.geometry?.coordinates ?: emptyList()
            Quake(
                id = f.id,
                mag = mag,
                place = pname.ifBlank { "unknown" },
                time = java.time.Instant.ofEpochMilli(ts).toString(),
                ts = ts,
                url = p.url.orEmpty(),
                lon = coords.getOrNull(0) ?: Double.NaN,
                lat = coords.getOrNull(1) ?: Double.NaN,
                depthKm = coords.getOrNull(2) ?: Double.NaN,
            )
        }
    }

    fun close() = http.close()
}
