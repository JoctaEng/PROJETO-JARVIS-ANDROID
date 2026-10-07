package com.joctaeng.jarvis.mind.persona

/**
 * Junta o texto que chega em streaming e libera frases completas, para a voz
 * começar a falar antes de a resposta inteira terminar (meta: 1ª palavra < 2 s).
 */
class SentenceChunker(private val minLength: Int = 12) {
    private val buffer = StringBuilder()

    fun feed(text: String): List<String> {
        buffer.append(text)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        var math: String? = null
        while (i < buffer.length) {
            val c = buffer[i]
            // Nunca corta dentro de uma fórmula ($…$, $$…$$, \(…\), \[…\]): a voz lê a fórmula inteira de uma vez.
            val closer = math
            // Fórmula na linha não atravessa quebra de linha: um "$" solto não pode calar a voz até o fim.
            if (closer != null && c == '\n' && (closer == "$" || closer == "\\)")) math = null
            if (math != null && closer != null) {
                if (buffer.startsWith(closer, i)) {
                    i += closer.length
                    math = null
                } else {
                    i++
                }
                continue
            }
            val opener = MATH_OPENERS.firstOrNull { (open, _) ->
                buffer.startsWith(open, i) && !(open == "$" && i > 0 && buffer[i - 1] in "RS\\")
            }
            if (opener != null) {
                math = opener.second
                i += opener.first.length
                continue
            }
            val isEnd = c == '\n' || (c in ".!?…;:" && (i + 1 >= buffer.length || buffer[i + 1].isWhitespace()))
            // Só corta se já houver um caractere depois (ou quebra de linha), para não partir "3.5".
            if (isEnd && i + 1 < buffer.length) {
                val sentence = buffer.substring(start, i + 1).trim()
                if (sentence.length >= minLength) {
                    out += sentence
                    start = i + 1
                }
            }
            i++
        }
        buffer.delete(0, start)
        return out
    }

    private fun StringBuilder.startsWith(prefix: String, at: Int): Boolean =
        at + prefix.length <= length && regionMatches(at, prefix, 0, prefix.length)

    private companion object {
        val MATH_OPENERS = listOf("$$" to "$$", "\\[" to "\\]", "\\(" to "\\)", "$" to "$")
    }

    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest.ifEmpty { null }
    }
}
