/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package nodomain.freeyourgadget.gadgetbridge.util.builtinweather

import android.content.Context
import android.net.Uri
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather
import nodomain.freeyourgadget.gadgetbridge.model.weather.WeatherMapper
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.floor

/** Fetches a small, keyless weather payload and translates it to GB's existing WeatherSpec. */
object BuiltinWeatherFetcher {
    private val LOG = LoggerFactory.getLogger(BuiltinWeatherFetcher::class.java)
    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    internal const val FORECAST_DAYS = 8 // Today + seven future days, required by some Huawei devices.
    private const val SYNODIC_MONTH_SECONDS = 29.530588853 * 24 * 60 * 60
    private const val REFERENCE_NEW_MOON_EPOCH_SECONDS = 947182440L // 2000-01-06 18:14 UTC

    fun fetch(context: Context): FetchResult {
        // The weather location is deliberately sticky. CurrentPosition may replace the saved
        // coordinates with Android's network-derived last known location, which can jump to a VPN
        // exit country. Only the explicit "use phone location" action should update these values.
        val prefs = GBApplication.getPrefs()
        val latitude = prefs.getFloat("location_latitude", 0f).toDouble()
        val longitude = prefs.getFloat("location_longitude", 0f).toDouble()
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            (latitude == 0.0 && longitude == 0.0)
        ) {
            return FetchResult.NoLocation
        }

        val query = Uri.parse(ENDPOINT).buildUpon()
            .appendQueryParameter("latitude", latitude.toString())
            .appendQueryParameter("longitude", longitude.toString())
            .appendQueryParameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m")
            .appendQueryParameter("hourly", "temperature_2m,relative_humidity_2m,precipitation_probability,weather_code,wind_speed_10m,wind_direction_10m")
            .appendQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset")
            .appendQueryParameter("forecast_days", FORECAST_DAYS.toString())
            // The first hourly point is normally the current (already-started) hour. Fetch one
            // extra so the watch still receives 24 strictly future points after filtering it out.
            .appendQueryParameter("forecast_hours", "25")
            .appendQueryParameter("timezone", "auto")
            .build()

        val json = try {
            InternetUtils.doJsonRequest(query)
        } catch (e: Exception) {
            LOG.warn("Could not fetch built-in weather", e)
            return FetchResult.RetryableFailure(e.message ?: e.javaClass.simpleName)
        } ?: return FetchResult.RetryableFailure("No response")

