package com.joctaeng.jarvis.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.control.EunoNotificationListener
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.system.resources.AgendaRequest
import com.joctaeng.jarvis.system.resources.EventTime
import com.joctaeng.jarvis.system.resources.HtmlText
import com.joctaeng.jarvis.ui.PermissionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

/**
 * "Mãos invisíveis" (pedido 71): fazem em segundo plano, sem abrir nenhum app na tela — agenda, pesquisa na web,
 * páginas, arquivos, copiar, contatos e notificações. As "mãos na tela" (tela_*) continuam existindo e cada lado
 * funciona sozinho; o cérebro escolhe a invisível primeiro e usa a tela quando o usuário quer ver.
 */
object BackgroundTools {
    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(
            AgendaSave(app), AgendaEdit(app), AgendaDelete(app), AgendaOpenDay(app),
            WebResearch(app), ReadPage(app), FileSave(app), FileList(app), FileRead(app),
            CopyText(app), ContactCreate(app), NotificationsRead(app),
        ) + GmailTools.all(app) + SkillTools.all(app) + TaskTools.all(app) + LifeTools.all(app)
    }
}

/** Base das ferramentas em segundo plano: argumentos já em JSON, trabalho fora da thread principal. */
internal abstract class BackgroundTool(
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
        return runCatching { run(args) }.getOrElse { ToolResult.Failure(it.message ?: it.javaClass.simpleName) }
    }

    protected abstract suspend fun run(args: JSONObject): ToolResult

    protected fun ok(vararg pairs: Pair<String, Any?>) = ToolResult.Success(JSONObject(pairs.toMap()).toString())

    protected fun text(args: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { args.optString(it).takeIf { v -> v.isNotBlank() } }.orEmpty().trim()

    protected fun missingPermission(permission: String, what: String): ToolResult? {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return null
        runCatching { context.startActivity(PermissionActivity.intent(context, permission)) }
        return ToolResult.Failure("Ainda sem permissão para $what. Abri o pedido na tela: diga ao usuário para tocar em Permitir e depois pedir de novo.")
    }
}

// ---------------------------------------------------------------- Agenda (sem abrir o app)

/** Agenda onde gravar: a principal da conta Google com permissão de escrita; senão, a primeira que aceite escrita. */
private fun writableCalendarId(context: Context): Long? {
    val projection = arrayOf(
        CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE,
    )
    val found = mutableListOf<Triple<Long, Boolean, String>>()
    context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val access = c.getInt(3)
            if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(4) != 1) continue
            found += Triple(c.getLong(0), c.getInt(1) == 1, c.getString(2).orEmpty())
        }
    }
    return (found.firstOrNull { it.second && it.third == "com.google" } ?: found.firstOrNull { it.second } ?: found.firstOrNull { it.third == "com.google" } ?: found.firstOrNull())?.first
}

