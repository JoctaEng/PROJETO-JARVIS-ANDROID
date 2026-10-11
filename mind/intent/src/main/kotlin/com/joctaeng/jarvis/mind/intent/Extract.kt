package com.joctaeng.jarvis.mind.intent

import java.time.DayOfWeek
import java.time.LocalDate

/** "dia 15", "amanhã", "semana que vem": [start] e quantos [days] ([kind]: day, week, nextweek, month, nextmonth, weekend). */
data class DateSlot(val start: LocalDate, val days: Int = 1, val kind: String = "day", val spoken: String = "")

/** Hora do dia. [ambiguous]: "às 5" sem manhã/tarde/noite (pode ser 5 ou 17). */
data class TimeSlot(val hour: Int, val minute: Int, val periodGiven: Boolean = false, val ambiguous: Boolean = false)

/** "30 minutos", "2 horas": [cue] é a palavra antes ("daqui a", "em", "por", "de"...), que diz se é daqui a quanto tempo ou quanto dura. */
data class DurSlot(val seconds: Int, val cue: String = "")

data class MoneySlot(val cents: Long)

/** "todo dia", "de segunda a sexta", "toda terça": quais dias da semana (1 = segunda … 7 = domingo). */
data class RecSlot(val days: Set<Int>, val spoken: String)

/**
 * O pedido depois da extração: datas, horas, durações e valores saem do texto e viram marcadores
 * (`xdatax`, `xtimex`, `xdurx`, `xmoneyx`) no [skeleton], que é o que os modelos de frase casam.
 */
data class Extracted(
    val toks: List<Tok>,
    val date: DateSlot?,
    val time: TimeSlot?,
    val dur: DurSlot?,
    val money: MoneySlot?,
    val part: String?,
    val rec: RecSlot? = null,
    /** A fala como foi dita (para "sim"/"não" e textos livres). */
    val raw: String = "",
) {
    val skeleton: String get() = PtText.join(toks)
    val numbers: List<Int> get() = toks.filter { it.norm.all(Char::isDigit) && it.norm.length in 1..6 }.map { it.norm.toInt() }
}

internal class Toks(var list: MutableList<Tok>) {
    /** Troca o primeiro trecho que casa com [regex] e começa/termina em fronteira de palavra. [make] devolve o marcador (ou [DROP] para apagar, ou null para ignorar). */
    fun replaceFirst(regex: Regex, make: (MatchResult, String) -> Tok?): Boolean {
        val joined = list.joinToString(" ") { it.norm }
        val starts = IntArray(list.size)
        val ends = IntArray(list.size)
        var pos = 0
        for ((i, t) in list.withIndex()) {
            starts[i] = pos
            pos += t.norm.length
            ends[i] = pos
            pos += 1
        }
        var from = 0
        while (from <= joined.length) {
            val m = regex.find(joined, from) ?: return false
            val s = starts.indexOf(m.range.first)
            val e = ends.indexOf(m.range.last + 1)
            if (m.value.isEmpty() || s < 0 || e < 0 || e < s) {
                from = m.range.first + 1
                continue
            }
            val repl = make(m, joined.substring(0, m.range.first).trimEnd())
            if (repl == null) {
                from = m.range.first + 1
                continue
            }
            val orig = list.subList(s, e + 1).joinToString(" ") { it.orig }
            list.subList(s, e + 1).clear()
            if (repl !== DROP) list.add(s, Tok(repl.norm, orig))
            return true
        }
        return false
    }

    companion object {
        val DROP = Tok("", "")
    }
}

