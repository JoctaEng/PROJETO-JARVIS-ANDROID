package com.joctaeng.jarvis.tools

import android.Manifest
import android.content.ContentUris
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
import android.provider.CalendarContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.joctaeng.jarvis.system.resources.AgendaEvent
import com.joctaeng.jarvis.system.resources.AgendaFormatter
import com.joctaeng.jarvis.system.resources.AgendaPeriod
import java.time.Instant
import java.time.ZoneId
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.ui.PermissionActivity
import com.joctaeng.jarvis.device.DeviceState
import com.joctaeng.jarvis.system.resources.EventTime
import com.joctaeng.jarvis.system.resources.PhoneNumber
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Ferramentas nativas do Android da Fase 1 (roteiro 9.4: apps, alarmes/timers, lanterna, compartilhar, bateria/rede). */
object AndroidTools {
    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(
            OpenApp(app), ListApps(app), PhoneStatus(app), SetAlarm(app), SetTimer(app),
            Flashlight(app), ShareText(app), OpenLink(app), WebSearch(app), OpenMap(app), AgendaQuery(app), ContactsSearch(app),
            MemorySave(app), MemoryForget(app), WhatsAppMessage(app), AgendaCreate(app), DaySummary(app),
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

    /** Se a permissão faltar, abre o pedido na tela (sem a pessoa procurar nas configurações) e devolve o aviso para o cérebro. */
    protected fun missingPermission(permission: String, what: String): ToolResult? {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return null
        runCatching { context.startActivity(PermissionActivity.intent(context, permission)) }
        return ToolResult.Failure(
            "Ainda sem permissão para $what. Abri o pedido de permissão na tela do usuário: " +
                "diga a ele para tocar em Permitir e depois pedir de novo.",
        )
    }
}

internal fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

private data class Launchable(val label: String, val packageName: String)

/** Nome falado → pacote, para apps comuns cujo nome na lista pode variar. */
private val knownPackages = mapOf(
    "whatsapp" to "com.whatsapp",
    "zap" to "com.whatsapp",
    "whatsapp business" to "com.whatsapp.w4b",
    "whats business" to "com.whatsapp.w4b",
    "telegram" to "org.telegram.messenger",
    "instagram" to "com.instagram.android",
    "youtube" to "com.google.android.youtube",
    "gmail" to "com.google.android.gm",
    "chrome" to "com.android.chrome",
    "maps" to "com.google.android.apps.maps",
)

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
            ?: knownPackages[wanted]?.let { pkg ->
                // Apps conhecidos pelo pacote (ex.: WhatsApp Business), caso o nome na lista seja diferente.
                context.packageManager.getLaunchIntentForPackage(pkg)?.let { Launchable(args.optString("nome"), pkg) }
            }
        if (match == null) {
            val close = apps.map { it.label }.filter { label -> wanted.split(' ').any { w -> w.length >= 3 && w in normalize(label) } }.take(8)
            JarvisApp.from(context).events.warn(
                "ferramentas",
                "abrir_app: \"${args.optString("nome")}\" não encontrado entre ${apps.size} apps; parecidos: ${close.ifEmpty { listOf("nenhum") }.joinToString(", ")}",
            )
            return ToolResult.Failure(
                "não encontrei um app chamado \"${args.optString("nome")}\" (${apps.size} apps visíveis" +
                    (if (close.isNotEmpty()) "; parecidos: ${close.joinToString(", ")}" else "") + ")",
            )
        }
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
        // Modelos pequenos erram o nome do argumento: aceita variações comuns.
        val q = listOf("consulta", "query", "q", "busca", "pesquisa", "termo", "texto", "pergunta")
            .firstNotNullOfOrNull { args.optString(it).takeIf { v -> v.isNotBlank() } }.orEmpty()
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

/** Fase 2: lê a agenda do celular (Google Agenda e outras contas sincronizadas), só leitura. */
private class AgendaQuery(context: Context) : AndroidTool(
    context, "agenda_consultar",
    "Lê a agenda do celular (Google Agenda e outras) e lista os compromissos de hoje, amanhã ou dos próximos 7 dias. " +
        "Use para 'como está meu dia', 'tenho algo amanhã?', 'o que tenho esta semana'.",
    RiskLevel.READ,
    """"periodo":{"type":"string","enum":["hoje","amanha","semana"],"description":"hoje (padrão), amanha ou semana"}""",
) {
    override fun run(args: JSONObject): ToolResult {
        missingPermission(Manifest.permission.READ_CALENDAR, "ler a agenda")?.let { return it }
        val period = AgendaPeriod.parse(args.optString("periodo"))
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val (from, to) = AgendaFormatter.range(period, zone, now)
        val events = runCatching { readAgenda(context, from, to) }.getOrElse { return ToolResult.Failure("Não consegui ler a agenda: ${it.message}") }
        return ToolResult.Success(AgendaFormatter.format(events, period, zone, now))
    }
}

/** Fase 2: procura contatos do celular pelo nome (só leitura). */
private class ContactsSearch(context: Context) : AndroidTool(
    context, "contatos_buscar",
    "Procura um contato do celular pelo nome e devolve nome e telefones. Use para 'qual o telefone da Maria?'.",
    RiskLevel.READ,
    """"nome":{"type":"string","description":"nome ou parte do nome do contato"}""", listOf("nome"),
) {
    override fun run(args: JSONObject): ToolResult {
        missingPermission(Manifest.permission.READ_CONTACTS, "ler os contatos")?.let { return it }
        val query = args.optString("nome").trim()
        if (query.isBlank()) return ToolResult.Failure("informe o nome do contato")
        val found = runCatching { findContacts(context, query) }.getOrElse { return ToolResult.Failure("Não consegui ler os contatos: ${it.message}") }
        if (found.isEmpty()) return ToolResult.Success("Nenhum contato encontrado com \"$query\".")
        return ToolResult.Success(found.entries.take(8).joinToString("\n") { (name, numbers) -> "- $name: ${numbers.joinToString(", ")}" })
    }
}


/** Guarda um fato na memória de verdade (o Euno dizia "anotei" sem gravar). */
private class MemorySave(context: Context) : AndroidTool(
    context, "memoria_guardar",
    "Guarda na memória permanente um fato sobre o usuário (nomes, preferências, dados que ele pediu para lembrar). " +
        "Use quando ele pedir para anotar/lembrar/guardar/corrigir algo. Escreva o fato completo e com a grafia certa.",
    RiskLevel.WRITE_REVERSIBLE,
    """"fato":{"type":"string","description":"frase completa, ex.: A esposa de Joctã se chama Thaynara Neves Souza Galvão"}""", listOf("fato"),
) {
    override fun run(args: JSONObject): ToolResult {
        val fact = listOf("fato", "texto", "memoria", "informacao").firstNotNullOfOrNull { args.optString(it).takeIf { v -> v.isNotBlank() } }
            ?: return ToolResult.Failure("fato vazio")
        val app = JarvisApp.from(context)
        if (app.settings.privateMode) return ToolResult.Denied("Modo Privado ativo: nada é memorizado")
        val item = app.memory.add(fact.trim(), source = "conversa", reason = "pedido do usuário")
        app.events.info("memoria", "guardado pela ferramenta (${item.text.length} caracteres)")
        return ok("guardado" to item.text, "total" to app.memory.all().size)
    }
}

/** Apaga da memória o fato que mais combina com o trecho (para corrigir: apagar o errado e guardar o certo). */
private class MemoryForget(context: Context) : AndroidTool(
    context, "memoria_esquecer",
    "Apaga da memória o fato que mais combina com o trecho informado (use para corrigir uma informação errada antes de guardar a certa).",
    RiskLevel.WRITE_REVERSIBLE,
    """"trecho":{"type":"string","description":"palavras do fato a apagar"}""", listOf("trecho"),
) {
    override fun run(args: JSONObject): ToolResult {
        val query = listOf("trecho", "fato", "texto").firstNotNullOfOrNull { args.optString(it).takeIf { v -> v.isNotBlank() } }
            ?: return ToolResult.Failure("informe o trecho")
        val app = JarvisApp.from(context)
        val gone = app.memory.forget(query) ?: return ToolResult.Failure("nada na memória combina com \"$query\"")
        app.events.info("memoria", "apagado pela ferramenta (${gone.text.length} caracteres)")
        return ok("apagado" to gone.text)
    }
}

/** Lê os compromissos da agenda do celular entre [from] e [to] (milissegundos). Exige READ_CALENDAR. */
internal fun readAgenda(context: Context, from: Long, to: Long): List<AgendaEvent> {
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, from)
        ContentUris.appendId(it, to)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
    )
    val out = mutableListOf<AgendaEvent>()
    context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
        while (c.moveToNext() && out.size < 200) {
            out += AgendaEvent(
                title = c.getString(0).orEmpty().ifBlank { "(sem título)" },
                startMillis = c.getLong(1),
                endMillis = c.getLong(2),
                allDay = c.getInt(3) == 1,
                location = c.getString(4)?.ifBlank { null },
                calendar = c.getString(5),
            )
        }
    }
    return out
}