private class AgendaSave(context: Context) : BackgroundTool(
    context, "agenda_salvar",
    "Cria um compromisso direto na agenda, EM SEGUNDO PLANO (sem abrir o app). Use para 'marca dentista amanhã às 15h'. " +
        "Data/hora absolutas a partir de 'Agora' do prompt. Para criar vendo na tela, use agenda_criar.",
    RiskLevel.WRITE_REVERSIBLE,
    """"titulo":{"type":"string"},"inicio":{"type":"string","description":"AAAA-MM-DD HH:mm (hora local)"},""" +
        """"duracao_min":{"type":"integer","minimum":5,"maximum":1440},"dia_inteiro":{"type":"boolean"},"local":{"type":"string"},"descricao":{"type":"string"},""" +
        """"forcar":{"type":"boolean","description":"true só se o usuário quer mesmo um segundo compromisso igual"}""",
    listOf("titulo", "inicio"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        missingPermission(Manifest.permission.WRITE_CALENDAR, "escrever na agenda")?.let { return@withContext it }
        missingPermission(Manifest.permission.READ_CALENDAR, "ler a agenda")?.let { return@withContext it }
        val title = text(args, "titulo")
        if (title.isBlank()) return@withContext ToolResult.Failure("informe o título")
        val zone = ZoneId.systemDefault()
        val start = EventTime.parseMillis(args.optString("inicio"), zone)
            ?: AgendaRequest.parseDate(args.optString("inicio"), Instant.now().atZone(zone).toLocalDate())?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            ?: return@withContext ToolResult.Failure("data/hora inválida; use AAAA-MM-DD HH:mm")
        val allDay = args.optBoolean("dia_inteiro", false)
        val minutes = args.optInt("duracao_min", 60).coerceIn(5, 1440)
        // Já existe um igual (mesmo título, mesmo horário)? Não cria outro (teste da 0.26.1: o mesmo pedido gerou os eventos 292 e 293).
        if (!args.optBoolean("forcar", false)) {
            sameEvent(title, start, allDay, zone)?.let { existing ->
                return@withContext ok(
                    "salvo" to true, "id" to existing, "titulo" to title, "ja_existia" to true,
                    "obs" to "esse compromisso JÁ estava na agenda (id $existing); não criei outro. Se houver um formulário de agenda aberto na tela, descarte-o.",
                )
            }
        }
        val calendarId = writableCalendarId(context) ?: return@withContext ToolResult.Failure("nenhuma agenda com permissão de escrita no celular")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            if (allDay) {
                // Dia inteiro: o Android exige meia-noite UTC e fuso UTC.
                val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val utc = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, utc)
                put(CalendarContract.Events.DTEND, utc + 86_400_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + minutes * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
            text(args, "local").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            text(args, "descricao").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@withContext ToolResult.Failure("a agenda não aceitou o compromisso")
        val id = ContentUris.parseId(uri)
        val app = JarvisApp.from(context)
        app.events.info("ferramentas", "agenda_salvar: evento $id criado em segundo plano")
        // 0.31: a agenda do Google do celular é a mesma do Google Agenda na web; pede a sincronização já, para aparecer nos dois na hora.
        app.sync.requestCalendarSync()
        val where = calendarLabel(calendarId)
        ok(
            "salvo" to true, "id" to id, "titulo" to title, "agenda" to where,
            "obs" to "compromisso salvo na agenda $where (sem abrir o app). Não salve de novo; se um formulário de agenda estiver aberto na tela, descarte-o.",
        )
    }

    /** "do Google" (conta Google: aparece no celular e no Google Agenda) ou "do celular" (só no aparelho). */
    private fun calendarLabel(calendarId: Long): String = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(CalendarContract.Calendars.ACCOUNT_TYPE), null, null, null,
        )?.use { c -> if (c.moveToFirst() && c.getString(0) == "com.google") "do Google" else "do celular" }
    }.getOrNull() ?: "do celular"

    /** Id de um compromisso com o mesmo título no mesmo horário (±1 min; dia inteiro: no mesmo dia), ou null. */
    private fun sameEvent(title: String, start: Long, allDay: Boolean, zone: ZoneId): Long? {
        val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val from = if (allDay) day.atStartOfDay(zone).toInstant().toEpochMilli() else start - 60_000L
        val to = if (allDay) from + 86_400_000L else start + 60_000L
        val wanted = normalizeTitle(title)
        val inInstances = runCatching {
            CalendarContract.Instances.query(
                context.contentResolver,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                from, to,
            )?.use { c ->
                var found: Long? = null
                while (found == null && c.moveToNext()) {
                    val begin = c.getLong(2)
                    val timeOk = allDay || kotlin.math.abs(begin - start) <= 60_000L
                    if (timeOk && normalizeTitle(c.getString(1).orEmpty()) == wanted) found = c.getLong(0)
                }
                found
            }
        }.getOrNull()
        if (inInstances != null) return inInstances
        // Teste da 0.29.0: o mesmo "futebol" foi criado duas vezes, porque a agenda do aparelho nem sempre preenche as
        // instâncias. Procura também nos eventos guardados. Dia inteiro: meia-noite UTC, como ao salvar.
        val (eventFrom, eventTo) = if (allDay) {
            val utc = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            utc to utc + 86_400_000L
        } else start - 60_000L to start + 60_000L
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(eventFrom.toString(), eventTo.toString()),
                null,
            )?.use { c ->
                var found: Long? = null
                while (found == null && c.moveToNext()) {
                    if (normalizeTitle(c.getString(1).orEmpty()) == wanted) found = c.getLong(0)
                }
                found
            }
        }.getOrNull()
    }

    private fun normalizeTitle(t: String): String =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
}

