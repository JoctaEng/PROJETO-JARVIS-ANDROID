package com.joctaeng.jarvis.mind.intent

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Contas faladas: "12 vezes 15", "15 por cento de 80", "raiz quadrada de 144", "2 elevado a 10". Sem IA e sem internet. */
object Calc {
    private val ops = mapOf(
        "mais" to '+', "+" to '+', "menos" to '-', "-" to '-', "vezes" to '*', "x" to '*', "*" to '*', "multiplicado" to '*',
        "dividido" to '/', "/" to '/', "sobre" to '/', "elevado" to '^', "^" to '^',
    )

    /** Resultado formatado em português, ou null se não for uma conta que eu entenda. */
    fun solve(norm: String): String? {
        val toks = norm.split(' ').filter { it.isNotBlank() && it !in setOf("a", "de", "por", "o", "e", "quanto", "da", "da", "igual") }
        // reconhecer "por cento" antes de tirar o "por"
        val text = norm.replace(Regex("""\b(?:quanto e|quanto da|quanto fica)\b"""), "").trim()
        percentOf(text)?.let { return fmt(it) }
        sqrt(text)?.let { return fmt(it) }
        power(text)?.let { return fmt(it) }
        val expr = Parser(toks).parse() ?: return null
        return fmt(expr)
    }

    private fun num(s: String): BigDecimal? = s.replace(".", "").replace(',', '.').toBigDecimalOrNull()

    private fun percentOf(t: String): BigDecimal? {
        val m = Regex("""(\d[\d.,]*) por cento (?:de|do|da) (\d[\d.,]*)""").find(t) ?: return null
        val p = num(m.groupValues[1]) ?: return null
        val v = num(m.groupValues[2]) ?: return null
        return v.multiply(p).divide(BigDecimal(100), MathContext.DECIMAL64)
    }

    private fun sqrt(t: String): BigDecimal? {
        val m = Regex("""raiz (?:quadrada )?(?:de|do) (\d[\d.,]*)""").find(t) ?: return null
        val v = num(m.groupValues[1]) ?: return null
        return runCatching { BigDecimal(Math.sqrt(v.toDouble()), MathContext.DECIMAL64) }.getOrNull()
    }

    private fun power(t: String): BigDecimal? {
        Regex("""(\d[\d.,]*) (?:ao quadrado)""").find(t)?.let { m -> num(m.groupValues[1])?.let { return it.multiply(it) } }
        Regex("""(\d[\d.,]*) (?:ao cubo)""").find(t)?.let { m -> num(m.groupValues[1])?.let { return it.multiply(it).multiply(it) } }
        val m = Regex("""(\d[\d.,]*) elevado (?:a|na) (\d{1,3})""").find(t) ?: return null
        val b = num(m.groupValues[1]) ?: return null
        return runCatching { b.pow(m.groupValues[2].toInt(), MathContext.DECIMAL64) }.getOrNull()
    }

    private fun fmt(v: BigDecimal): String {
        val r = v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = r.toPlainString()
        val (int, frac) = if ('.' in plain) plain.split('.') else listOf(plain, "")
        val neg = int.startsWith("-")
        val grouped = int.removePrefix("-").reversed().chunked(3).joinToString(".").reversed()
        return (if (neg) "-" else "") + grouped + if (frac.isEmpty()) "" else ",$frac"
    }

    /** expressão: termo (+|- termo)*, termo: fator (*|/ fator)*. */
    private class Parser(val t: List<String>) {
        var i = 0
        fun parse(): BigDecimal? {
            if (t.none { it in ops.keys }) return null
            val v = runCatching { expr() }.getOrNull() ?: return null
            return if (i == t.size) v else null
        }
        private fun peekOp(): Char? = t.getOrNull(i)?.let { ops[it] }
        private fun expr(): BigDecimal {
            var v = term()
            while (true) {
                val op = peekOp()
                if (op == '+' || op == '-') { i++; val r = term(); v = if (op == '+') v.add(r) else v.subtract(r) } else break
            }
            return v
        }
        private fun term(): BigDecimal {
            var v = factor()
            while (true) {
                val op = peekOp()
                if (op == '*' || op == '/') {
                    i++
                    if (t.getOrNull(i) == "por") i++
                    val r = factor()
                    v = if (op == '*') v.multiply(r) else v.divide(r, MathContext.DECIMAL64)
                } else break
            }
            return v
        }
        private fun factor(): BigDecimal {
            val w = t.getOrNull(i) ?: error("fim")
            i++
            return num(w) ?: error("número esperado: $w")
        }
    }
}
