package com.joctaeng.jarvis.mind.intent

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/** Como o Euno fala datas, horas e valores em voz alta. */
object Speak {
    private val ptBr = Locale.forLanguageTag("pt-BR")
    private val monthNames = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
    private val weekNames = mapOf(
        DayOfWeek.MONDAY to "segunda-feira", DayOfWeek.TUESDAY to "terça-feira", DayOfWeek.WEDNESDAY to "quarta-feira",
        DayOfWeek.THURSDAY to "quinta-feira", DayOfWeek.FRIDAY to "sexta-feira", DayOfWeek.SATURDAY to "sábado", DayOfWeek.SUNDAY to "domingo",
    )

    fun weekday(d: LocalDate): String = weekNames.getValue(d.dayOfWeek)
    fun month(d: LocalDate): String = monthNames[d.monthValue - 1]

    /** "hoje", "amanhã", "segunda-feira, dia 12", "dia 15 de outubro", "dia 3 de janeiro de 2027". */
    fun date(d: LocalDate, today: LocalDate): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return when {
            diff == 0L -> "hoje"
            diff == 1L -> "amanhã"
            diff == 2L -> "depois de amanhã"
            diff == -1L -> "ontem"
            diff in 3..6 -> "${weekday(d)}, dia ${d.dayOfMonth}"
            d.year == today.year -> "${weekday(d)}, dia ${d.dayOfMonth} de ${month(d)}"
            else -> "dia ${d.dayOfMonth} de ${month(d)} de ${d.year}"
        }
    }

    /** "às 15h", "às 8h30", "ao meio-dia", "à meia-noite". */
    fun at(h: Int, m: Int): String = when {
        h == 12 && m == 0 -> "ao meio-dia"
        h == 0 && m == 0 -> "à meia-noite"
        h == 1 -> "à 1h" + if (m == 0) "" else "%02d".format(m)
        else -> "às ${h}h" + if (m == 0) "" else "%02d".format(m)
    }

    /** "15h", "8h30" (sem "às"). */
    fun hm(h: Int, m: Int): String = "${h}h" + if (m == 0) "" else "%02d".format(m)

    /** 15230 → "R$ 152,30". */
    fun money(cents: Long): String {
        val reais = cents / 100
        val c = cents % 100
        val grouped = reais.toString().reversed().chunked(3).joinToString(".").reversed()
        return "R$ $grouped,${"%02d".format(c)}"
    }

    /** 90 → "1 minuto e 30 segundos"; 3600 → "1 hora"; 5400 → "1 hora e 30 minutos". */
    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val min = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += if (h == 1) "1 hora" else "$h horas"
        if (min > 0) parts += if (min == 1) "1 minuto" else "$min minutos"
        if (s > 0 && h == 0) parts += if (s == 1) "1 segundo" else "$s segundos"
        return if (parts.isEmpty()) "0 segundos" else parts.joinToString(" e ")
    }

    private val tag = Regex("""^\s*\[([^\]]{1,12})]\s*""")
    private val separators = Regex("""\s+[–—|-]\s+|\s*[–—|]\s*""")
    /** "nº 213/2026", "SIMP 029809-500/2026", "025590, 025604-500/2026": números de processo/documento não se falam. */
    private val codes = Regex("""(?i)\b(?:n[º°o.]?\s*|nr\.?\s*|simps?\s+|processo\s+|of[ií]cio\s+n?[º°o.]?\s*)?\d[\d.,/-]*[/-][\d.,/-]*\d\b(?:\s*,\s*\d[\d./-]+)*""")

    private val dateLike = Regex("""\d{1,2}/\d{1,2}(/\d{2}|/\d{4})?""")

    /**
     * Título de compromisso bom de falar (0.31): sem etiqueta "[MP]", só o primeiro trecho antes de " – ", sem números de
     * processo e no máximo [max] caracteres. A tela continua mostrando o título inteiro.
     */
    fun title(raw: String, max: Int = 48): String {
        var t = raw.trim()
        val tagWord = tag.find(t)?.groupValues?.get(1)
        t = t.replace(tag, "")
        val pieces = t.split(separators).map { it.trim() }.filter { it.isNotBlank() }
        t = pieces.firstOrNull() ?: t
        if (t.length < 6 && pieces.size > 1) t = "$t, ${pieces[1]}"
        t = t.replace(codes) { if (dateLike.matches(it.value.trim())) it.value else " " }.replace(Regex("""\(\s*\)"""), " ").replace(Regex("""\s+"""), " ").trim().trim(',', '-', '–', ':', ';', '.').trim()
        if (t.isBlank()) t = tagWord ?: raw.trim()
        if (t.length > max) t = t.take(max).substringBeforeLast(' ').trimEnd(',', ';', ':')
        return t
    }

    fun plural(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"

    fun capitalize(s: String): String = s.replaceFirstChar { it.uppercase(ptBr) }

    /** Lista falada: "a, b e c". */
    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " e " + items.last()
    }

    /** Variação de frase sem ficar robótico: escolhe pela hora (os testes fixam o relógio). */
    fun pick(seed: Long, vararg options: String): String = options[Math.floorMod(seed / 7, options.size.toLong()).toInt()]
}