private class AgendaEdit(context: Context) : BackgroundTool(
    context, "agenda_editar",
    "Muda um compromisso da agenda em segundo plano, pelo id (o id aparece em agenda_consultar). Mande só o que muda.",
    RiskLevel.WRITE_REVERSIBLE,
    """"id":{"type":"integer"},"titulo":{"type":"string"},"inicio":{"type":"string","description":"AAAA-MM-DD HH:mm"},"duracao_min":{"type":"integer"},"local":{"type":"string"}""",
    listOf("id"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        missingPermission(Manifest.permission.WRITE_CALENDAR, "escrever na agenda")?.let { return@withContext it }
        val id = args.optLong("id", 0L).takeIf { it > 0 } ?: return@withContext ToolResult.Failure("informe o id do compromisso (veja com agenda_consultar)")
        val values = ContentValues()
        text(args, "titulo").takeIf { it.isNotBlank() }?.let { values.put(CalendarContract.Events.TITLE, it) }
        text(args, "local").takeIf { it.isNotBlank() }?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        args.optString("inicio").takeIf { it.isNotBlank() }?.let { s ->
            val start = EventTime.parseMillis(s, ZoneId.systemDefault()) ?: return@withContext ToolResult.Failure("data/hora inválida; use AAAA-MM-DD HH:mm")
            values.put(CalendarContract.Events.DTSTART, start)
            values.put(CalendarContract.Events.DTEND, start + args.optInt("duracao_min", 60).coerceIn(5, 1440) * 60_000L)
        }
        if (values.size() == 0) return@withContext ToolResult.Failure("nada para mudar")
        val n = context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null)
        if (n == 0) ToolResult.Failure("não achei o compromisso $id") else ok("alterado" to true, "id" to id)
    }
}

private class AgendaDelete(context: Context) : BackgroundTool(
    context, "agenda_apagar",
    "Apaga um compromisso da agenda pelo id (de agenda_consultar). Sempre pede confirmação ao usuário.",
    RiskLevel.CRITICAL,
    """"id":{"type":"integer"}""", listOf("id"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        missingPermission(Manifest.permission.WRITE_CALENDAR, "escrever na agenda")?.let { return@withContext it }
        val id = args.optLong("id", 0L).takeIf { it > 0 } ?: return@withContext ToolResult.Failure("informe o id do compromisso")
        val n = context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        if (n > 0) JarvisApp.from(context).sync.requestCalendarSync()
        if (n == 0) ToolResult.Failure("não achei o compromisso $id") else ok("apagado" to true, "id" to id)
    }
}

