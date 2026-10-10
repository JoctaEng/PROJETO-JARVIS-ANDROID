package com.joctaeng.jarvis.mind.persona

import java.text.Normalizer

/** Chamado pelo nome ("Oi Joca"): reconhece a saudação + nome na frase ouvida e devolve o que veio depois (o pedido). */
object WakeWord {
    /** [rest] = o que foi dito depois do chamado, já sem pontuação nas pontas (pode ser vazio). */
    data class Match(val rest: String)

    private val greetings = setOf("oi", "ola", "ei", "hey", "e", "ai", "opa", "fala", "salve", "oii", "eae", "eai", "hei", "ok")

    /**
     * Atende por qualquer um dos [names] (ex.: o chamado geral "Joca" e o nome do avatar escolhido, "Guardião").
     * Nomes compostos valem pela primeira palavra ("Oi Victoria").
     */
    fun matchAny(heard: String, names: List<String>): Match? = names
        .map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
        .filter { it.isNotBlank() }
        .distinctBy { normalizeWord(it) }
        .firstNotNullOfOrNull { match(heard, it) }

    fun match(heard: String, name: String): Match? {
        val target = normalizeWord(name)
        if (target.isEmpty()) return null
        val words = heard.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // O chamado vem no começo da frase: até 3 palavras de saudação e então o nome.
        for (i in 0 until minOf(words.size, 4)) {
            if (isName(normalizeWord(words[i]), target)) {
                val before = words.take(i).map { normalizeWord(it) }
                if (before.all { it in greetings }) {
                    val rest = words.drop(i + 1).joinToString(" ").trim().trim(',', '.', '!', '?', ';', ':', ' ')
                    return Match(rest)
                }
                return null
            }
        }
        return null
    }

    /** Aceita pequenas trocas do reconhecedor: "joca", "jóca", "joka", "jocas", "joquinha" não (só o nome). */
    private fun isName(word: String, target: String): Boolean {
        if (word.isEmpty()) return false
        if (word == target) return true
        val k = word.replace('k', 'c').replace("qu", "c")
        val t = target.replace('k', 'c').replace("qu", "c")
        if (k == t || k == t + "s") return true
        return editDistance(k, t) <= 1 && t.length >= 4
    }

    private fun normalizeWord(w: String): String =
        Normalizer.normalize(w.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }
}
