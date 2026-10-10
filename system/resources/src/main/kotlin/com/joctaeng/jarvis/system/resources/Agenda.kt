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
    /** Id do evento no Android (para editar/apagar sem abrir o app); 0 = desconhecido. */
    val id: Long = 0L,
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

    fun format(events: List<AgendaEvent>, period: AgendaPeriod, zone: ZoneId, now: Instant, limit: Int = 30): String =
        format(events, period.label, period.days > 1, zone, limit)

    fun format(events: List<AgendaEvent>, label: String, multiDay: Boolean, zone: ZoneId, limit: Int = 30): String {
        if (events.isEmpty()) return "Nenhum compromisso na agenda para $label."
        val sorted = events.sortedWith(compareBy({ dayOf(it, zone) }, { !it.allDay }, { it.startMillis }))
        val shown = sorted.take(limit)
        return buildString {
            appendLine("Agenda para $label:")
            var lastDay: LocalDate? = null
            shown.forEach { e ->
                val day = dayOf(e, zone)
                if (multiDay && day != lastDay) {
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
                if (e.id > 0) append(" [id ${e.id}]")
                appendLine()
            }
            if (sorted.size > shown.size) appendLine("…e mais ${sorted.size - shown.size} compromissos.")
        }.trimEnd()
    }

    private fun dayOf(e: AgendaEvent, zone: ZoneId): LocalDate =
        if (e.allDay) Instant.ofEpochMilli(e.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate()
}

/**
 * Qual trecho da agenda o cérebro pediu: um dia específico ("15/10", "2026-10-15", "dia 15"), hoje, amanhã, a semana
 * ou N dias. Antes só havia hoje/amanhã/semana e um "15/10" virava "hoje" em silêncio (o Euno dizia que o dia 15 estava
 * vazio). Pedido sem sentido agora dá erro, para o cérebro corrigir.
 */
object AgendaRequest {
    data class Window(val from: Long, val to: Long, val label: String, val multiDay: Boolean, val firstDay: LocalDate)

    private val ptBr = Locale.forLanguageTag("pt-BR")
    private val dayLabel = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", ptBr)

    fun resolve(periodo: String?, data: String?, dias: Int?, zone: ZoneId, now: Instant): Result<Window> {
        val today = now.atZone(zone).toLocalDate()
        val dateText = data?.trim().orEmpty()
        val periodText = periodo?.trim().orEmpty()
        val explicit = parseDate(dateText, today) ?: parseDate(periodText, today)
        val count = (dias ?: 0).coerceIn(0, 31)
        if (dateText.isNotEmpty() && explicit == null) {
            return Result.failure(IllegalArgumentException("data \"$dateText\" não entendida; use AAAA-MM-DD ou DD/MM (ex.: 15/10)"))
        }
        if (explicit != null) {
            val n = if (count > 0) count else 1
            val label = if (n == 1) explicit.format(dayLabel) else "${explicit.format(dayLabel)} e os ${n - 1} dias seguintes"
            return Result.success(window(explicit, n, label, zone))
        }
        val norm = normalize(periodText)
        val period = when {
            norm.isEmpty() || "hoje" in norm -> AgendaPeriod.HOJE
            "amanh" in norm -> AgendaPeriod.AMANHA
            "semana" in norm -> AgendaPeriod.SEMANA
            "mes" in norm -> null
            else -> return Result.failure(IllegalArgumentException("período \"$periodText\" não entendido; use hoje, amanha, semana, mes ou o campo data (ex.: 15/10)"))
        }
        if (period == null) return Result.success(window(today, if (count > 0) count else 30, "os próximos ${if (count > 0) count else 30} dias", zone))
        val first = today.plusDays(if (period.startsTomorrow) 1 else 0)
        val n = if (count > 0) count else period.days
        return Result.success(window(first, n, if (count > 0) "os próximos $n dias" else period.label, zone))
    }

    private fun window(first: LocalDate, days: Int, label: String, zone: ZoneId) = Window(
        first.atStartOfDay(zone).toInstant().toEpochMilli(),
        first.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
        label, days > 1, first,
    )

    /** "2026-10-15", "15/10", "15/10/2026", "15-10", "dia 15", "15". Dia sem mês = este mês, ou o próximo se já passou. */
    fun parseDate(text: String, today: LocalDate): LocalDate? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(t)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        Regex("""(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?""").find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            val base = runCatching { LocalDate.of(y ?: today.year, mo, d) }.getOrNull() ?: return null
            return if (y == null && base.isBefore(today.minusDays(60))) base.plusYears(1) else base
        }
        Regex("""^(?:dia\s+)?(\d{1,2})$""").find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            val thisMonth = runCatching { today.withDayOfMonth(d) }.getOrNull()
            return when {
                thisMonth != null && !thisMonth.isBefore(today) -> thisMonth
                else -> runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
            }
        }
        return null
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()
}
