import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeoPlace(val displayName: String, val lat: Double, val lon: Double)

@Serializable
private data class GeocodeResult(
    val name: String = "",
    val country: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

@Serializable
private data class GeocodeResponse(val results: List<GeocodeResult> = emptyList())

@Serializable
private data class CurrentWeather(val temperature_2m: Double = Double.NaN, val wind_speed_10m: Double = Double.NaN)

@Serializable
private data class ForecastResponse(val current: CurrentWeather? = null)

data class WeatherSample(val temp: Double, val wind: Double)

// Тонкий клиент к Open-Meteo (без ключа):
// геокодер + текущий прогноз. По одному методу на внешнюю нужду.
class WeatherApi {
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun geocode(city: String): GeoPlace {
        val resp: GeocodeResponse = http.get("https://geocoding-api.open-meteo.com/v1/search") {
            parameter("name", city)
            parameter("count", 1)
            parameter("language", "ru")
            parameter("format", "json")
        }.body()
        val r = resp.results.firstOrNull()
            ?: throw IllegalArgumentException("City not found: '$city'")
        val display = if (r.country.isNotBlank()) "${r.name}, ${r.country}" else r.name
        return GeoPlace(displayName = display, lat = r.latitude, lon = r.longitude)
    }

    suspend fun fetch(lat: Double, lon: Double): WeatherSample {
        val resp: ForecastResponse = http.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("current", "temperature_2m,wind_speed_10m")
            parameter("timezone", "auto")
        }.body()
        val cur = resp.current ?: throw IllegalStateException("Open-Meteo returned no current weather")
        return WeatherSample(temp = cur.temperature_2m, wind = cur.wind_speed_10m)
    }

    fun close() = http.close()
}
