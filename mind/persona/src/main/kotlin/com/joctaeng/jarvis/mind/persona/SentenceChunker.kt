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
        while (i < buffer.length) {
            val c = buffer[i]
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

    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest.ifEmpty { null }
    }
}
