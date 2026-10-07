package com.joctaeng.jarvis.mind.persona

/**
 * Prepara a resposta para a voz: tira marcações de Markdown e transforma fórmulas LaTeX
 * em português falado ("b^2 - 4ac" → "b ao quadrado menos 4 a c"). A tela continua mostrando a fórmula.
 */
object SpokenText {
    private val codeBlock = Regex("```[\\s\\S]*?(```|$)")
    private val displayDollar = Regex("\\$\\$([\\s\\S]+?)\\$\\$")
    private val displayBracket = Regex("\\\\\\[([\\s\\S]+?)\\\\]")
    private val inlineParen = Regex("\\\\\\(([\\s\\S]+?)\\\\\\)")
    // $...$ sem espaço logo após a abertura nem antes do fecho; "R$ 10" e "US$" não contam como fórmula.
    private val inlineDollar = Regex("(?<![\\\\RS$])\\$(?=\\S)([^$\\n]+?)(?<=\\S)\\$(?!\\d)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val tableSeparator = Regex("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun forSpeech(text: String): String {
        var s = text.replace(codeBlock, " (o código está na tela) ")
        s = s.replace(displayDollar) { " " + SpokenMath.speak(it.groupValues[1]) + ". " }
        s = s.replace(displayBracket) { " " + SpokenMath.speak(it.groupValues[1]) + ". " }
        s = s.replace(inlineParen) { SpokenMath.speak(it.groupValues[1]) }
        s = s.replace(inlineDollar) { SpokenMath.speak(it.groupValues[1]) }
        s = s.replace(link) { it.groupValues[1] }
        s = s.replace(tableSeparator, "")
        s = s.lines().joinToString("\n") { line ->
            line.replace(Regex("^\\s*#{1,6}\\s+"), "")
                .replace(Regex("^\\s*>\\s?"), "")
                .replace(Regex("^\\s*[-*•+]\\s+"), "")
                .let { if (it.trimStart().startsWith("|")) it.trim().trim('|').replace("|", ", ") else it }
        }
        s = s.replace(Regex("(\\*\\*|__|~~|`)"), "")
            .replace(Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)\\*(?!\\w)"), "$1")
            .replace(Regex("[*#_|>~]"), " ")
        return s.replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n+ *"), ". ").replace(Regex("\\.(\\s*\\.)+"), ".").trim()
    }
}

/** LaTeX → português falado. Cobre o que aparece em explicações de matemática do ensino fundamental ao superior. */
object SpokenMath {
    private val greek = mapOf(
        "alpha" to "alfa", "beta" to "beta", "gamma" to "gama", "Gamma" to "gama", "delta" to "delta", "Delta" to "delta",
        "epsilon" to "épsilon", "varepsilon" to "épsilon", "theta" to "teta", "Theta" to "teta", "lambda" to "lambda",
        "Lambda" to "lambda", "mu" to "mi", "pi" to "pi", "Pi" to "pi", "rho" to "rô", "sigma" to "sigma", "Sigma" to "sigma",
        "tau" to "tau", "phi" to "fi", "varphi" to "fi", "Phi" to "fi", "omega" to "ômega", "Omega" to "ômega",
    )
    private val symbols = mapOf(
        "pm" to "mais ou menos", "mp" to "menos ou mais", "cdot" to "vezes", "times" to "vezes", "div" to "dividido por",
        "neq" to "diferente de", "ne" to "diferente de", "leq" to "menor ou igual a", "le" to "menor ou igual a",
        "geq" to "maior ou igual a", "ge" to "maior ou igual a", "approx" to "aproximadamente", "infty" to "infinito",
        "Rightarrow" to "então", "implies" to "então", "rightarrow" to "tende a", "to" to "tende a", "iff" to "se e somente se",
        "in" to "pertence a", "notin" to "não pertence a", "subset" to "contido em", "cup" to "união", "cap" to "interseção",
        "circ" to "graus", "degree" to "graus", "angle" to "ângulo", "perp" to "perpendicular a", "parallel" to "paralelo a",
        "therefore" to "portanto", "forall" to "para todo", "exists" to "existe", "percent" to "por cento",
        "quad" to "", "qquad" to "", "," to "", ";" to "", "!" to "", " " to "", "displaystyle" to "", "limits" to "",
    )
    private val functions = mapOf(
        "sin" to "seno de", "sen" to "seno de", "cos" to "cosseno de", "tan" to "tangente de", "tg" to "tangente de",
        "log" to "log de", "ln" to "logaritmo natural de", "exp" to "exponencial de",
        "lim" to "limite", "sum" to "somatório", "int" to "integral", "max" to "máximo", "min" to "mínimo",
    )
    private val textual = setOf("text", "mathrm", "textbf", "mathbf", "operatorname", "textit", "mathit", "mbox")
    private val wrappers = setOf("left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr")

