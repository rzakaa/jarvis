package pl.jarvis.brain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.LocalDate

/**
 * Narzędzia Jarvisa sięgające do internetu: pogoda (Open-Meteo – darmowe, bez klucza)
 * i czytanie stron. Do sieci idzie tylko nazwa miasta albo adres strony. Blokują – wołać poza wątkiem UI.
 */
object WebTools {
    private val MAPPER = ObjectMapper()
    private val HTTP = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .followRedirects(true)
        .build()

    private const val PAGE_LIMIT = 6000

    /** Prognoza dla miasta: teraz + [days] kolejnych dni (0 = tylko dziś). */
    fun weather(city: String, days: Int): String {
        val place = getJson(
            "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", city)
                .addQueryParameter("count", "1")
                .addQueryParameter("language", "pl")
                .build().toString(),
        ).path("results").path(0)
        if (place.isMissingNode) return "Nie znalazłem miejscowości „$city”."
        val name = listOf(place.path("name").asText(), place.path("admin1").asText(), place.path("country").asText())
            .filter { it.isNotBlank() }.joinToString(", ")
        val forecast = getJson(
            "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", place.path("latitude").asText())
                .addQueryParameter("longitude", place.path("longitude").asText())
                .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation")
                .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum")
                .addQueryParameter("timezone", "auto")
                .addQueryParameter("forecast_days", (days.coerceIn(0, 6) + 1).toString())
                .build().toString(),
        )
        val now = forecast.path("current")
        val lines = mutableListOf(
            "Pogoda: $name. Teraz ${now.path("temperature_2m").asDouble()}°C (odczuwalna ${now.path("apparent_temperature").asDouble()}°C), " +
                "${describe(now.path("weather_code").asInt())}, wiatr ${now.path("wind_speed_10m").asDouble()} km/h, " +
                "opad ${now.path("precipitation").asDouble()} mm.",
        )
        val daily = forecast.path("daily")
        for (i in 0 until daily.path("time").size()) {
            lines += "${LocalDate.parse(daily.path("time").path(i).asText())}: " +
                "${daily.path("temperature_2m_min").path(i).asDouble()}…${daily.path("temperature_2m_max").path(i).asDouble()}°C, " +
                "${describe(daily.path("weather_code").path(i).asInt())}, " +
                "szansa opadów ${daily.path("precipitation_probability_max").path(i).asInt()}% " +
                "(${daily.path("precipitation_sum").path(i).asDouble()} mm)"
        }
        return lines.joinToString("\n")
    }

    /**
     * Kartka z kalendarza na dzień: święta państwowe (Nager.Date), imieniny, święta nietypowe
     * (np. Dzień Hydraulika), przysłowie, wschód i zachód słońca (kalbi.pl).
     */
    fun dayCalendar(day: LocalDate): String {
        val lines = mutableListOf("Kalendarz na $day (${Polish.dayName(day)}).")
        runCatching {
            val holidays = getJson("https://date.nager.at/api/v3/PublicHolidays/${day.year}/PL")
                .filter { it.path("date").asText() == day.toString() }.map { it.path("localName").asText() }
            lines += "Święto państwowe: " + holidays.joinToString().ifEmpty { "brak" } + "."
        }
        val page = runCatching {
            val request = Request.Builder().url("https://www.kalbi.pl/${day.dayOfMonth}-${MONTHS[day.monthValue - 1]}")
                .header("User-Agent", "Mozilla/5.0 (Android) Jarvis").build()
            HTTP.newCall(request).execute().use { if (it.isSuccessful) htmlToText(it.body?.string().orEmpty()) else "" }
        }.getOrDefault("").replace('\n', ' ').replace("&#8222;", "„").replace("&#8221;", "”")
        // Kartka: od „Imieniny obchodzą” do lokalizacji; potem opisy świąt nietypowych.
        val card = page.substringAfter("Imieniny obchodzą:", "").substringBefore("Lokalizacja:").trim()
        if (card.isNotEmpty()) lines += "Imieniny obchodzą: $card"
        val unusual = page.substringAfter("Jakie jest święto nietypowe", "").substringAfter("?", "")
            .substringBefore("Ile trwa dzień").trim().take(1200)
        if (unusual.isNotEmpty()) lines += "Opisy świąt nietypowych: $unusual"
        if (card.isEmpty()) lines += "(Nie udało się pobrać imienin i świąt nietypowych.)"
        return lines.joinToString("\n")
    }

    private val MONTHS = listOf(
        "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
        "lipca", "sierpnia", "wrzesnia", "pazdziernika", "listopada", "grudnia",
    )

    /** Tekst strony (bez znaczników), ucięty do [PAGE_LIMIT] znaków. */
    fun fetchPage(url: String): String {
        val fixed = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val request = Request.Builder().url(fixed).header("User-Agent", "Mozilla/5.0 (Android) Jarvis").build()
        HTTP.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val text = if (response.header("Content-Type").orEmpty().contains("html")) htmlToText(body) else body
            return text.take(PAGE_LIMIT).ifBlank { "Strona jest pusta." }
        }
    }

    private fun htmlToText(html: String) = html
        .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<(br|p|div|li|h[1-6]|tr)[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\\n\\s*"), "\n")
        .trim()

    private fun getJson(url: String): JsonNode {
        HTTP.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return MAPPER.readTree(response.body?.string().orEmpty())
        }
    }

    /** Kody pogody WMO po polsku. */
    private fun describe(code: Int) = when (code) {
        0 -> "bezchmurnie"
        1 -> "przeważnie pogodnie"
        2 -> "częściowe zachmurzenie"
        3 -> "pochmurno"
        45, 48 -> "mgła"
        51, 53, 55 -> "mżawka"
        56, 57 -> "marznąca mżawka"
        61 -> "słaby deszcz"
        63 -> "deszcz"
        65 -> "ulewny deszcz"
        66, 67 -> "marznący deszcz"
        71 -> "słaby śnieg"
        73 -> "śnieg"
        75 -> "intensywny śnieg"
        77 -> "ziarna śniegu"
        80, 81, 82 -> "przelotne opady deszczu"
        85, 86 -> "przelotne opady śniegu"
        95 -> "burza"
        96, 99 -> "burza z gradem"
        else -> "kod pogody $code"
    }
}