/** Contatos cujo nome contém [query]: nome → telefones. Exige READ_CONTACTS. */
internal fun findContacts(context: Context, query: String): Map<String, List<String>> {
    val found = linkedMapOf<String, MutableList<String>>()
    context.contentResolver.query(
        Phone.CONTENT_URI,
        arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
        "${Phone.DISPLAY_NAME} LIKE ?",
        arrayOf("%$query%"),
        "${Phone.DISPLAY_NAME} ASC",
    )?.use { c ->
        while (c.moveToNext() && found.size <= 8) {
            val name = c.getString(0).orEmpty()
            val number = c.getString(1).orEmpty()
            if (name.isNotBlank() && number.isNotBlank()) found.getOrPut(name) { mutableListOf() }.let { if (number !in it) it += number }
        }
    }
    return found
}

/** Abre a conversa do WhatsApp com o texto já escrito; quem toca em enviar é o usuário. */
private class WhatsAppMessage(context: Context) : AndroidTool(
    context, "whatsapp_mensagem",
    "Abre o WhatsApp na conversa com um contato, com a mensagem já escrita (o usuário toca em enviar). " +
        "Use para 'manda mensagem para a Thaynara dizendo que já saí'. Escreva a mensagem na voz do usuário, pronta para enviar.",
    RiskLevel.WRITE_REVERSIBLE,
    """"contato":{"type":"string","description":"nome do contato na agenda"},"mensagem":{"type":"string"},"business":{"type":"boolean","description":"true = usar o WhatsApp Business"}""",
    listOf("contato", "mensagem"),
) {
    override fun run(args: JSONObject): ToolResult {
        missingPermission(Manifest.permission.READ_CONTACTS, "ler os contatos")?.let { return it }
        val name = args.optString("contato").trim()
        val text = args.optString("mensagem").trim()
        if (name.isBlank() || text.isBlank()) return ToolResult.Failure("informe o contato e a mensagem")
        val found = runCatching { findContacts(context, name) }.getOrElse { return ToolResult.Failure("Não consegui ler os contatos: ${it.message}") }
        if (found.isEmpty()) return ToolResult.Failure("Nenhum contato com \"$name\".")
        if (found.size > 1) return ToolResult.Failure("Mais de um contato combina com \"$name\": ${found.keys.joinToString(", ")}. Pergunte qual.")
        val (contact, numbers) = found.entries.first()
        val number = numbers.firstNotNullOfOrNull { PhoneNumber.forWhatsApp(it) } ?: return ToolResult.Failure("O contato $contact não tem um telefone válido.")
        val pm = context.packageManager
        val installed = listOf("com.whatsapp", "com.whatsapp.w4b").filter { pm.getLaunchIntentForPackage(it) != null }
        val wanted = if (args.optBoolean("business")) "com.whatsapp.w4b" else "com.whatsapp"
        val pkg = installed.firstOrNull { it == wanted } ?: installed.firstOrNull()
        val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(text))
        val intent = Intent(Intent.ACTION_VIEW, uri).also { if (pkg != null) it.setPackage(pkg) }
        JarvisApp.from(context).events.info("ferramentas", "whatsapp_mensagem: abrindo conversa (app=${pkg ?: "padrão"}, texto ${text.length} caracteres)")
        return if (start(intent)) ok("aberto" to "conversa com $contact", "app" to (pkg ?: "navegador/padrão"), "obs" to "o usuário toca em enviar")
        else ToolResult.Failure("O Android não deixou abrir o WhatsApp.")
    }
}