    fun speak(latex: String): String = Parser(latex).sequence(stopAtBrace = false).joinToString(" ")
        .replace(Regex("\\s+"), " ").replace(" ,", ",").trim()

    private class Parser(private val s: String) {
        private var i = 0

        fun sequence(stopAtBrace: Boolean): List<String> {
            val out = mutableListOf<String>()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '}' -> { i++; if (stopAtBrace) return out }
                    c == '{' -> { i++; out += sequence(stopAtBrace = true) }
                    c == '\\' -> out += command()
                    c == '^' -> { i++; out += exponent(group()) }
                    c == '_' -> { i++; out += group() }
                    c.isDigit() -> out += number()
                    c.isLetter() -> { out += c.toString(); i++ }
                    else -> { out += operator(c); i++ }
                }
            }
            return out
        }

        /** Próximo argumento: {grupo}, \comando ou um único caractere. */
        fun group(): String {
            skipSpaces()
            if (i >= s.length) return ""
            return when (s[i]) {
                '{' -> { i++; sequence(stopAtBrace = true).joinToString(" ") }
                '\\' -> command()
                else -> if (s[i].isDigit()) number() else operator(s[i]).also { i++ }.ifEmpty { s[i - 1].toString() }
            }
        }

        private fun rawGroup(): String {
            skipSpaces()
            if (i >= s.length || s[i] != '{') return ""
            var depth = 0
            val start = i + 1
            while (i < s.length) {
                when (s[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { i++; return s.substring(start, i - 1) } }
                }
                i++
            }
            return s.substring(start)
        }

        private fun command(): String {
            i++ // '\'
            if (i >= s.length) return ""
            if (!s[i].isLetter()) {
                val sym = s[i].toString()
                i++
                return when (sym) {
                    "\\" -> "."
                    "{", "}", "(", ")", "[", "]", "|" -> ""
                    "%" -> "por cento"
                    else -> symbols[sym] ?: ""
                }
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when {
                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    val num = group(); val den = group()
                    if (num == "1" && den == "2") "um meio" else "$num sobre $den"
                }
                name == "sqrt" -> {
                    skipSpaces()
                    val index = if (i < s.length && s[i] == '[') {
                        val end = s.indexOf(']', i).takeIf { it > 0 } ?: s.length
                        s.substring(i + 1, end).also { i = minOf(end + 1, s.length) }.trim()
                    } else "2"
                    val body = group()
                    when (index) {
                        "2" -> "raiz quadrada de $body"
                        "3" -> "raiz cúbica de $body"
                        else -> "raiz de índice ${Parser(index).sequence(false).joinToString(" ")} de $body"
                    }
                }
                name in textual -> rawGroup()
                name in wrappers -> { skipSpaces(); if (i < s.length && s[i] != '\\') i++ else if (i < s.length) command(); "" }
                name in functions -> {
                    val spoken = functions.getValue(name)
                    skipSpaces()
                    // "\sin^2 x" é "seno ao quadrado de x", não "seno de ao quadrado x".
                    if (spoken.endsWith(" de") && i < s.length && s[i] == '^') {
                        i++
                        "${spoken.removeSuffix(" de")} ${exponent(group())} de"
                    } else spoken
                }
                name in greek -> greek.getValue(name)
                name in symbols -> symbols.getValue(name)
                name == "overline" || name == "bar" -> "${group()} barra"
                name == "vec" -> "vetor ${group()}"
                name == "hat" -> "${group()} chapéu"
                else -> ""
            }
        }

        private fun exponent(power: String): String = when (power.trim()) {
            "2" -> "ao quadrado"
            "3" -> "ao cubo"
            "graus" -> "graus"
            "" -> ""
            else -> "elevado a $power"
        }

        private fun number(): String {
            val start = i
            while (i < s.length && (s[i].isDigit() || ((s[i] == '.' || s[i] == ',') && i + 1 < s.length && s[i + 1].isDigit()))) i++
            return s.substring(start, i)
        }

        private fun operator(c: Char): String = when (c) {
            '+' -> "mais"
            '-', '−' -> "menos"
            '=' -> "igual a"
            '<' -> "menor que"
            '>' -> "maior que"
            '*', '×', '·' -> "vezes"
            '/' -> "dividido por"
            '!' -> "fatorial"
            '%' -> "por cento"
            '±' -> "mais ou menos"
            '≠' -> "diferente de"
            '≤' -> "menor ou igual a"
            '≥' -> "maior ou igual a"
            '≈' -> "aproximadamente"
            '°' -> "graus"
            'Δ' -> "delta"
            '√' -> "raiz quadrada de"
            ',' -> ","
            else -> ""
        }

        private fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
