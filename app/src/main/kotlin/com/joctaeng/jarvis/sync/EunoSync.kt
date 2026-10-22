package com.joctaeng.jarvis.sync

import android.Manifest
import android.accounts.Account
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.CalendarContract
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.mind.intent.ToolText
import com.joctaeng.jarvis.mind.intent.Weather
import com.joctaeng.jarvis.system.resources.AgendaFormatter
import com.joctaeng.jarvis.system.resources.AgendaPeriod
import com.joctaeng.jarvis.tools.GmailTools
import com.joctaeng.jarvis.tools.LifeTools
import com.joctaeng.jarvis.tools.readAgenda
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Sincronização contínua (0.31, pedido de 14/10: "sincronizado sempre, todos os dias, até eu tirar"): a cada ~1 hora,
 * e ao abrir a conversa se estiver velho, o Euno lê sozinho a agenda (a do Google chega pelo próprio Android), o clima e
 * os e-mails novos, e guarda um retrato. A IA recebe esse retrato no prompt e responde "como está meu dia" sem abrir nada.
 * Desliga em Ajustes (continuousSync). Nada sai do celular além das consultas de clima e Gmail.
 */
class EunoSync(private val context: Context) {
    data class Snapshot(
        val atMillis: Long = 0L,
        val agendaToday: String = "",
        val agendaTomorrow: String = "",
        val weather: String = "",
        val mails: String = "",
        val notes: List<String> = emptyList(),
    )

    private val app get() = JarvisApp.from(context)
    private val file = File(context.filesDir, "sync/retrato.json")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var snapshot: Snapshot = load()
        private set

    fun ageMinutes(now: Long = System.currentTimeMillis()): Long = if (snapshot.atMillis == 0L) Long.MAX_VALUE else (now - snapshot.atMillis) / 60_000

    /** Dispara uma sincronização sem esperar, se a última tem mais de [maxAgeMin] minutos. */
    fun refreshIfStale(reason: String, maxAgeMin: Long = 20) {
        if (!app.settings.continuousSync || ageMinutes() < maxAgeMin) return
        scope.launch { runCatching { run(reason) } }
    }

    /** Clima pedido pelo usuário também atualiza o retrato (sem gastar outra consulta). */
    fun noteWeather(json: String) {
        snapshot = snapshot.copy(weather = json)
        save(snapshot)
    }

