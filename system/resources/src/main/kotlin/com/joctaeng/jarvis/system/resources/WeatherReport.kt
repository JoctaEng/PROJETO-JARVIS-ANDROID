package com.joctaeng.jarvis.system.resources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Clima (0.31): a resposta da API pública Open-Meteo (sem chave) vira o JSON curto que o Euno lê e fala:
 * {"cidade","agora":{"temp","sensacao","umidade","descricao"},"dias":[{"data","min","max","chuva","descricao"}]}.
 * Puro, para testar na JVM.
 */
object WeatherReport {
    private val json = Json { ignoreUnknownKeys = true }

    /** Endereço da previsão (Open-Meteo, documentação em open-meteo.com/en/docs). */
    fun forecastUrl(lat: Double, lon: Double, days: Int): String =
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(java.util.Locale.US, lat, lon) +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=${days.coerceIn(1, 7)}"

    /** Endereço da busca de cidade pelo nome (geocodificação do Open-Meteo). */
    fun geocodeUrl(city: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&format=json&name=" + java.net.URLEncoder.encode(city.trim(), "UTF-8")

    data class Place(val name: String, val lat: Double, val lon: Double)

    fun parsePlace(raw: String): Place? = runCatching {
        val first = (json.parseToJsonElement(raw).jsonObject["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val name = (first["name"] as? JsonPrimitive)?.content.orEmpty()
        val lat = (first["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (first["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        Place(name, lat, lon)
    }.getOrNull()

    /** WMO weather_code → descrição em português (tabela da documentação do Open-Meteo). */
    fun describe(code: Int?): String = when (code) {
        0 -> "céu limpo"
        1 -> "poucas nuvens"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "neblina"
        51, 53, 55 -> "garoa"
        56, 57 -> "garoa congelante"
        61 -> "chuva fraca"
        63 -> "chuva moderada"
        65 -> "chuva forte"
        66, 67 -> "chuva congelante"
        71, 73, 75, 77 -> "neve"
        80 -> "pancadas de chuva fracas"
        81 -> "pancadas de chuva"
        82 -> "pancadas de chuva fortes"
        85, 86 -> "pancadas de neve"
        95 -> "trovoadas"
        96, 99 -> "trovoadas com granizo"
        else -> ""
    }

    fun convert(raw: String, city: String): String? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val cur = root["current"] as? JsonObject
        val daily = root["daily"] as? JsonObject
        fun num(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.doubleOrNull
        fun arr(k: String) = (daily?.get(k) as? JsonArray)
        val times = arr("time")
        buildJsonObject {
            put("cidade", city)
            put("agora", buildJsonObject {
                num(cur, "temperature_2m")?.let { put("temp", Math.round(it)) }
                num(cur, "apparent_temperature")?.let { put("sensacao", Math.round(it)) }
                num(cur, "relative_humidity_2m")?.let { put("umidade", Math.round(it)) }
                put("descricao", describe(num(cur, "weather_code")?.toInt()))
            })
            put("dias", buildJsonArray {
                for (i in 0 until (times?.size ?: 0)) {
                    fun at(k: String) = (arr(k)?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
                    add(buildJsonObject {
                        put("data", (times!![i] as JsonPrimitive).content)
                        at("temperature_2m_min")?.let { put("min", Math.round(it)) }
                        at("temperature_2m_max")?.let { put("max", Math.round(it)) }
                        at("precipitation_probability_max")?.let { put("chuva", Math.round(it)) }
                        put("descricao", describe(at("weather_code")?.toInt()))
                    })
                }
            })
        }.toString()
    }.getOrNull()
}
