import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class GeoRect(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
)

@Serializable
data class GeoCircle(
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
)

@Serializable
data class ResolvedArea(
    val rect: GeoRect? = null,
    val circle: GeoCircle? = null,
    val label: String = "",
    val source: String = "", // "gazetteer" | "nominatim" | "explicit"
)

@Serializable
private data class NominatimHit(
    val lat: String = "",
    val lon: String = "",
    val boundingbox: List<String> = emptyList(),
    val display_name: String = "",
    val type: String = "",
    val addresstype: String = "",
    val place_rank: Int? = null,
)

// Резолвер места в географию.
// USGS НЕ имеет параметра place (только min/maxlatitude/longitude и circle),
// поэтому place превращаем в rect/circle и фильтруем на стороне USGS,
// а не substring по полю place после limit.
//
// Семантика под запросы по городу:
//  - страны/крупные регионы (газеттир, addresstype=country) -> rect,
//  - города/населённые пункты -> circle с центром Nominatim и радиусом
//    DEFAULT_CITY_RADIUS_KM (50 км), переопределяется аргументом maxradiuskm.
object PlaceGeocoder {
    const val DEFAULT_CITY_RADIUS_KM = 50.0
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }

    private val cache = ConcurrentHashMap<String, ResolvedArea>()

    // Офлайн-газеттир для детерминизма и крупных регионов.
    // Nominatim для Европы отдаёт слишком большой bbox, Румынию — точно.
    private val gazetteer: Map<String, ResolvedArea> = mapOf(
        "europe" to ResolvedArea(
            rect = GeoRect(south = 35.0, north = 72.0, west = -25.0, east = 45.0),
            label = "Europe",
            source = "gazetteer",
        ),
        "romania" to ResolvedArea(
            rect = GeoRect(south = 43.5, north = 48.5, west = 20.0, east = 30.1),
            label = "Romania",
            source = "gazetteer",
        ),
        "italy" to ResolvedArea(
            rect = GeoRect(south = 35.0, north = 47.5, west = 6.5, east = 19.0),
            label = "Italy",
            source = "gazetteer",
        ),
        "greece" to ResolvedArea(
            rect = GeoRect(south = 34.5, north = 42.0, west = 19.0, east = 30.0),
            label = "Greece",
            source = "gazetteer",
        ),
        "turkey" to ResolvedArea(
            rect = GeoRect(south = 35.5, north = 42.5, west = 25.5, east = 45.0),
            label = "Turkey",
            source = "gazetteer",
        ),
        "japan" to ResolvedArea(
            rect = GeoRect(south = 30.0, north = 46.0, west = 128.0, east = 146.0),
            label = "Japan",
            source = "gazetteer",
        ),
        "chile" to ResolvedArea(
            rect = GeoRect(south = -56.0, north = -17.0, west = -76.0, east = -66.0),
            label = "Chile",
            source = "gazetteer",
        ),
        "iceland" to ResolvedArea(
            rect = GeoRect(south = 63.0, north = 67.0, west = -25.0, east = -13.0),
            label = "Iceland",
            source = "gazetteer",
        ),
    )

    suspend fun resolve(place: String, radiusKmOverride: Double? = null): ResolvedArea? {
        val key = place.trim().lowercase()
        if (key.isEmpty()) return null
        if (radiusKmOverride != null) {
            require(radiusKmOverride in 0.0..20001.6) { "maxradiuskm must be 0..20001.6" }
        }
        // Кэш учитывает радиус: города кэшируются по ключу + радиусу.
        val cacheKey = if (radiusKmOverride != null) "$key#r=$radiusKmOverride" else key
        cache[cacheKey]?.let { return it }
        gazetteer[key]?.let {
            cache[cacheKey] = it
            return it
        }
        // Nominatim: https://nominatim.openstreetmap.org/search?q=...&format=json&limit=1
        // Требует User-Agent по usage policy.
        try {
            val hits: List<NominatimHit> = http.get("https://nominatim.openstreetmap.org/search") {
                parameter("q", place.trim())
                parameter("format", "json")
                parameter("limit", 1)
                header("User-Agent", "week3-quake-pipeline/1.0 (contact: local)")
            }.body()
            val hit = hits.firstOrNull() ?: return null
            val lat = hit.lat.toDoubleOrNull()
            val lon = hit.lon.toDoubleOrNull()
            if (lat == null || lon == null) return null
            val radius = radiusKmOverride ?: DEFAULT_CITY_RADIUS_KM
            val label = hit.display_name.ifBlank { place }
            val area = if (isCountryLike(hit) && hit.boundingbox.size == 4) {
                val s = hit.boundingbox[0].toDoubleOrNull()
                val n = hit.boundingbox[1].toDoubleOrNull()
                val w = hit.boundingbox[2].toDoubleOrNull()
                val e = hit.boundingbox[3].toDoubleOrNull()
                if (s != null && n != null && w != null && e != null && n > s && e > w) {
                    ResolvedArea(
                        rect = GeoRect(s, n, w, e),
                        label = label,
                        source = "nominatim",
                    )
                } else {
                    ResolvedArea(
                        circle = GeoCircle(lat, lon, radius),
                        label = label,
                        source = "nominatim",
                    )
                }
            } else {
                // Город/населённый пункт/округ: круг вокруг центра.
                ResolvedArea(
                    circle = GeoCircle(lat, lon, radius),
                    label = label,
                    source = "nominatim",
                )
            }
            cache[cacheKey] = area
            return area
        } catch (e: Exception) {
            return null
        }
    }

    // Страна (и аналогично крупное) — единственный случай, когда берём bbox.
    // Всё остальное (city/town/village/county/...) — круг: bbox города
    // покрывает только застройку (~20 км), а quakes вида "X km from city" рядом выпадут.
    private fun isCountryLike(hit: NominatimHit): Boolean {
        if (hit.addresstype.lowercase() == "country") return true
        // place_rank у Nominatim: чем меньше, тем крупнее (страна ~4).
        val rank = hit.place_rank
        if (rank != null && rank <= 6) return true
        return false
    }
}
