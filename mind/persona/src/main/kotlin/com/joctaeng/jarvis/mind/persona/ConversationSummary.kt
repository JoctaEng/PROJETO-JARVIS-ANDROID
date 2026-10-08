package com.joctaeng.jarvis.mind.persona

/** Pedido de resumo da conversa ao cérebro e, sem cérebro disponível, um resumo simples feito só com as falas do usuário. */
object ConversationSummary {
    const val SYSTEM_PROMPT = "Você resume conversas para servirem de contexto em conversas futuras. Escreva em português do Brasil, " +
        "em tópicos curtos, sem enfeites: objetivos do usuário, decisões tomadas, pendências e próximos passos, preferências " +
        "e fatos importantes sobre ele, nomes e datas citados. Não invente nada que não esteja na conversa. Máximo de 1.200 caracteres."

    /** Transcrição "Usuário: ... / Euno: ...", mantendo o FIM (o mais recente) se passar de [maxChars]. */
    fun transcript(messages: List<Pair<String, String>>, maxChars: Int = 12_000): String {
        val text = messages.joinToString("\n") { (who, said) -> "$who: ${said.trim()}" }
        return if (text.length <= maxChars) text else "…" + text.takeLast(maxChars)
    }

    /** Resumo de reserva: as últimas falas do usuário, cada uma cortada. */
    fun fallback(userMessages: List<String>, maxChars: Int = 1_000): String {
        val lines = userMessages.map { it.trim().replace('\n', ' ') }.filter { it.isNotEmpty() }.takeLast(8).map { "- ${it.take(160)}" }
        val text = "Resumo simples (sem cérebro disponível para resumir). O que o usuário pediu ou disse por último:\n" + lines.joinToString("\n")
        return text.take(maxChars)
    }

    /** Título curto a partir da primeira fala do usuário. */
    fun title(firstUserMessage: String, date: String): String {
        val words = firstUserMessage.trim().replace(Regex("\\s+"), " ").split(' ').filter { it.isNotEmpty() }
        val head = words.take(6).joinToString(" ").take(40)
        return if (head.isEmpty()) "Conversa de $date" else "$head — $date"
    }
}
