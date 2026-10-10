package com.joctaeng.jarvis.mind.persona

import java.text.Normalizer

/** Pedido simples que o Euno executa direto, sem esperar o cérebro (pedido 65/72: rápido e sempre igual). */
sealed interface QuickCommand {
    data class OpenApp(val name: String) : QuickCommand
    data object Home : QuickCommand
    data object Back : QuickCommand
    /** [direction]: baixo, cima, proximo (para a direita) ou anterior (para a esquerda). */
    data class Scroll(val direction: String) : QuickCommand
}

/**
 * Reconhece só frases curtas e sem ambiguidade ("abra o Gmail", "volta para a tela inicial", "rola para baixo",
 * "para direita"). Qualquer coisa a mais ("abre o WhatsApp e manda…", "abre o Gmail e procura…") vai para o cérebro.
 */
object QuickCommands {
    private val fillers = setOf(
        "oi", "ola", "ei", "ok", "por", "favor", "pode", "agora", "ai", "ja", "euno", "joca", "entao", "bom", "me", "pra", "por favor",
    ) + (CharacterCatalog.all + AvatarPersonas.all).filter { it.id != "avatar" }.flatMap { normalize(it.defaultName) }
    private val openVerbs = setOf("abra", "abre", "abrir", "abri", "abrindo")
    private val articles = setOf("o", "a", "os", "as", "um", "uma", "app", "aplicativo", "do", "da", "meu", "minha")
    /** Palavras que indicam pedido composto ou condição: aí o cérebro decide. */
    private val compound = setOf("e", "depois", "para", "pra", "que", "se", "quando", "procura", "procure", "pesquisa", "pesquise", "veja", "manda", "mande", "com", "no", "na", "em")

    fun parse(utterance: String): QuickCommand? {
        val words = normalize(utterance).filterNot { it in fillers }
        if (words.isEmpty() || words.size > 7) return null
        val text = words.joinToString(" ")

        // Tela inicial / sair do app
        val homePhrases = listOf(
            "tela inicial", "volta para a tela inicial", "volta pra tela inicial", "volte para a tela inicial", "vai para a tela inicial",
            "va para a tela inicial", "ir para a tela inicial", "voltar para a tela inicial", "sai do app", "saia do app", "sair do app",
            "fecha o app", "feche o app", "fechar o app", "volta para o inicio", "vai para o inicio", "inicio", "home",
        )
        if (text in homePhrases) return QuickCommand.Home
        if (text in setOf("volta", "voltar", "volte", "volta uma tela", "volte uma tela", "tela anterior", "volta a tela")) return QuickCommand.Back

        // Rolar / deslizar
        val scrollVerbs = setOf("rola", "role", "rolar", "desce", "desca", "descer", "sobe", "suba", "subir", "desliza", "deslize", "deslizar", "vai", "va", "passa", "passe")
        if (words.first() in scrollVerbs || words.first() == "para" || words.first() == "mais") {
            val rest = words.toSet()
            val dir = when {
                "direita" in rest || "proxima" in rest || "proximo" in rest -> "proximo"
                "esquerda" in rest || "anterior" in rest -> "anterior"
                "cima" in rest || words.first() in setOf("sobe", "suba", "subir") -> "cima"
                "baixo" in rest || "mais" in rest || words.first() in setOf("desce", "desca", "descer", "rola", "role", "rolar") -> "baixo"
                else -> null
            }
            val allowed = scrollVerbs + setOf("para", "pra", "a", "o", "tela", "direita", "esquerda", "cima", "baixo", "mais", "um", "pouco", "proxima", "proximo", "anterior", "semana", "pagina", "mes", "dia")
            if (dir != null && words.all { it in allowed }) return QuickCommand.Scroll(dir)
        }

        // Abrir um app: "abra o Gmail", "abre o WhatsApp Business"
        if (words.first() in openVerbs) {
            val name = words.drop(1).dropWhile { it in articles }
            if (name.isEmpty() || name.size > 3 || name.any { it in compound }) return null
            if (name.first() in setOf("agenda", "calendario") && name.size == 1) return QuickCommand.OpenApp("Agenda")
            return QuickCommand.OpenApp(originalName(utterance, name) ?: name.joinToString(" "))
        }
        return null
    }

    /** Devolve o nome do app como foi dito (com maiúsculas/acentos), para o casamento com a lista de apps. */
    private fun originalName(utterance: String, normalizedName: List<String>): String? {
        val tokens = utterance.trim().split(Regex("\\s+"))
        val normTokens = tokens.map { normalize(it).joinToString("") }
        for (i in normTokens.indices) {
            if (normTokens.subList(i, normTokens.size).take(normalizedName.size) == normalizedName) {
                return tokens.subList(i, i + normalizedName.size).joinToString(" ").trim(',', '.', '!', '?')
            }
        }
        return null
    }

    private fun normalize(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
}