private class AgendaOpenDay(context: Context) : BackgroundTool(
    context, "agenda_abrir",
    "Abre o app da agenda NA TELA já no dia pedido (sem deslizar). Use para 'abre a agenda no dia 15', 'mostra o dia 20 na agenda'.",
    RiskLevel.READ,
    """"data":{"type":"string","description":"DD/MM ou AAAA-MM-DD (ex.: 15/10)"},""" +
        """"app":{"type":"string","description":"qual agenda: 'google' (Google Agenda) ou 'celular' (a agenda do aparelho, ex.: Xiaomi). Vazio = a padrão"}""",
    listOf("data"),
) {
    override suspend fun run(args: JSONObject): ToolResult {
        val zone = ZoneId.systemDefault()
        val day = AgendaRequest.parseDate(text(args, "data", "dia"), Instant.now().atZone(zone).toLocalDate())
            ?: return ToolResult.Failure("data não entendida; use DD/MM (ex.: 15/10)")
        val millis = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").let { ContentUris.appendId(it, millis) }.build()
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Dois apps chamados "Agenda" (Google e Xiaomi, teste da 0.26.1): o pedido escolhe qual abre.
        val wanted = text(args, "app", "agenda").lowercase()
        val pm = context.packageManager
        val handlers = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
        val pick = when {
            "google" in wanted -> handlers.firstOrNull { it.startsWith("com.google.") }
            wanted.isNotBlank() -> handlers.firstOrNull { !it.startsWith("com.google.") }
            else -> null
        }
        if (pick != null) intent.setPackage(pick)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) return ToolResult.Failure("nenhum app de agenda abriu essa data")
        EunoScreen.settle(2_500)
        val opened = pick ?: pm.resolveActivity(intent, 0)?.activityInfo?.packageName.orEmpty()
        val which = when {
            opened.startsWith("com.google.") -> "Google Agenda"
            opened.isBlank() || opened == "android" -> "a agenda padrão"
            else -> "agenda do celular ($opened)"
        }
        return ok("aberto" to "$which no dia ${day.dayOfMonth}/${day.monthValue}", "pacote" to opened, "obs" to "use tela_ler para ver os compromissos na tela")
    }
}

// ---------------------------------------------------------------- Web

private class WebResearch(context: Context) : BackgroundTool(
    context, "pesquisar_e_resumir",
    "Pesquisa na internet EM SEGUNDO PLANO (Google, pelo Gemini) e devolve a resposta com as fontes, sem abrir o navegador. " +
        "Use para notícias, preços, horários, fatos atuais, 'pesquisa sobre X'. Para mostrar no navegador, use pesquisar_na_web.",
    RiskLevel.READ,
    """"pergunta":{"type":"string"}""", listOf("pergunta"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val q = text(args, "pergunta", "consulta", "query", "texto")
        if (q.isBlank()) return@withContext ToolResult.Failure("informe a pergunta")
        val (_, model, key) = JarvisApp.from(context).brains.visionBrain()
            ?: return@withContext ToolResult.Failure("a pesquisa em segundo plano usa o Gemini: cadastre um cérebro Gemini com chave; ou use pesquisar_na_web (abre o navegador)")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "Pesquise e responda em português do Brasil, de forma objetiva e com números/datas quando houver: $q")))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key.trim())
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                return@withContext ToolResult.Failure("a pesquisa falhou (${conn.responseCode}): ${conn.errorStream?.bufferedReader()?.readText()?.take(160)}")
            }
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            val cand = root.optJSONArray("candidates")?.optJSONObject(0) ?: return@withContext ToolResult.Failure("a pesquisa voltou vazia")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val answer = (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.optJSONObject(it)?.optString("text").orEmpty() }.trim()
            val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            val sources = (0 until (chunks?.length() ?: 0)).mapNotNull { i ->
                chunks!!.optJSONObject(i)?.optJSONObject("web")?.let { w -> w.optString("title").ifBlank { w.optString("uri") } }
            }.distinct().take(5)
            if (answer.isBlank()) return@withContext ToolResult.Failure("a pesquisa não trouxe resposta")
            ok("resposta" to answer.take(4_000), "fontes" to JSONArray(sources))
        } finally {
            conn.disconnect()
        }
    }
}