/** Abre a tela de novo compromisso da agenda já preenchida; o usuário toca em salvar. */
private class AgendaCreate(context: Context) : AndroidTool(
    context, "agenda_criar",
    "Cria um compromisso na agenda: abre a agenda com título, data e hora preenchidos para o usuário salvar. " +
        "Descubra a data/hora absolutas a partir de 'Agora' do prompt (ex.: amanhã às 15h → data de amanhã).",
    RiskLevel.WRITE_REVERSIBLE,
    """"titulo":{"type":"string"},"inicio":{"type":"string","description":"AAAA-MM-DD HH:mm (hora local)"},"duracao_min":{"type":"integer","minimum":5,"maximum":1440},"local":{"type":"string"}""",
    listOf("titulo", "inicio"),
) {
    override fun run(args: JSONObject): ToolResult {
        val title = args.optString("titulo").trim()
        if (title.isBlank()) return ToolResult.Failure("informe o título")
        val start = EventTime.parseMillis(args.optString("inicio"), ZoneId.systemDefault())
            ?: return ToolResult.Failure("data/hora inválida; use AAAA-MM-DD HH:mm")
        val minutes = args.optInt("duracao_min", 60).coerceIn(5, 1440)
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + minutes * 60_000L)
        args.optString("local").takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        return if (start(intent)) ok("aberto" to "novo compromisso", "titulo" to title, "obs" to "o usuário toca em salvar")
        else ToolResult.Failure("Nenhum app de agenda disponível.")
    }
}