    suspend fun run(reason: String): Snapshot = mutex.withLock {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val notes = mutableListOf<String>()
            requestCalendarSync()
            val zone = ZoneId.systemDefault()
            var today = ""
            var tomorrow = ""
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    for (p in listOf(AgendaPeriod.HOJE, AgendaPeriod.AMANHA)) {
                        val (from, to) = AgendaFormatter.range(p, zone, Instant.now())
                        val text = AgendaFormatter.format(readAgenda(context, from, to), p, zone, Instant.now())
                        if (p == AgendaPeriod.HOJE) today = text else tomorrow = text
                    }
                }.onFailure { notes += "agenda: ${it.message}" }
            } else notes += "agenda sem permissão"
            val weather = withTimeoutOrNull(15_000) { LifeTools.weather(context, null, 2, askPermission = false).getOrNull() }
                ?: snapshot.weather.also { notes += "clima não atualizado" }
            var mails = ""
            if (app.settings.gmailConnected) {
                mails = withTimeoutOrNull(15_000) {
                    val tool = GmailTools.all(context).first { it.name == "gmail_buscar" }
                    when (val r = tool.execute("""{"consulta":"is:unread in:inbox newer_than:2d","quantos":6}""", ToolContext("sync", "Sincronização contínua"))) {
                        is ToolResult.Success -> r.outputJson
                        else -> null
                    }
                } ?: snapshot.mails.also { notes += "e-mails não atualizados" }
            }
            val snap = Snapshot(System.currentTimeMillis(), today, tomorrow, weather, mails, notes)
            snapshot = snap
            save(snap)
            app.events.info("sync", "retrato atualizado ($reason) em ${System.currentTimeMillis() - t0} ms" + if (notes.isEmpty()) "" else "; ${notes.joinToString("; ")}")
            snap
        }
    }

    /** O retrato em poucas linhas para o prompt. [maxChars] menor no cérebro do celular. */
    fun liveSection(maxChars: Int): String {
        val s = snapshot
        if (s.atMillis == 0L || !app.settings.continuousSync) return ""
        val at = Instant.ofEpochMilli(s.atMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
        val parts = mutableListOf("Lido em $at.")
        if (s.agendaToday.isNotBlank()) parts += compactAgenda(s.agendaToday)
        if (s.agendaTomorrow.isNotBlank()) parts += compactAgenda(s.agendaTomorrow)
        Weather.parse(s.weather)?.let { parts += "Clima: " + Weather.brief(it) + (it.days.getOrNull(1)?.let { d -> " " + Weather.day(d, "Amanhã") }.orEmpty()) }
        if (app.settings.gmailConnected) {
            val list = ToolText.mails(s.mails)
            parts += if (list.isEmpty()) "E-mails: nenhum não lido nos últimos 2 dias."
            else "E-mails não lidos (${list.size}): " + list.take(5).joinToString("; ") { "${it.from.substringBefore('<').trim()}: ${it.subject.take(70)}" }
        } else parts += "Gmail: não conectado."
        return parts.joinToString("\n").take(maxChars)
    }

    /** Sem o [id] e com títulos curtos: a IA precisa do assunto, não do número do processo. */
    private fun compactAgenda(text: String): String = text.lines().joinToString("\n") { l ->
        l.replace(Regex("""\s*\[id \d+]"""), "").let { if (it.length > 110) it.take(110) + "…" else it }
    }

    /** Pede ao Android para sincronizar já as agendas do Google (o que o Euno grava aparece no Google Agenda em segundos). */
    fun requestCalendarSync() {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val accounts = mutableSetOf<Pair<String, String>>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE), null, null, null,
            )?.use { c -> while (c.moveToNext()) accounts += c.getString(0).orEmpty() to c.getString(1).orEmpty() }
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            accounts.filter { it.second == "com.google" && it.first.isNotBlank() }.forEach { (name, type) ->
                runCatching { ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras) }
            }
        }
    }

    private fun save(s: Snapshot) {
        runCatching {
            file.parentFile?.mkdirs()
            val o = JSONObject().put("at", s.atMillis).put("hoje", s.agendaToday).put("amanha", s.agendaTomorrow)
                .put("clima", s.weather).put("emails", s.mails)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.writeText(o.toString()); tmp.delete() }
        }
    }

    private fun load(): Snapshot = runCatching {
        val o = JSONObject(file.readText())
        Snapshot(o.optLong("at"), o.optString("hoje"), o.optString("amanha"), o.optString("clima"), o.optString("emails"))
    }.getOrDefault(Snapshot())

    companion object {
        private const val ACTION = "com.joctaeng.euno.SINCRONIZAR"

        private fun pending(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
            context, 4210, Intent(context, SyncReceiver::class.java).setAction(ACTION), flags or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Liga (ou desliga) o despertador da sincronização: ~30 min, sem precisar de alarme exato. */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val on = JarvisApp.from(context).settings.continuousSync
            if (!on) {
                pending(context, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
                return
            }
            val pi = pending(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000L, AlarmManager.INTERVAL_HOUR, pi,
            )
        }
    }
}

/** O despertador da sincronização tocou: lê tudo em até 25 s (o Android dá 60 s a um receptor em segundo plano). */
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = JarvisApp.from(context)
        if (!app.settings.continuousSync) return
        // Bateria (pedido de 22/10): com pouca carga ou economia de energia, pula esta leitura.
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        if ((level <= 15 && bm?.isCharging != true) || pm?.isPowerSaveMode == true) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000) { app.sync.run("a cada hora") }
            } catch (e: Exception) {
                app.events.warn("sync", "sincronização falhou: ${e.message}")
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
