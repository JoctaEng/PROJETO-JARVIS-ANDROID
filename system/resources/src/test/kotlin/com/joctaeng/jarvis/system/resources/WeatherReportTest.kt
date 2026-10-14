package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeatherReportTest {
    private val sample = """
        {"latitude":-1.45,"longitude":-48.5,"current":{"time":"2026-10-14T15:00","temperature_2m":31.4,"apparent_temperature":35.2,"relative_humidity_2m":66,"weather_code":2},
         "daily":{"time":["2026-10-14","2026-10-15"],"weather_code":[80,61],"temperature_2m_max":[33.1,32.0],"temperature_2m_min":[24.6,24.0],"precipitation_probability_max":[60,85]}}
    """.trimIndent()

    @Test fun convertsOpenMeteo() {
        val out = WeatherReport.convert(sample, "Cidade Exemplo")
        assertNotNull(out)
        assertTrue(""""temp":31""" in out && """"sensacao":35""" in out && "parcialmente nublado" in out, out)
        assertTrue(""""chuva":85""" in out && "chuva fraca" in out && "pancadas de chuva fracas" in out, out)
    }

    @Test fun placeAndUrls() {
        val p = WeatherReport.parsePlace("""{"results":[{"name":"Belém","latitude":-1.45583,"longitude":-48.50444,"country":"Brasil"}]}""")
        assertEquals("Belém", p?.name)
        assertTrue(WeatherReport.forecastUrl(-1.4558, -48.5044, 2).contains("latitude=-1.4558&longitude=-48.5044"))
        assertTrue(WeatherReport.geocodeUrl("São Paulo").endsWith("name=S%C3%A3o+Paulo"))
    }
}
