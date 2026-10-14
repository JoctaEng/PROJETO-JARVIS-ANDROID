package com.joctaeng.jarvis.mind.persona

/**
 * Texto comum → fala natural (0.31, teste de 14/10: "não sabe ler barra, hífen, número de processo; parece um robô").
 * A tela continua com o texto original; só a voz muda.
 * - "15/10" (com contexto de data) → "15 de outubro"; "15/10/2026" → "15 de outubro de 2026"
 * - "08:00–09:00" → "das 8 às 9 horas"; "14:30" → "14 e 30"; "15h" → "15 horas"; "8h30" → "8 e 30"
 * - " – " e " - " → vírgula; "[MP]" → "MP"; "nº" → "número"; códigos longos ("029809-500/2026") saem da fala
 */
object SpokenProse {
    private val months = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    private val range = Regex("""\b([01]?\d|2[0-3])[:h]([0-5]\d)?\s*[–—-]\s*([01]?\d|2[0-3])[:h]([0-5]\d)?\b""")
    private val clock = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val hourMark = Regex("""\b([01]?\d|2[0-3])h([0-5]\d)?\b""")
    private val fullDate = Regex("""\b(0?[1-9]|[12]\d|3[01])/(0?[1-9]|1[0-2])/((?:19|20)?\d\d)\b""")
    /** dd/mm só com cara de data: depois de "dia", "em", "até", "no", "de", "para", "(", ",", ou no começo. */
    private val shortDate = Regex("""(?i)(^|\bdia\s+|\bem\s+|\baté\s+|\bate\s+|\bno\s+|\bde\s+|\bpara\s+|\bpra\s+|\bdesde\s+|\(|,\s*|:\s*|-\s*)(0?[1-9]|[12]\d|3[01])/(0?[1-9]|1[0-2])\b(?!/)""")
    private val code = Regex("""\b\d[\d.]*[/-]\d[\d./-]*\b""")
    private val dash = Regex("""\s+[–—-]\s+|\s*[–—]\s*""")
    private val tag = Regex("""\[([A-Za-zÀ-ú]{1,12})]""")

    fun normalize(text: String): String {
        var s = text
        s = s.replace(tag) { it.groupValues[1] }
        s = s.replace(Regex("""(?i)\bn[º°]\s*"""), "número ")
        s = s.replace(range) { m ->
            val a = hour(m.groupValues[1].toInt(), m.groupValues[2])
            val b = hour(m.groupValues[3].toInt(), m.groupValues[4])
            "das $a às $b"
        }
        s = s.replace(fullDate) { m -> "${m.groupValues[1].toInt()} de ${months[m.groupValues[2].toInt() - 1]} de ${year(m.groupValues[3])}" }
        s = s.replace(shortDate) { m -> "${m.groupValues[1]}${m.groupValues[2].toInt()} de ${months[m.groupValues[3].toInt() - 1]}" }
        s = s.replace(code) { m -> if (m.value.count { it.isDigit() } >= 6) " " else m.value }
        s = s.replace(clock) { m -> hour(m.groupValues[1].toInt(), m.groupValues[2]) }
        s = s.replace(hourMark) { m -> hour(m.groupValues[1].toInt(), m.groupValues[2]) }
        s = s.replace(dash, ", ")
        s = s.replace(Regex("""\be/ou\b"""), "e ou")
        return s.replace(Regex("""\(\s*\)"""), "").replace(Regex("""\s+,"""), ",").replace(Regex(""",\s*,+"""), ",")
            .replace(Regex("""[ \t]+"""), " ").trim()
    }

    /** 15,"00" → "15 horas"; 8,"30" → "8 e 30"; 1 → "1 hora"; 12 → "meio-dia"; 0 → "meia-noite". */
    private fun hour(h: Int, min: String): String {
        val m = min.toIntOrNull() ?: 0
        val base = when (h) {
            0 -> if (m == 0) "meia-noite" else "0"
            12 -> if (m == 0) "meio-dia" else "12"
            1 -> if (m == 0) "1 hora" else "1"
            else -> if (m == 0) "$h horas" else "$h"
        }
        return if (m == 0) base else "$base e $m"
    }

    private fun year(y: String): String = if (y.length == 2) "20$y" else y
}
