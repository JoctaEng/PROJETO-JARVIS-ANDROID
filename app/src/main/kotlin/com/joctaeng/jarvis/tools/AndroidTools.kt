package com.joctaeng.jarvis.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.StatFs
import android.provider.AlarmClock
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Ferramentas nativas do Android da Fase 1 (roteiro 9.4: apps, alarmes/timers, lanterna, compartilhar, bateria/rede). */
object AndroidTools {
    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(
            OpenApp(app), ListApps(app), PhoneStatus(app), SetAlarm(app), SetTimer(app),
            Flashlight(app), ShareText(app), OpenLink(app), WebSearch(app), OpenMap(app),
        )
    }
}

private abstract class AndroidTool(
    protected val context: Context,
    final override val name: String,
    final override val description: String,
    final override val risk: RiskLevel,
    properties: String = "",
    required: List<String> = emptyList(),
) : Tool {
    final override val inputSchemaJson: String =
        """{"type":"object","properties":{$properties}${if (required.isEmpty()) "" else ",\"required\":" + JSONArray(required)}}"""

    final override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val args = runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() }
        return run(args)
    }

    protected abstract fun run(args: JSONObject): ToolResult

    protected fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    protected fun ok(vararg pairs: Pair<String, Any?>) = ToolResult.Success(JSONObject(pairs.toMap()).toString())
}

internal fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

private data class Launchable(val label: String, val packageName: String)