object Extract {
    private val months = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    private val weekdays = mapOf(
        "segunda" to DayOfWeek.MONDAY, "terca" to DayOfWeek.TUESDAY, "quarta" to DayOfWeek.WEDNESDAY, "quinta" to DayOfWeek.THURSDAY,
        "sexta" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY, "domingo" to DayOfWeek.SUNDAY,
    )
    private val durCues = listOf("daqui a", "daqui", "dentro de", "durante", "cerca de", "uns", "em", "por", "de", "para", "pra", "com", "timer", "temporizador", "cronometro", "regressiva")
    private const val PERIOD = "(?:da manha|da tarde|da noite|da madrugada)"
    private const val AT = "(?:(?:as|ao|pras|pelas|das|ate as|depois das|antes das|por volta das|umas) )?"

    fun of(raw: String, today: LocalDate): Extracted = of(PtText.tokenize(raw), today)

    fun of(tokens: List<Tok>, today: LocalDate): Extracted {
        val t = Toks(tokens.toMutableList())
        var money: MoneySlot? = null
        var dur: DurSlot? = null
        var time: TimeSlot? = null
        var date: DateSlot? = null
        var part: String? = null

        // ---- dinheiro: "50 reais", "152,30 reais", "2 reais e 50 centavos", "75 centavos"
        t.replaceFirst(Regex("""(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}))? (?:reais|real|conto|contos|pau|paus)(?: e (\d{1,2}) centavos?)?""")) { m, _ ->
            val reais = m.groupValues[1].replace(".", "").toLongOrNull() ?: return@replaceFirst null
            val cents = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { if (it.length == 1) it.toLong() * 10 else it.toLong() }
                ?: m.groupValues[3].toLongOrNull() ?: 0L
            money = MoneySlot(reais * 100 + cents)
            Tok("xmoneyx", "")
        }
        if (money == null) t.replaceFirst(Regex("""(\d+) centavos?""")) { m, _ ->
            money = MoneySlot(m.groupValues[1].toLong())
            Tok("xmoneyx", "")
        }

        // dinheiro sem "reais": "152,30" (sempre com centavos) ou o número logo depois de "despesa de", "gasto de", "valor de"...
        if (money == null) t.replaceFirst(Regex("""(\d{1,3}(?:\.\d{3})+,\d{2}|\d+,\d{2})""")) { m, _ ->
            val (r, c) = m.groupValues[1].replace(".", "").split(',')
            money = MoneySlot(r.toLong() * 100 + c.toLong())
            Tok("xmoneyx", "")
        }
        if (money == null) t.replaceFirst(Regex("""(?:despesa|gasto|receita|entrada|saida|valor|pagamento|compra|lancamento) (?:de |no valor de )?(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}))?(?= |$)""")) { m, _ ->
            val reais = m.groupValues[1].replace(".", "").toLongOrNull() ?: return@replaceFirst null
            val cents = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { if (it.length == 1) it.toLong() * 10 else it.toLong() } ?: 0L
            money = MoneySlot(reais * 100 + cents)
            Tok("xmoneyx", "")
        }

        // ---- duração: "30 minutos", "daqui a 2 horas", "uma hora e meia", "meia hora"
        t.replaceFirst(Regex("""meia hora""")) { _, before ->
            dur = DurSlot(1800, cueOf(before))
            Tok("xdurx", "")
        }
        if (dur == null) t.replaceFirst(Regex("""(\d+) (segundos?|seg|minutos?|min|horas?)(?: e (meia|\d+(?: minutos?| min)?))?""")) { m, before ->
            val n = m.groupValues[1].toIntOrNull() ?: return@replaceFirst null
            val unit = m.groupValues[2]
            val cue = cueOf(before)
            // "2 horas" sem uma palavra de duração antes ("às 2 horas") é hora do dia, não duração
            if (unit.startsWith("hora") && cue.isEmpty()) return@replaceFirst null
            var secs = when {
                unit.startsWith("seg") -> n
                unit.startsWith("min") -> n * 60
                else -> n * 3600
            }
            val tail = m.groupValues[3]
            if (tail == "meia") secs += if (unit.startsWith("hora")) 1800 else 30
            else if (tail.isNotEmpty()) tail.takeWhile(Char::isDigit).toIntOrNull()?.let { secs += it * 60 }
            dur = DurSlot(secs, cue)
            Tok("xdurx", "")
        }

        // ---- hora do dia (do mais específico ao mais solto)
        fun period(g: String): String? = g.takeIf { it.isNotEmpty() }?.removePrefix("da ")
        fun mk(h0: Int, min: Int, per: String?, strict24: Boolean): TimeSlot? {
            if (h0 !in 0..24 || min !in 0..59) return null
            var h = h0 % 24
            val given = per != null
            when (per) {
                "tarde" -> if (h in 1..11) h += 12
                "noite" -> if (h in 1..11) h += 12 else if (h == 12) h = 0
                "madrugada" -> if (h == 12) h = 0
                else -> Unit
            }
            return TimeSlot(h, min, given, ambiguous = !given && !strict24 && h0 in 1..12)
        }
        fun minuteWord(s: String): Int? = when {
            s == "meia" -> 30
            s == "um quarto" -> 15
            s.isEmpty() -> 0
            else -> s.toIntOrNull()
        }
        // "quinze para as cinco" = 4:45
        t.replaceFirst(Regex("""(\d{1,2}) para (?:as|a) (\d{1,2})(?: horas?)?(?: ($PERIOD))?""")) { m, _ ->
            val n = m.groupValues[1].toInt()
            val h = m.groupValues[2].toInt()
            if (n !in 1..59 || h !in 0..24) return@replaceFirst null
            time = mk(if (h == 0) 23 else h - 1, 60 - n, period(m.groupValues[3]), false)
            if (time == null) null else Tok("xtimex", "")
        }
        if (time == null) t.replaceFirst(Regex("""$AT(\d{1,2}):(\d{2})(?: ($PERIOD))?""")) { m, _ ->
            time = mk(m.groupValues[1].toInt(), m.groupValues[2].toInt(), period(m.groupValues[3]), m.groupValues[1].length == 2 && m.groupValues[1].startsWith("0") || m.groupValues[1].toInt() > 12)
            if (time == null) null else Tok("xtimex", "")
        }
        if (time == null) t.replaceFirst(Regex("""$AT(\d{1,2}) ?h(?:(\d{2}))?(?: ($PERIOD))?""")) { m, _ ->
            time = mk(m.groupValues[1].toInt(), m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0, period(m.groupValues[3]), m.groupValues[1].toInt() > 12)
            if (time == null) null else Tok("xtimex", "")
        }
        if (time == null) t.replaceFirst(Regex("""(?:as|pras|pelas|das|ate as|depois das|antes das|por volta das|umas) (\d{1,2})(?: horas?)?(?: e (meia|um quarto|\d{1,2}))?(?: ($PERIOD|em ponto))?""")) { m, _ ->
            val min = minuteWord(m.groupValues[2]) ?: return@replaceFirst null
            val per = m.groupValues[3].takeIf { it != "em ponto" }
            time = mk(m.groupValues[1].toInt(), min, period(per.orEmpty()), m.groupValues[1].toInt() > 12)
            if (time == null) null else Tok("xtimex", "")
        }
        if (time == null) t.replaceFirst(Regex("""(\d{1,2}) horas?(?: e (meia|um quarto|\d{1,2}))?(?: ($PERIOD))?""")) { m, _ ->
            val min = minuteWord(m.groupValues[2]) ?: return@replaceFirst null
            time = mk(m.groupValues[1].toInt(), min, period(m.groupValues[3]), m.groupValues[1].toInt() > 12)
            if (time == null) null else Tok("xtimex", "")
        }
        if (time == null) t.replaceFirst(Regex("""$AT(?:meio dia(?: e meia)?|meia noite)""")) { m, _ ->
            time = when (m.value.substringAfterLast(" a", "").let { Regex("(meio dia e meia|meio dia|meia noite)$").find(m.value)?.value }) {
                "meio dia" -> TimeSlot(12, 0, true)
                "meio dia e meia" -> TimeSlot(12, 30, true)
                else -> TimeSlot(0, 0, true)
            }
            Tok("xtimex", "")
        }

        // ---- repetição ("todo dia", "de segunda a sexta", "toda terça"): antes das datas, para "segunda" não virar data
        var rec: RecSlot? = null
        val dayWord = "(segunda|terca|quarta|quinta|sexta|sabado|domingo)s?(?: feira)?"
        fun days(vararg d: DayOfWeek) = d.map { it.value }.toSet()
        t.replaceFirst(Regex("""todo santo dia|todos os dias|todo dia|diariamente|todas as manhas|todas as noites""")) { _, _ ->
            rec = RecSlot((1..7).toSet(), "todo dia"); Tok("xrecx", "")
        }
        if (rec == null) t.replaceFirst(Regex("""(?:de )?segunda a sexta(?: feira)?|dias uteis|durante a semana|de segunda a sexta""")) { _, _ ->
            rec = RecSlot((1..5).toSet(), "dias úteis"); Tok("xrecx", "")
        }
        if (rec == null) t.replaceFirst(Regex("""(?:(?:nos|aos|todo|todos os) )?(?:fins?|finais?) de semana|(?:aos |todos os |nos )?sabados e domingos""")) { _, _ ->
            rec = RecSlot(days(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), "fins de semana"); Tok("xrecx", "")
        }
        if (rec == null) t.replaceFirst(Regex("""(?:toda|todas as|todo|todos os|nas|nos|as|aos) $dayWord(?:(?:,| e) $dayWord)*""")) { m, _ ->
            val set = Regex(dayWord).findAll(m.value).mapNotNull { weekdays[it.groupValues[1]]?.value }.toSet()
            // "as segundas" só vale no plural ("as segunda" seria artigo + data)
            val plural = Regex("""(?:segunda|terca|quarta|quinta|sexta|sabado|domingo)s""").containsMatchIn(m.value) || m.value.startsWith("toda") || m.value.startsWith("todo")
            if (set.isEmpty() || !plural) null else { rec = RecSlot(set, m.value); Tok("xrecx", "") }
        }

        // ---- data
        fun year(y: String, d: Int, mo: Int): LocalDate? {
            val yy = y.takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            val base = runCatching { LocalDate.of(yy ?: today.year, mo, d) }.getOrNull() ?: return null
            return if (yy == null && base.isBefore(today.minusDays(60))) base.plusYears(1) else base
        }
        fun setDate(d: LocalDate?, spoken: String = "", days: Int = 1, kind: String = "day"): Tok? {
            if (d == null) return null
            date = DateSlot(d, days, kind, spoken)
            return Tok("xdatax", "")
        }
        t.replaceFirst(Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")) { m, _ ->
            setDate(runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull())
        }
        if (date == null) t.replaceFirst(Regex("""(?:dia )?(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?""")) { m, _ ->
            setDate(year(m.groupValues[3], m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        if (date == null) t.replaceFirst(Regex("""(?:dia )?(\d{1,2}) de (jan\w*|fev\w*|mar\w*|abr\w*|mai\w*|jun\w*|jul\w*|ago\w*|set\w*|out\w*|nov\w*|dez\w*)(?: de (\d{4}))?""")) { m, _ ->
            val mo = months.indexOf(m.groupValues[2].take(3)) + 1
            if (mo == 0) null else setDate(year(m.groupValues[3], m.groupValues[1].toInt(), mo))
        }
        if (date == null) t.replaceFirst(Regex("""dia (\d{1,2})""")) { m, _ ->
            val d = m.groupValues[1].toInt()
            val thisMonth = runCatching { today.withDayOfMonth(d) }.getOrNull()
            val pick = when {
                thisMonth != null && !thisMonth.isBefore(today) -> thisMonth
                else -> runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
            }
            setDate(pick)
        }
        if (date == null) t.replaceFirst(Regex("""depois de amanha|anteontem|amanha|hoje|ontem""")) { m, _ ->
            val off = when (m.value) { "depois de amanha" -> 2; "amanha" -> 1; "ontem" -> -1; "anteontem" -> -2; else -> 0 }
            setDate(today.plusDays(off.toLong()), m.value)
        }
        if (date == null) t.replaceFirst(Regex("""(?:daqui a|em|dentro de) (\d+) (dias?|semanas?)""")) { m, _ ->
            val n = m.groupValues[1].toLong()
            setDate(today.plusDays(if (m.groupValues[2].startsWith("sem")) n * 7 else n))
        }
        if (date == null) t.replaceFirst(Regex("""(?:(?:no|na|nesta|neste|esta|este|essa|esse|proxima|proximo|na proxima|no proximo) )?(segunda|terca|quarta|quinta|sexta|sabado|domingo)(?: feira)?(?: que vem| passada| passado)?""")) { m, before ->
            val dow = weekdays[m.groupValues[1]] ?: return@replaceFirst null
            val v = m.value
            val thisOne = Regex("""^(?:nesta|neste|esta|este|essa|esse) """).containsMatchIn(v)
            val past = v.endsWith("passada") || v.endsWith("passado")
            var d = today
            if (past) {
                d = d.minusDays(1)
                while (d.dayOfWeek != dow) d = d.minusDays(1)
            } else {
                if (!thisOne) d = d.plusDays(1)
                while (d.dayOfWeek != dow) d = d.plusDays(1)
            }
            setDate(d, m.groupValues[1])
        }
        if (date == null) t.replaceFirst(Regex("""(?:semana que vem|proxima semana|semana proxima)""")) { _, _ ->
            val monday = today.plusDays((8 - today.dayOfWeek.value).toLong())
            setDate(monday, "semana que vem", 7, "nextweek")
        }
        if (date == null) t.replaceFirst(Regex("""(?:fim|final) de semana""")) { _, _ ->
            val sat = today.plusDays(((DayOfWeek.SATURDAY.value - today.dayOfWeek.value + 7) % 7).toLong())
            setDate(sat, "fim de semana", 2, "weekend")
        }
        if (date == null) t.replaceFirst(Regex("""(?:na |a )?semana passada""")) { _, _ ->
            setDate(today.minusDays((today.dayOfWeek.value - 1 + 7).toLong()), "semana passada", 7, "lastweek")
        }
        if (date == null) t.replaceFirst(Regex("""(?:no |do |ao )?(?:mes passado|ultimo mes)""")) { _, _ ->
            val first = today.withDayOfMonth(1).minusMonths(1)
            setDate(first, "mês passado", first.lengthOfMonth(), "lastmonth")
        }
        if (date == null) t.replaceFirst(Regex("""(?:nesta|esta|essa|na) semana""")) { _, _ -> setDate(today, "esta semana", 7, "week") }
        if (date == null) t.replaceFirst(Regex("""(?:mes que vem|proximo mes)""")) { _, _ ->
            setDate(today.plusMonths(1).withDayOfMonth(1), "mês que vem", 31, "nextmonth")
        }
        if (date == null) t.replaceFirst(Regex("""(?:neste|este|esse|no) mes""")) { _, _ -> setDate(today, "este mês", 31, "month") }

        // ---- parte do dia solta ("amanhã de manhã")
        t.replaceFirst(Regex("""(?:de|da|pela|na|a|esta|essa|nesta) (manha|tarde|noite|madrugada)""")) { m, _ ->
            part = m.groupValues[1]
            Toks.DROP
        }

        return Extracted(t.list, date, time, dur, money, part, rec)
    }

    private fun cueOf(before: String): String {
        val b = " $before"
        return durCues.firstOrNull { b.endsWith(" $it") } ?: ""
    }
}
