package com.joctaeng.jarvis.system.resources

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Um compromisso da agenda do celular. Em eventos de dia inteiro, [startMillis] é meia-noite UTC (regra do Android). */
data class AgendaEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String? = null,
    val calendar: String? = null,
)

enum class AgendaPeriod(val label: String, val days: Int, val startsTomorrow: Boolean = false) {
    HOJE("hoje", 1),
    AMANHA("amanhã", 1, startsTomorrow = true),
    SEMANA("os próximos 7 dias", 7),
    ;

    companion object {
        fun parse(text: String?): AgendaPeriod {
            val t = Normalizer.normalize(text.orEmpty().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return when {
                "amanh" in t -> AMANHA
                "semana" in t || "7" in t -> SEMANA
                else -> HOJE
            }
        }
    }
}

/** Intervalo e texto da agenda para o cérebro ler e falar. Sem dependência do Android: testável na JVM. */
object AgendaFormatter {
    private val ptBr = Locale.forLanguageTag("pt-BR")
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE, dd/MM", ptBr)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", ptBr)

    /** [início, fim) em milissegundos: do começo do dia (de hoje ou de amanhã) até [AgendaPeriod.days] dias depois. */
    fun range(period: AgendaPeriod, zone: ZoneId, now: Instant): Pair<Long, Long> {
        val first = now.atZone(zone).toLocalDate().plusDays(if (period.startsTomorrow) 1 else 0)
        return first.atStartOfDay(zone).toInstant().toEpochMilli() to first.plusDays(period.days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun format(events: List<AgendaEvent>, period: AgendaPeriod, zone: ZoneId, now: Instant, limit: Int = 30): String {
        if (events.isEmpty()) return "Nenhum compromisso na agenda para ${period.label}."
        val sorted = events.sortedWith(compareBy({ dayOf(it, zone) }, { !it.allDay }, { it.startMillis }))
        val shown = sorted.take(limit)
        return buildString {
            appendLine("Agenda para ${period.label}:")
            var lastDay: LocalDate? = null
            shown.forEach { e ->
                val day = dayOf(e, zone)
                if (period.days > 1 && day != lastDay) {
                    appendLine("${day.format(dayFormat)}:")
                    lastDay = day
                }
                val whenText = if (e.allDay) "dia inteiro" else {
                    val s = Instant.ofEpochMilli(e.startMillis).atZone(zone).format(timeFormat)
                    val f = Instant.ofEpochMilli(e.endMillis).atZone(zone).format(timeFormat)
                    "$s–$f"
                }
                append("- $whenText ${e.title}")
                e.location?.takeIf { it.isNotBlank() }?.let { append(" (local: $it)") }
                appendLine()
            }
            if (sorted.size > shown.size) appendLine("…e mais ${sorted.size - shown.size} compromissos.")
        }.trimEnd()
    }

    private fun dayOf(e: AgendaEvent, zone: ZoneId): LocalDate =
        if (e.allDay) Instant.ofEpochMilli(e.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate()
}
