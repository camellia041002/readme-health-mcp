package nodomain.freeyourgadget.gadgetbridge.util.builtinweather

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinWeatherFetcherTest : TestBase() {
    @Test
    fun parsesOpenMeteoCurrentAndForecast() {
        val json = JSONObject(
            """
            {
              "timezone":"Asia/Singapore",
              "current":{"time":"2026-09-06T12:00","temperature_2m":30.0,"relative_humidity_2m":70,"apparent_temperature":34.0,"weather_code":1,"wind_speed_10m":12.0,"wind_direction_10m":90},
              "daily":{"time":["2026-09-06","2026-09-07","2026-09-08"],"weather_code":[1,61,3],"temperature_2m_max":[32,31,30],"temperature_2m_min":[26,25,24],"precipitation_probability_max":[20,60,10],"sunrise":["2026-09-06T06:58","2026-09-07T06:58","2026-09-08T06:58"],"sunset":["2026-09-06T19:06","2026-09-07T19:06","2026-09-08T19:06"]},
              "hourly":{"time":["2026-09-06T12:00","2026-09-06T13:00","2026-09-06T14:00"],"temperature_2m":[30.0,31.0,32.0],"relative_humidity_2m":[70,69,68],"precipitation_probability":[20,30,40],"weather_code":[1,2,3],"wind_speed_10m":[12.0,13.0,14.0],"wind_direction_10m":[90,100,110]}
            }
            """.trimIndent()
        )

        val spec = BuiltinWeatherFetcher.parse(json, "当前位置", 1.3f, 103.8f, getContext())

        assertEquals("当前位置", spec.location)
        assertEquals(303, spec.currentTemp)
        assertEquals(801, spec.currentConditionCode)
        assertEquals(70, spec.currentHumidity)
        assertEquals(2, spec.forecasts.size)
        assertEquals(500, spec.forecasts[0].conditionCode)
        assertEquals(2, spec.hourly.size)
        assertEquals(1788670800, spec.hourly[0].timestamp)
        assertTrue(spec.hourly.all { it.timestamp > spec.timestamp })
        assertTrue(spec.sunRise > 0)
        assertTrue(spec.sunSet > spec.sunRise)
        assertTrue(spec.moonPhase in 0..359)
        assertTrue(spec.forecasts.all { it.moonPhase in 0..359 })
    }

    @Test
    fun preservesSevenFutureDaysForHuaweiWithoutDuplicatingLastDay() {
        val daily = JSONObject()
        for (key in listOf("time", "weather_code", "temperature_2m_max", "temperature_2m_min", "sunrise", "sunset")) {
            daily.put(key, JSONArray())
        }
        // Include an extra day to verify the cap as well as the eighth day's own values.
        for (i in 0..8) {
            val date = LocalDate.of(2026, 9, 11).plusDays(i.toLong())
            daily.getJSONArray("time").put(date.toString())
            daily.getJSONArray("weather_code").put(0)
            daily.getJSONArray("temperature_2m_max").put(25 + i)
            daily.getJSONArray("temperature_2m_min").put(15 + i)
            daily.getJSONArray("sunrise").put("${date}T06:00")
            daily.getJSONArray("sunset").put("${date}T18:00")
        }
        val json = JSONObject().put("timezone", "Asia/Shanghai")
            .put("current", JSONObject().put("time", "2026-09-11T13:30"))
            .put("daily", daily)
        val spec = BuiltinWeatherFetcher.parse(json, "test", 30f, 121f, getContext())
        assertEquals(8, BuiltinWeatherFetcher.FORECAST_DAYS)
        assertEquals(7, spec.forecasts.size)
        assertEquals(305, spec.forecasts.last().maxTemp)
        assertEquals(7 * 86400, spec.forecasts.last().sunRise - spec.sunRise)
        assertTrue(spec.forecasts.last().sunSet > spec.forecasts.last().sunRise)
    }

    @Test
    fun calculatesMoonPhaseFromKnownNewMoon() {
        assertEquals(0, BuiltinWeatherFetcher.moonPhaseDegrees(947182440L))
        assertEquals(180, BuiltinWeatherFetcher.moonPhaseDegrees(947182440L + 1275722L))
    }
}