private class ReadPage(context: Context) : BackgroundTool(
    context, "ler_pagina",
    "Lê o texto de uma página da web (http/https) em segundo plano, para resumir ou extrair informação.",
    RiskLevel.READ,
    """"url":{"type":"string"}""", listOf("url"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val url = text(args, "url", "link")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ToolResult.Failure("só leio endereços http ou https")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Euno")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext ToolResult.Failure("a página respondeu ${conn.responseCode}")
            // Lê no máximo ~400 mil caracteres (páginas enormes não travam o celular).
            val html = conn.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(16_384)
                while (sb.length < 400_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            val page = HtmlText.extract(html)
            ok("titulo" to page.title, "texto" to page.text, "cortado" to page.cut)
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------- Arquivos (Documentos/Euno)

private const val EUNO_DIR = "Documents/Euno/"

private class FileSave(context: Context) : BackgroundTool(
    context, "arquivo_salvar",
    "Salva um texto como arquivo no celular, em Documentos/Euno (notas, listas, plano de aula, resumo, tabela CSV), em segundo plano.",
    RiskLevel.WRITE_REVERSIBLE,
    """"nome":{"type":"string","description":"nome do arquivo sem extensão"},"conteudo":{"type":"string"},"formato":{"type":"string","enum":["txt","md","csv"]},""" +
        """"modo":{"type":"string","enum":["novo","acrescentar","substituir"],"description":"acrescentar = junta no fim de um arquivo que já existe (lista de compras, notas); substituir = reescreve; padrão novo"}""",
    listOf("nome", "conteudo"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val content = text(args, "conteudo", "texto")
        if (content.isBlank()) return@withContext ToolResult.Failure("conteúdo vazio")
        val ext = args.optString("formato").lowercase().takeIf { it in setOf("txt", "md", "csv") } ?: "txt"
        val base = text(args, "nome").replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "nota" }.take(80)
        val mode = args.optString("modo").lowercase()
        // Lista de compras e notas rápidas (skills da 0.27) crescem no MESMO arquivo, em vez de criar "Lista (1)", "Lista (2)"…
        if (mode == "acrescentar" || mode == "substituir") {
            eunoFiles(context).firstOrNull { it.first.equals("$base.$ext", ignoreCase = true) }?.let { (name, existing) ->
                val append = mode == "acrescentar"
                context.contentResolver.openOutputStream(existing, if (append) "wa" else "wt")?.use {
                    it.write((if (append) "\n" + content else content).toByteArray())
                } ?: return@withContext ToolResult.Failure("não consegui escrever em $name")
                return@withContext ok("salvo" to "$EUNO_DIR$name", "modo" to mode, "tamanho" to content.length)
            }
        }
        val mime = when (ext) { "csv" -> "text/csv"; "md" -> "text/markdown"; else -> "text/plain" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, EUNO_DIR)
        }
        val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: return@withContext ToolResult.Failure("o Android não deixou criar o arquivo")
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: return@withContext ToolResult.Failure("não consegui escrever")
        ok("salvo" to "$EUNO_DIR$base.$ext", "tamanho" to content.length)
    }
}

/** Arquivos que o Euno criou em Documentos/Euno: nome → uri. */
private fun eunoFiles(context: Context): List<Pair<String, Uri>> {
    val out = mutableListOf<Pair<String, Uri>>()
    val col = MediaStore.Files.getContentUri("external")
    context.contentResolver.query(
        col, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(EUNO_DIR), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
    )?.use { c ->
        while (c.moveToNext()) out += c.getString(1).orEmpty() to ContentUris.withAppendedId(col, c.getLong(0))
    }
    return out
}

private class FileList(context: Context) : BackgroundTool(
    context, "arquivo_listar", "Lista os arquivos que o Euno salvou em Documentos/Euno (mais novos primeiro).", RiskLevel.READ,
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val files = eunoFiles(context).map { it.first }
        ok("arquivos" to JSONArray(files.take(50)), "total" to files.size)
    }
}

