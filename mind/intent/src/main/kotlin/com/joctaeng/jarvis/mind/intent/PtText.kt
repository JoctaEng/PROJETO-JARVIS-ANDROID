package com.joctaeng.jarvis.mind.intent

import java.text.Normalizer
import java.util.Locale

/** Uma palavra do pedido: [norm] para casar (minúscula, sem acento) e [orig] como foi dita (para títulos e nomes). */
data class Tok(val norm: String, val orig: String)

/** Texto em português para o motor: sem acento, números por extenso viram dígitos ("dezessete horas" → "17 horas"). */
object PtText {
    private val ACCENTS_REGEX = Regex("\\p{Mn}+")
    private val CURRENCY_REGEX = Regex("""(?i)r\$\s*(\d[\d.]*(?:,\d{1,2})?)""")
    private val PERCENT_REGEX = Regex("""(\d)\s*%""")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val CLEAN_NORM_REGEX = Regex("[^a-z0-9:/.,%$+*=<>-]")
    private val DIGIT_HYPHEN_DIGIT_REGEX = Regex("""\d-\d""")

    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(ACCENTS_REGEX, "")
            .replace("º", "")
            .replace("ª", "")

    private val edge = charArrayOf(',', '.', ';', ':', '!', '?', '(', ')', '"', '“', '”', '\'', '’', '¿', '¡', '…', '[', ']')

    /** Palavras do pedido, já em minúsculas sem acento; "R$ 50" vira "50 reais" e "15%" vira "15 por cento". */
    fun tokenize(raw: String): List<Tok> {
        var s = raw.replace(CURRENCY_REGEX) { "${it.groupValues[1]} reais" }
        s = s.replace(PERCENT_REGEX, "$1 por cento")
        val out = mutableListOf<Tok>()
        for (word in s.split(WHITESPACE_REGEX).filter { it.isNotBlank() }) {
            val orig = word.trim(*edge)
            if (orig.isEmpty()) continue
            var n = fold(orig).replace(CLEAN_NORM_REGEX, "")
            // hífen entre dígitos é de data (2026-10-15, 15-10); nas palavras ("segunda-feira") separa
            val parts = if (DIGIT_HYPHEN_DIGIT_REGEX.containsMatchIn(n)) listOf(n) else n.split('-')
            for (p in parts) {
                val t = p.trim('.', ',', ':')
                if (t.isNotEmpty()) out += Tok(t, if (parts.size == 1) orig else p)
            }
        }
        return numberWords(dedupeArticles(out))
    }

    private val articles = setOf("o", "a", "os", "as")

    /** "abra o a agenda" (voz ou digitação com tropeço): fica o último artigo. */
    private fun dedupeArticles(t: List<Tok>): List<Tok> {
        val out = mutableListOf<Tok>()
        for ((i, tok) in t.withIndex()) {
            val next = t.getOrNull(i + 1)
            if (tok.norm in articles && next != null && next.norm in articles) continue
            if (out.isNotEmpty() && out.last().norm == tok.norm && tok.norm.length > 2 && !tok.norm[0].isDigit()) continue
            out += tok
        }
        return out
    }

    private val units = mapOf(
        "zero" to 0, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9,
        "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19,
    )
    private val tens = mapOf("vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60, "setenta" to 70, "oitenta" to 80, "noventa" to 90)
    private val hundreds = mapOf(
        "cem" to 100, "cento" to 100, "duzentos" to 200, "duzentas" to 200, "trezentos" to 300, "trezentas" to 300, "quatrocentos" to 400,
        "quinhentos" to 500, "quinhentas" to 500, "seiscentos" to 600, "setecentos" to 700, "oitocentos" to 800, "novecentos" to 900,
    )
    /** "um/uma" só é número antes de uma unidade de tempo/dinheiro ("uma hora", "um real"): senão é artigo. */
    private val unitWords = setOf(
        "hora", "horas", "minuto", "minutos", "min", "segundo", "segundos", "seg", "dia", "dias", "semana", "semanas", "mes", "meses",
        "real", "reais", "conto", "contos", "por", "vez", "vezes", "e",
    )
    private val ordinalsDay = mapOf("primeiro" to 1)

    private fun numberAt(t: List<Tok>, i: Int): Pair<Int, Int>? {
        // devolve (valor, quantos tokens usou)
        var idx = i
        var total = 0
        var used = false
        fun word(k: Int) = t.getOrNull(k)?.norm
        // milhares: "mil", "dois mil", "dois mil e quinhentos"
        val first = word(idx) ?: return null
        if (first == "mil") {
            total = 1000; idx++; used = true
        } else {
            val lead = units[first] ?: if (first == "um" || first == "uma") 1 else null
            if (lead != null && word(idx + 1) == "mil") {
                total = lead * 1000; idx += 2; used = true
            }
        }
        if (used && word(idx) == "e" && (hundreds.containsKey(word(idx + 1)) || tens.containsKey(word(idx + 1)) || units.containsKey(word(idx + 1)))) idx++
        var part = 0
        var partUsed = false
        word(idx)?.let { w -> hundreds[w]?.let { part += it; idx++; partUsed = true } }
        if (partUsed && word(idx) == "e" && (tens.containsKey(word(idx + 1)) || units.containsKey(word(idx + 1)))) idx++
        word(idx)?.let { w ->
            if (tens.containsKey(w)) {
                part += tens[w]!!; idx++; partUsed = true
                if (word(idx) == "e" && (units[word(idx + 1)] ?: if (word(idx + 1) in setOf("um", "uma")) 1 else null) != null) {
                    val u = units[word(idx + 1)] ?: 1
                    if (u in 1..9) { part += u; idx += 2 }
                }
            } else if (units.containsKey(w)) {
                part += units[w]!!; idx++; partUsed = true
            }
        }
        if (!used && !partUsed) {
            // "um"/"uma" sozinho: número só antes de unidade
            val w = word(i)
            if ((w == "um" || w == "uma") && word(i + 1) in unitWords) return 1 to 1
            return null
        }
        return (total + part) to (idx - i)
    }

    /** Troca números por extenso por dígitos, mantendo a palavra original em [Tok.orig]. */
    fun numberWords(t: List<Tok>): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < t.size) {
            val hit = numberAt(t, i)
            if (hit != null && hit.second > 0) {
                out += Tok(hit.first.toString(), t.subList(i, i + hit.second).joinToString(" ") { it.orig })
                i += hit.second
            } else {
                out += t[i]
                i++
            }
        }
        return out
    }

    fun join(t: List<Tok>): String = t.joinToString(" ") { it.norm }

    /** Texto original (com acento e maiúsculas) das palavras de [from] até [to] (exclusivo). */
    fun orig(t: List<Tok>, from: Int, to: Int): String = t.subList(from, to).joinToString(" ") { it.orig }.trim()
}
