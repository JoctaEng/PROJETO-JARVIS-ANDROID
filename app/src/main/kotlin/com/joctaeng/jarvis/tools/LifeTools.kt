package com.joctaeng.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.system.resources.WeatherReport
import com.joctaeng.jarvis.ui.PermissionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 0.31: clima (Open-Meteo, sem chave) e SMS em segundo plano (com confirmação). */
object LifeTools {
    fun all(context: Context): List<Tool> = listOf(WeatherTool(context), SmsSend(context))

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000 }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("o serviço de clima respondeu ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    /** Última posição conhecida (sem ligar o GPS): rede, "fused", passiva ou GPS, a mais recente. */
    private fun lastLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    private fun cityName(context: Context, loc: Location): String {
        if (!android.location.Geocoder.isPresent()) return ""
        return runCatching {
            android.location.Geocoder(context, java.util.Locale.forLanguageTag("pt-BR")).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }.orEmpty()
        }.getOrDefault("")
    }

    /**
     * Clima em JSON curto (ver [WeatherReport.convert]). Cidade dita > localização do celular > cidade guardada.
     * Usado pela ferramenta e pela sincronização contínua.
     */
    suspend fun weather(context: Context, cityAsked: String?, days: Int, askPermission: Boolean): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val app = JarvisApp.from(context)
            val wanted = cityAsked?.trim()?.takeIf { it.length >= 2 }
            val loc = if (wanted == null) lastLocation(context) else null
            val (place, lat, lon) = when {
                wanted != null || loc == null -> {
                    val name = wanted ?: app.settings.weatherCity.takeIf { it.isNotBlank() } ?: run {
                        if (askPermission && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            runCatching { context.startActivity(PermissionActivity.intent(context, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                        }
                        throw IllegalStateException(
                            "não sei onde o usuário está: abri o pedido de localização na tela (toque em Permitir) ou diga a cidade, por exemplo \"clima em Belém\"",
                        )
                    }
                    val p = WeatherReport.parsePlace(get(WeatherReport.geocodeUrl(name))) ?: throw IllegalStateException("não achei a cidade \"$name\"")
                    // A primeira cidade pedida vira a padrão de quem não deu a localização.
                    if (app.settings.weatherCity.isBlank()) app.settings.weatherCity = p.name
                    Triple(p.name, p.lat, p.lon)
                }
                else -> Triple(cityName(context, loc), loc.latitude, loc.longitude)
            }
            WeatherReport.convert(get(WeatherReport.forecastUrl(lat, lon, days)), place) ?: throw IllegalStateException("a previsão veio num formato que não entendi")
        }
    }
}

private class WeatherTool(context: Context) : BackgroundTool(
    context, "clima",
    "Clima agora e previsão (até 7 dias) com chance de chuva, EM SEGUNDO PLANO (sem abrir app nem navegador). Sem cidade = onde o " +
        "usuário está. Use para 'como está o tempo', 'vai chover amanhã?', 'clima em Belém'.",
    RiskLevel.READ,
    """"cidade":{"type":"string","description":"vazio = localização do celular"},"dias":{"type":"integer","minimum":1,"maximum":7}""",
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val out = LifeTools.weather(context, text(args, "cidade", "local"), args.optInt("dias", 2), askPermission = true)
            .getOrElse { return ToolResult.Failure("Clima: ${it.message ?: it.javaClass.simpleName}") }
        JarvisApp.from(context).sync.noteWeather(out)
        return ToolResult.Success(out)
    }
}

private class SmsSend(context: Context) : BackgroundTool(
    context, "sms_enviar",
    "Envia um SMS EM SEGUNDO PLANO (sem abrir app) para um contato da agenda ou número. Sempre pede confirmação ao usuário. " +
        "Escreva a mensagem na voz do usuário.",
    RiskLevel.CRITICAL,
    """"contato":{"type":"string","description":"nome do contato ou número"},"mensagem":{"type":"string"}""",
    listOf("contato", "mensagem"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        missingPermission(Manifest.permission.SEND_SMS, "enviar SMS")?.let { return@withContext it }
        val who = text(args, "contato", "numero", "para")
        val msg = text(args, "mensagem", "texto")
        if (who.isBlank() || msg.isBlank()) return@withContext ToolResult.Failure("informe o contato e a mensagem")
        val digits = who.filter { it.isDigit() }
        val (name, number) = if (digits.length >= 8 && digits.length >= who.count { it.isLetter() }) {
            who to who
        } else {
            missingPermission(Manifest.permission.READ_CONTACTS, "ler os contatos")?.let { return@withContext it }
            val found = runCatching { findContacts(context, who) }.getOrElse { return@withContext ToolResult.Failure("Não consegui ler os contatos: ${it.message}") }
            if (found.isEmpty()) return@withContext ToolResult.Failure("Nenhum contato com \"$who\".")
            val exact = found.entries.filter { normalize(it.key) == normalize(who) }
            val entry = if (found.size == 1) found.entries.first() else exact.singleOrNull()
                ?: return@withContext ToolResult.Failure("Mais de um contato combina com \"$who\": ${found.keys.joinToString(", ")}. Pergunte qual.")
            entry.key to (entry.value.firstOrNull() ?: return@withContext ToolResult.Failure("O contato ${entry.key} não tem telefone."))
        }
        val sms = context.getSystemService(SmsManager::class.java) ?: return@withContext ToolResult.Failure("este celular não envia SMS")
        val parts = sms.divideMessage(msg)
        runCatching {
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null) else sms.sendTextMessage(number, null, msg, null, null)
        }.getOrElse { return@withContext ToolResult.Failure("o Android não enviou o SMS: ${it.message}") }
        JarvisApp.from(context).events.info("ferramentas", "sms_enviar: ${msg.length} caracteres, ${parts.size} parte(s)")
        ok("enviado" to true, "para" to name, "partes" to parts.size)
    }
}