private fun launchables(context: Context): List<Launchable> {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
        .map { Launchable(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private class OpenApp(context: Context) : AndroidTool(
    context, "abrir_app", "Abre um app instalado pelo nome (ex.: EduMath, WhatsApp, Câmera).", RiskLevel.READ,
    """"nome":{"type":"string","description":"nome do app como aparece no celular"}""", listOf("nome"),
) {
    override fun run(args: JSONObject): ToolResult {
        val wanted = normalize(args.optString("nome"))
        if (wanted.isBlank()) return ToolResult.Failure("informe o nome do app")
        val apps = launchables(context)
        val match = apps.firstOrNull { normalize(it.label) == wanted }
            ?: apps.firstOrNull { normalize(it.label).startsWith(wanted) }
            ?: apps.firstOrNull { wanted in normalize(it.label) }
            ?: return ToolResult.Failure("não encontrei um app chamado \"${args.optString("nome")}\"")
        val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return ToolResult.Failure("${match.label} não pode ser aberto diretamente")
        return if (start(intent)) ok("aberto" to match.label) else ToolResult.Failure("o Android não deixou abrir ${match.label}")
    }
}

private class ListApps(context: Context) : AndroidTool(
    context, "listar_apps", "Lista os apps instalados (opcionalmente filtrando por parte do nome).", RiskLevel.READ,
    """"filtro":{"type":"string"}""",
) {
    override fun run(args: JSONObject): ToolResult {
        val filter = normalize(args.optString("filtro"))
        val names = launchables(context).map { it.label }.filter { filter.isBlank() || filter in normalize(it) }
        return ok("apps" to JSONArray(names.take(60)), "total" to names.size)
    }
}

private class PhoneStatus(context: Context) : AndroidTool(
    context, "estado_do_celular", "Bateria (nível e se está carregando), conexão (Wi-Fi, dados ou nenhuma) e espaço livre.", RiskLevel.READ,
) {
    override fun run(args: JSONObject): ToolResult {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging == true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "sem conexão"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dados móveis"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "outra"
        }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val free = runCatching { StatFs(context.filesDir.path).availableBytes / (1L shl 30) }.getOrDefault(-1)
        return ok("bateria_percentual" to level, "carregando" to charging, "conexao" to network, "internet_funcionando" to internet, "espaco_livre_gb" to free)
    }
}

private class SetAlarm(context: Context) : AndroidTool(
    context, "criar_alarme", "Cria um alarme no app Relógio.", RiskLevel.WRITE_REVERSIBLE,
    """"hora":{"type":"integer","minimum":0,"maximum":23},"minuto":{"type":"integer","minimum":0,"maximum":59},""" +
        """"rotulo":{"type":"string"},"dias":{"type":"array","items":{"type":"string","enum":["dom","seg","ter","qua","qui","sex","sab"]},"description":"repetir nesses dias; vazio = só uma vez"}""",
    listOf("hora", "minuto"),
) {
    override fun run(args: JSONObject): ToolResult {
        val hour = args.optInt("hora", -1)
        val minute = args.optInt("minuto", -1)
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.Failure("horário inválido")
        val days = args.optJSONArray("dias")?.let { arr -> (0 until arr.length()).mapNotNull { DAYS[arr.optString(it)] } }.orEmpty()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("rotulo").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        return if (start(intent)) ok("alarme" to "%02d:%02d".format(hour, minute), "dias_de_repeticao" to days.size)
        else ToolResult.Failure("nenhum app de relógio aceitou criar o alarme")
    }

    companion object {
        val DAYS = mapOf(
            "dom" to java.util.Calendar.SUNDAY, "seg" to java.util.Calendar.MONDAY, "ter" to java.util.Calendar.TUESDAY,
            "qua" to java.util.Calendar.WEDNESDAY, "qui" to java.util.Calendar.THURSDAY, "sex" to java.util.Calendar.FRIDAY,
            "sab" to java.util.Calendar.SATURDAY,
        )
    }
}

private class SetTimer(context: Context) : AndroidTool(
    context, "criar_timer", "Inicia um timer (contagem regressiva) no app Relógio.", RiskLevel.WRITE_REVERSIBLE,
    """"segundos":{"type":"integer","minimum":1,"maximum":86400},"rotulo":{"type":"string"}""", listOf("segundos"),
) {
    override fun run(args: JSONObject): ToolResult {
        val seconds = args.optInt("segundos", 0)
        if (seconds !in 1..86_400) return ToolResult.Failure("duração inválida")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("rotulo").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (start(intent)) ok("timer_segundos" to seconds) else ToolResult.Failure("nenhum app de relógio aceitou o timer")
    }
}

private class Flashlight(context: Context) : AndroidTool(
    context, "lanterna", "Liga ou desliga a lanterna.", RiskLevel.WRITE_REVERSIBLE,
    """"ligar":{"type":"boolean"}""", listOf("ligar"),
) {
    override fun run(args: JSONObject): ToolResult {
        val on = args.optBoolean("ligar", true)
        val cm = context.getSystemService(CameraManager::class.java) ?: return ToolResult.Failure("câmera indisponível")
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ToolResult.Failure("este celular não tem flash")
        return runCatching { cm.setTorchMode(id, on) }
            .fold({ ok("lanterna" to if (on) "ligada" else "desligada") }, { ToolResult.Failure("a câmera está em uso por outro app") })
    }
}

private class ShareText(context: Context) : AndroidTool(
    context, "compartilhar_texto", "Abre a tela de compartilhar do Android com um texto; o usuário escolhe o app e envia.", RiskLevel.WRITE_REVERSIBLE,
    """"texto":{"type":"string"}""", listOf("texto"),
) {
    override fun run(args: JSONObject): ToolResult {
        val text = args.optString("texto")
        if (text.isBlank()) return ToolResult.Failure("texto vazio")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return if (start(Intent.createChooser(send, "Compartilhar"))) ok("tela_de_compartilhar" to "aberta; o usuário escolhe o destino")
        else ToolResult.Failure("não consegui abrir o compartilhamento")
    }
}

private class OpenLink(context: Context) : AndroidTool(
    context, "abrir_link", "Abre um endereço da web (http/https) no navegador.", RiskLevel.READ,
    """"url":{"type":"string"}""", listOf("url"),
) {
    override fun run(args: JSONObject): ToolResult {
        val uri = Uri.parse(args.optString("url").trim())
        if (uri.scheme != "https" && uri.scheme != "http") return ToolResult.Failure("só abro endereços http ou https")
        return if (start(Intent(Intent.ACTION_VIEW, uri))) ok("aberto" to uri.toString()) else ToolResult.Failure("nenhum navegador disponível")
    }
}

private class WebSearch(context: Context) : AndroidTool(
    context, "pesquisar_na_web", "Abre uma pesquisa na web no navegador.", RiskLevel.READ,
    """"consulta":{"type":"string"}""", listOf("consulta"),
) {
    override fun run(args: JSONObject): ToolResult {
        val q = args.optString("consulta")
        if (q.isBlank()) return ToolResult.Failure("consulta vazia")
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))
        return if (start(Intent(Intent.ACTION_VIEW, uri))) ok("pesquisa" to q) else ToolResult.Failure("nenhum navegador disponível")
    }
}

private class OpenMap(context: Context) : AndroidTool(
    context, "abrir_mapa", "Mostra um lugar no mapa ou inicia a navegação até ele.", RiskLevel.READ,
    """"destino":{"type":"string"},"navegar":{"type":"boolean","description":"true = iniciar rota"}""", listOf("destino"),
) {
    override fun run(args: JSONObject): ToolResult {
        val place = args.optString("destino")
        if (place.isBlank()) return ToolResult.Failure("destino vazio")
        val uri = if (args.optBoolean("navegar")) Uri.parse("google.navigation:q=" + Uri.encode(place)) else Uri.parse("geo:0,0?q=" + Uri.encode(place))
        val fallback = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(place))
        return if (start(Intent(Intent.ACTION_VIEW, uri)) || start(Intent(Intent.ACTION_VIEW, fallback))) ok("mapa" to place)
        else ToolResult.Failure("nenhum app de mapas disponível")
    }
}
