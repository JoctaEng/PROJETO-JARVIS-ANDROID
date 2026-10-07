package com.joctaeng.jarvis.mind.persona

import java.util.Locale

/** Pedidos de "pode ir / tchau": o personagem se recolhe sem passar pelo cérebro. */
object DismissCommands {
    private val ptBr = Locale.forLanguageTag("pt-BR")
    private val phrases = listOf(
        "tchau", "tchauzinho", "até logo", "ate logo", "até mais", "ate mais", "até já", "falou",
        "pode ir", "pode se esconder", "pode sumir", "pode descansar", "pode se recolher", "some daí", "some dai",
        "esconde-se", "esconde", "se esconde", "vai descansar", "pode ir embora", "isso é tudo", "é só isso", "e so isso", "só isso",
    )
    private val wake = Regex("^(ei\\s+|oi\\s+|ok\\s+)?(euno|jarvis|joca|joctã|jocta|luna|thor|nina|selene|rex|maya|kiko|astra)[,!.:]?\\s+", RegexOption.IGNORE_CASE)

    /** Verdadeiro só se a fala inteira (curta) for um adeus; frases longas que contenham "tchau" não contam. */
    fun matches(utterance: String): Boolean {
        val text = utterance.trim().replace(wake, "").trim().trimEnd('.', '!', '?', ',', ' ').lowercase(ptBr)
        if (text.isEmpty() || text.length > 40) return false
        return phrases.any { text == it || text == "$it por favor" || text == "$it obrigado" || text == "$it, obrigado" || text == "ok, $it" || text == "valeu, $it" }
    }
}
