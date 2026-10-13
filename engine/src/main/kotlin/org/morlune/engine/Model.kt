// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.time.LocalDate
import java.time.ZoneId

/** Normalised weather condition. Every provider maps its own codes/text onto this. */
enum class Condition {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, HAZE, DUST,
    DRIZZLE, RAIN, HEAVY_RAIN, SHOWERS, THUNDERSTORM, HAIL,
    SNOW, FROST, WIND, UNKNOWN
}

/** Where we want the weather for. [extra] carries provider-specific keys (station id, slug...). */
data class Location(
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val zoneId: String = ZoneId.systemDefault().id,
    val extra: Map<String, String> = emptyMap(),
)

/** [rainMm]: rain observed today if the source has it, otherwise today's forecast total. */
data class CurrentWeather(
    val temperatureC: Double?,
    val feelsLikeC: Double?,
    val rainMm: Double?,
    val condition: Condition,
    val isDay: Boolean?,
)

data class DailyForecast(
    val date: LocalDate,
    val minC: Double?,
    val maxC: Double?,
    val condition: Condition,
    val rainMm: Double?,
    val rainChancePct: Int?,
)

data class WeatherData(
    val providerId: String,
    val fetchedAtEpochMs: Long,
    val current: CurrentWeather?,
    val daily: List<DailyForecast>,
    val webUrl: String?,
    val attribution: String?,
    /** Non-fatal problems (a field that failed, an unmapped condition...). Handy for provider authors. */
    val warnings: List<String>,
) {
    /** Condition to drive the background image: current if known, else today's forecast. */
    val backgroundCondition: Condition
        get() = current?.condition?.takeIf { it != Condition.UNKNOWN }
            ?: daily.firstOrNull()?.condition
            ?: Condition.UNKNOWN
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Anything that can produce [WeatherData]. Declarative JSON providers and hand-written Kotlin ones both implement this. */
interface WeatherProvider {
    val id: String
    val name: String
    fun fetch(location: Location): WeatherData
}

/** Maps a condition (+ day/night) to the key of a background image in the widget's image pack. */
object Backgrounds {
    fun key(condition: Condition, isDay: Boolean?): String {
        val group = when (condition) {
            Condition.CLEAR, Condition.FROST, Condition.UNKNOWN -> "clear"
            Condition.PARTLY_CLOUDY -> "partly_cloudy"
            Condition.CLOUDY, Condition.WIND -> "cloudy"
            Condition.FOG, Condition.HAZE, Condition.DUST -> "fog"
            Condition.DRIZZLE, Condition.RAIN, Condition.SHOWERS -> "rain"
            Condition.HEAVY_RAIN -> "heavy_rain"
            Condition.THUNDERSTORM, Condition.HAIL -> "storm"
            Condition.SNOW -> "snow"
        }
        return group + if (isDay == false) "_night" else "_day"
    }
}