private class FileRead(context: Context) : BackgroundTool(
    context, "arquivo_ler", "Lê um arquivo que o Euno salvou em Documentos/Euno (pelo nome ou parte dele).", RiskLevel.READ,
    """"nome":{"type":"string"}""", listOf("nome"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val wanted = normalize(text(args, "nome"))
        val file = eunoFiles(context).firstOrNull { normalize(it.first).contains(wanted) }
            ?: return@withContext ToolResult.Failure("não achei \"${args.optString("nome")}\" em Documentos/Euno (veja com arquivo_listar)")
        val content = context.contentResolver.openInputStream(file.second)?.use { it.readBytes().decodeToString() }.orEmpty()
        ok("nome" to file.first, "conteudo" to content.take(8_000), "cortado" to (content.length > 8_000))
    }
}

// ---------------------------------------------------------------- Copiar, contatos, notificações

private class CopyText(context: Context) : BackgroundTool(
    context, "copiar",
    "Copia um texto para a área de transferência do celular (o usuário cola onde quiser). Ler o que o usuário copiou não é possível.",
    RiskLevel.WRITE_REVERSIBLE,
    """"texto":{"type":"string"}""", listOf("texto"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.Main) {
        val t = text(args, "texto", "conteudo")
        if (t.isBlank()) return@withContext ToolResult.Failure("texto vazio")
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return@withContext ToolResult.Failure("área de transferência indisponível")
        cm.setPrimaryClip(ClipData.newPlainText("Euno", t))
        ok("copiado" to t.length)
    }
}

private class ContactCreate(context: Context) : BackgroundTool(
    context, "contato_criar", "Cria um contato no celular em segundo plano (nome, telefone e e-mail opcional).",
    RiskLevel.WRITE_REVERSIBLE,
    """"nome":{"type":"string"},"telefone":{"type":"string"},"email":{"type":"string"}""", listOf("nome", "telefone"),
) {
    override suspend fun run(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        missingPermission(Manifest.permission.WRITE_CONTACTS, "criar contatos")?.let { return@withContext it }
        val name = text(args, "nome")
        val phone = text(args, "telefone", "numero")
        if (name.isBlank() || phone.isBlank()) return@withContext ToolResult.Failure("informe nome e telefone")
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build(),
        )
        text(args, "email").takeIf { it.isNotBlank() }?.let { mail ->
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, mail).build()
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        ok("criado" to name)
    }
}

private class NotificationsRead(context: Context) : BackgroundTool(
    context, "notificacoes_ler",
    "Lê as notificações que chegaram no celular (WhatsApp, e-mail, banco...), em segundo plano. Nunca responde sozinho. " +
        "Use para 'o que chegou?', 'tenho mensagem nova?'. Filtre por app se o usuário disser.",
    RiskLevel.READ,
    """"app":{"type":"string","description":"parte do nome do app (opcional)"},"quantas":{"type":"integer","minimum":1,"maximum":30}""",
) {
    override suspend fun run(args: JSONObject): ToolResult {
        if (!EunoNotificationListener.isEnabled(context)) {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return ToolResult.Failure("O acesso às notificações do Euno está desligado. Abri a página: peça ao usuário para ligar \"Euno\" e pedir de novo.")
        }
        val filter = normalize(text(args, "app"))
        val items = EunoNotificationListener.recent()
            .filter { filter.isBlank() || normalize(it.appLabel).contains(filter) }
            .take(args.optInt("quantas", 10).coerceIn(1, 30))
        if (items.isEmpty()) return ToolResult.Success("Nenhuma notificação nova${if (filter.isBlank()) "" else " de \"${args.optString("app")}\""}.")
        return ToolResult.Success(items.joinToString("\n") { it.line() })
    }
}

/** Esperar a tela assentar depois de abrir algo (quando o serviço de acessibilidade está ligado). */
internal object EunoScreen {
    suspend fun settle(maxMs: Long) {
        val service = com.joctaeng.jarvis.control.EunoAccessibilityService.instance
        if (service != null) service.waitSettled(maxMs) else kotlinx.coroutines.delay(1_200)
    }
}
