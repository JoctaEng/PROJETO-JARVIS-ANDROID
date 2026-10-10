package com.joctaeng.jarvis.mind.persona

import java.text.Normalizer

/** Comandos falados que o personagem obedece sem passar pelo cérebro. */
enum class VoiceCommand {
    /** "tchau", "até logo", "pode ir": fala um adeus, encerra a conversa por voz e se recolhe. */
    DISMISS,

    /** "para de ouvir", "encerrar": para de ouvir, mas continua na tela. */
    STOP_LISTENING,

    /**
     * "pera aí", "espera", "para", "chega", "silêncio": se ele está falando ou pensando, interrompe; se está parado,
     * equivale a parar de ouvir. Quem usa decide pelo contexto.
     */
    STOP,
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
        "e so isso", "so isso", "isso e tudo", "e so",
    )
    private val stopListeningExact = setOf(
        "para de ouvir", "pare de ouvir", "parar de ouvir", "encerrar", "encerra", "encerrar conversa", "encerrar a conversa",
        "terminar", "termina", "finalizar", "finaliza", "para por aqui", "desliga o microfone",
        "desligar microfone", "desligar o microfone", "nao precisa mais ouvir", "chega de ouvir", "pode encerrar", "vamos encerrar",
    )
    private val stopExact = setOf(
        "para", "pare", "parar", "chega", "silencio", "pode parar", "pera ai", "pera", "espera", "espere", "espera ai", "calma",
        "um momento", "so um momento", "um minuto", "so um minuto", "aguarda", "aguarde", "para de falar", "pare de falar", "ja chega",
        "cancela", "cancele", "cancelar", "para tudo", "pare tudo", "para com isso", "pare com isso", "chega disso",
    )

    /** "pare, pare", "euno pare com isso agora", "para de fazer isso": verbo de parar + só palavras de apoio. */
    private val stopVerbs = setOf("para", "pare", "parar", "chega", "cancela", "cancele", "cancelar", "interrompe", "interrompa")
    private val stopTail = setOf(
        "com", "isso", "tudo", "de", "falar", "fazer", "o", "que", "esta", "ta", "fazendo", "disso", "ai", "aqui", "logo", "ja",
        "agora", "por", "favor", "isto", "essa", "esse", "acao", "a", "e",
    )

    fun parse(utterance: String): VoiceCommand? {
        val words = normalize(utterance)
        if (words.isEmpty() || words.size > 8) return null
        // Tchau/adeus no começo ou no fim de uma frase curta ("tchau", "tchau e obrigado", "obrigado, tchau").
        if (words.size <= 4 && (words.first() in byeWords || words.last() in byeWords)) return VoiceCommand.DISMISS
        // "até logo / até mais..." no começo ou no fim.
        if (words.size <= 5 && byePhrases.any { startsWith(words, it) || endsWith(words, it) }) return VoiceCommand.DISMISS
        // Só enchimento + o pedido ("ei euno, pode ir", "então encerrar").
        val core = words.filterNot { it in fillers && it != "pode" }.let { dropLeadingPode(it) }
        val coreText = core.joinToString(" ")
        val coreNoPode = words.filterNot { it in fillers }.joinToString(" ")
        if (coreText in dismissExact || coreNoPode in dismissExact) return VoiceCommand.DISMISS
        if (coreText in stopListeningExact || coreNoPode in stopListeningExact) return VoiceCommand.STOP_LISTENING
        if (coreText in stopExact || coreNoPode in stopExact) return VoiceCommand.STOP
        val rest = words.filterNot { it in fillers }
        if (rest.isNotEmpty() && rest.size <= 6 && rest.first() in stopVerbs && rest.drop(1).all { it in stopVerbs || it in stopTail }) {
            return VoiceCommand.STOP
        }
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
