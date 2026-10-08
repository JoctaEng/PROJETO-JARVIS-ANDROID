package com.joctaeng.jarvis.mind.persona

import java.text.Normalizer

/** Comandos falados que o personagem obedece sem passar pelo cérebro. */
enum class VoiceCommand {
    /** "tchau", "até logo", "pode ir": fala um adeus, encerra a conversa por voz e se recolhe. */
    DISMISS,

    /** "para de ouvir", "encerrar", "chega": para de ouvir, mas continua na tela. */
    STOP_LISTENING,
}

object VoiceCommands {
    private val fillers = setOf(
        "ei", "oi", "ok", "entao", "bom", "ta", "bem", "ai", "por", "favor", "obrigado", "obrigada", "valeu", "muito", "ja",
        "agora", "pode", "pra", "voce", "euno", "jarvis", "joca", "jocta", "luna", "thor", "nina", "selene", "rex", "maya", "kiko", "astra",
    )
    private val byeWords = setOf("tchau", "tchauzinho", "adeus", "flw", "xau")
    private val byePhrases = listOf(
        listOf("ate", "logo"), listOf("ate", "mais"), listOf("ate", "amanha"), listOf("ate", "ja"), listOf("ate", "breve"),
        listOf("ate", "a", "proxima"),
    )
    private val dismissExact = setOf(
        "ir", "ir embora", "se esconder", "sumir", "descansar", "se recolher", "some dai", "se esconde", "esconde", "vai descansar", "descansa",
        "e so isso", "so isso", "isso e tudo", "e tudo", "e so",
    )
    private val stopExact = setOf(
        "para de ouvir", "pare de ouvir", "parar de ouvir", "chega", "silencio", "encerrar", "encerra", "encerrar conversa", "encerrar a conversa",
        "terminar", "termina", "finalizar", "finaliza", "para", "pare", "parar", "pode parar", "para por aqui", "desliga o microfone",
        "desligar microfone", "desligar o microfone", "nao precisa mais ouvir", "chega de ouvir", "pode encerrar", "vamos encerrar",
    )

    fun parse(utterance: String): VoiceCommand? {
        val words = normalize(utterance)
        if (words.isEmpty() || words.size > 6) return null
        // Tchau/adeus no começo ou no fim de uma frase curta ("tchau", "tchau e obrigado", "obrigado, tchau").
        if (words.size <= 4 && (words.first() in byeWords || words.last() in byeWords)) return VoiceCommand.DISMISS
        // "até logo / até mais..." no começo ou no fim.
        if (words.size <= 5 && byePhrases.any { startsWith(words, it) || endsWith(words, it) }) return VoiceCommand.DISMISS
        // Só enchimento + o pedido ("ei euno, pode ir", "então encerrar").
        val core = words.filterNot { it in fillers && it != "pode" }.let { dropLeadingPode(it) }
        val coreText = core.joinToString(" ")
        val coreNoPode = words.filterNot { it in fillers }.joinToString(" ")
        if (coreText in dismissExact || coreNoPode in dismissExact) return VoiceCommand.DISMISS
        if (coreText in stopExact || coreNoPode in stopExact) return VoiceCommand.STOP_LISTENING
        return null
    }

    private fun dropLeadingPode(words: List<String>): List<String> = if (words.size > 1 && words.first() == "pode") words.drop(1) else words

    private fun startsWith(words: List<String>, phrase: List<String>) = words.size >= phrase.size && words.subList(0, phrase.size) == phrase

    private fun endsWith(words: List<String>, phrase: List<String>) = words.size >= phrase.size && words.subList(words.size - phrase.size, words.size) == phrase

    /** Minúsculas, sem acentos e sem pontuação, em palavras. */
    private fun normalize(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
}