        return try {
            val location = context.getString(R.string.builtin_weather_current_location)
            val spec = parse(json, location, latitude.toFloat(), longitude.toFloat(), context)
            Weather.setWeatherSpec(listOf(spec))
            FetchResult.Success(spec)
        } catch (e: Exception) {
            LOG.warn("Could not parse built-in weather response", e)
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    internal fun parse(
        json: JSONObject,
        location: String,
        latitude: Float,
        longitude: Float,
        context: Context,
    ): WeatherSpec {
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val hourly = json.optJSONObject("hourly")
        val zone = try {
            ZoneId.of(json.optString("timezone", "UTC"))
        } catch (_: Exception) {
            ZoneId.of("UTC")
        }
        val now = epochSeconds(current.optString("time", ""), zone).takeIf { it > 0 }
            ?: Instant.now().epochSecond.toInt()

        fun kelvin(celsius: Double): Int = (celsius + 273.15).toInt()
        fun condition(code: Int): Int = mapWmoToOpenWeather(code)
        fun probability(value: Double): Int = value.coerceIn(0.0, 100.0).toInt()
        fun optDouble(obj: JSONObject, key: String, index: Int = -1): Double {
            return if (index >= 0) obj.optJSONArray(key)?.optDouble(index, 0.0) ?: 0.0
            else obj.optDouble(key, 0.0)
        }
        fun dailyMoonPhase(index: Int): Int {
            val dayStart = epochSeconds(daily.optJSONArray("time")?.optString(index, ""), zone)
            val fallback = epochSeconds(daily.optJSONArray("sunrise")?.optString(index, ""), zone)
            return moonPhaseDegrees((dayStart.takeIf { it > 0 } ?: fallback).toLong())
        }

        val currentCode = condition(current.optInt("weather_code", 0))
        val spec = WeatherSpec().apply {
            timestamp = now.toInt()
            this.location = location
            currentTemp = kelvin(current.optDouble("temperature_2m", 0.0))
            feelsLikeTemp = kelvin(current.optDouble("apparent_temperature", current.optDouble("temperature_2m", 0.0)))
            currentConditionCode = currentCode
            currentCondition = WeatherMapper.getConditionString(context, currentCode)
            currentHumidity = current.optInt("relative_humidity_2m", 0)
            todayMaxTemp = kelvin(daily.getJSONArray("temperature_2m_max").optDouble(0, 0.0))
            todayMinTemp = kelvin(daily.getJSONArray("temperature_2m_min").optDouble(0, 0.0))
            windSpeed = current.optDouble("wind_speed_10m", 0.0).toFloat()
            windDirection = current.optDouble("wind_direction_10m", 0.0).toInt()
            precipProbability = probability(hourly?.optJSONArray("precipitation_probability")?.optDouble(0, 0.0) ?: 0.0)
            this.latitude = latitude
            this.longitude = longitude
            isCurrentLocation = 1
            sunRise = epochSeconds(daily.getJSONArray("sunrise").optString(0, ""), zone)
            sunSet = epochSeconds(daily.getJSONArray("sunset").optString(0, ""), zone)
            moonPhase = dailyMoonPhase(0)
        }

        for (i in 1 until minOf(daily.getJSONArray("weather_code").length(), FORECAST_DAYS)) {
            spec.forecasts.add(WeatherSpec.Daily().apply {
                conditionCode = condition(daily.getJSONArray("weather_code").optInt(i, 0))
                maxTemp = kelvin(daily.getJSONArray("temperature_2m_max").optDouble(i, 0.0))
                minTemp = kelvin(daily.getJSONArray("temperature_2m_min").optDouble(i, 0.0))
                precipProbability = probability(daily.optJSONArray("precipitation_probability_max")?.optDouble(i, 0.0) ?: 0.0)
                sunRise = epochSeconds(daily.getJSONArray("sunrise").optString(i, ""), zone)
                sunSet = epochSeconds(daily.getJSONArray("sunset").optString(i, ""), zone)
                moonPhase = dailyMoonPhase(i)
            })
        }

        if (hourly != null) {
            val times = hourly.optJSONArray("time")
            val count = times?.length() ?: 0
            for (i in 0 until count) {
                val timestamp = epochSeconds(times?.optString(i, ""), zone)
                if (timestamp <= now || spec.hourly.size >= 24) continue
                spec.hourly.add(WeatherSpec.Hourly().apply {
                    this.timestamp = timestamp
                    temp = kelvin(optDouble(hourly, "temperature_2m", i))
                    conditionCode = condition(hourly.optJSONArray("weather_code")?.optInt(i, 0) ?: 0)
                    humidity = hourly.optJSONArray("relative_humidity_2m")?.optInt(i, 0) ?: 0
                    windSpeed = optDouble(hourly, "wind_speed_10m", i).toFloat()
                    windDirection = optDouble(hourly, "wind_direction_10m", i).toInt()
                    precipProbability = probability(optDouble(hourly, "precipitation_probability", i))
                })
            }
        }
        return spec
    }

    private fun epochSeconds(value: String?, zone: ZoneId): Int = try {
        if (value.isNullOrBlank()) 0 else LocalDateTime.parse(value).atZone(zone).toEpochSecond().toInt()
    } catch (_: Exception) {
        0
    }

    internal fun moonPhaseDegrees(epochSeconds: Long): Int {
        if (epochSeconds <= 0) return 0
        val cycles = (epochSeconds - REFERENCE_NEW_MOON_EPOCH_SECONDS) / SYNODIC_MONTH_SECONDS
        val fraction = cycles - floor(cycles)
        return floor(fraction * 360.0).toInt().coerceIn(0, 359)
    }

    private fun mapWmoToOpenWeather(code: Int): Int = when (code) {
        0 -> 800
        1 -> 801
        2 -> 802
        3 -> 804
        45, 48 -> 741
        51, 53, 55 -> 301
        56, 57 -> 511
        61 -> 500
        63 -> 501
        65 -> 502
        66, 67 -> 511
        71, 77 -> 600
        73 -> 601
        75 -> 602
        80 -> 520
        81 -> 521
        82 -> 522
        85 -> 620
        86 -> 621
        95 -> 211
        96, 99 -> 212
        else -> 3200
    }

    sealed class FetchResult {
        data class Success(val spec: WeatherSpec) : FetchResult()
        data class Failure(val message: String) : FetchResult()
        data class RetryableFailure(val message: String) : FetchResult()
        object NoLocation : FetchResult()
    }
}