/** "Bom dia" e "Fechar o dia": reúne agenda, bateria e memórias do dia para o Euno narrar. */
private class DaySummary(context: Context) : AndroidTool(
    context, "resumo_do_dia",
    "Reúne os dados para 'bom dia' (manha) ou 'fechar o dia' (noite): hora, bateria, compromissos de hoje e de amanhã e " +
        "memórias recentes. Depois narre em poucas frases: na manhã, o que vem pela frente e o que merece atenção; à noite, " +
        "o que foi o dia, o que fica para amanhã, e pergunte se quer anotar pendências ou criar lembretes.",
    RiskLevel.READ,
    """"tipo":{"type":"string","enum":["manha","noite"]}""",
) {
    override fun run(args: JSONObject): ToolResult {
        missingPermission(Manifest.permission.READ_CALENDAR, "ler a agenda")?.let { return it }
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val night = args.optString("tipo").startsWith("n", ignoreCase = true)
        val parts = mutableListOf<String>()
        parts += "Tipo: ${if (night) "fechar o dia" else "bom dia"}"
        parts += "Bateria: ${DeviceState.batteryPercent(context)}%"
        for (period in listOf(AgendaPeriod.HOJE, AgendaPeriod.AMANHA)) {
            val (from, to) = AgendaFormatter.range(period, zone, now)
            val events = runCatching { readAgenda(context, from, to) }.getOrElse { return ToolResult.Failure("Não consegui ler a agenda: ${it.message}") }
            parts += AgendaFormatter.format(events, period, zone, now)
        }
        val memories = JarvisApp.from(context).memory.all().takeLast(5).map { it.text }
        if (memories.isNotEmpty()) parts += "Memórias recentes: " + memories.joinToString(" | ")
        return ToolResult.Success(parts.joinToString("\n\n"))
    }
}
