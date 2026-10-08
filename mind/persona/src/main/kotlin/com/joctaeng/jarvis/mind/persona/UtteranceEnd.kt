package com.joctaeng.jarvis.mind.persona

import java.text.Normalizer

/** Ajuda a decidir se a pessoa terminou de falar: uma frase que acaba em "e", "mas", "porque"... provavelmente continua. */
object UtteranceEnd {
    private val danglers = setOf(
        "e", "mas", "porque", "pois", "que", "entao", "tipo", "ou", "se", "de", "do", "da", "dos", "das", "pra", "para", "com", "sem",
        "a", "o", "as", "os", "um", "uma", "uns", "umas", "em", "no", "na", "nos", "nas", "por", "como", "quando", "onde", "ai", "assim",
        "tambem", "ate", "ao", "aos", "sobre", "entre", "contra", "desde", "durante", "meu", "minha", "seu", "sua", "isso", "esse", "essa", "aquele", "aquela", "pelo", "pela",
    )

    fun looksIncomplete(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.last() in listOf(',', ':', ';', '-') || trimmed.endsWith("...") || trimmed.endsWith("…")) return true
        val words = Normalizer.normalize(trimmed.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
        return words.size >= 2 && words.last() in danglers
    }
}
