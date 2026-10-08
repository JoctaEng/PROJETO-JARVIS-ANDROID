package com.joctaeng.jarvis.system.resources

/** Monta o relatório de erros em texto para o usuário compartilhar (sem chaves nem conversas). */
object ErrorReport {
    /**
     * @param header pares "campo" → valor (versão, aparelho, assinatura...).
     * @param sections título → conteúdo (cada um já em texto); o fim de cada seção é preservado ao cortar.
     * @param maxChars teto do relatório inteiro (o app de compartilhar tem limite de tamanho).
     */
    fun build(header: List<Pair<String, String>>, sections: List<Pair<String, String>>, maxChars: Int = 150_000): String {
        val head = buildString {
            appendLine("# Relatório do Euno")
            header.forEach { (k, v) -> appendLine("- $k: $v") }
        }
        val budget = ((maxChars - head.length) / sections.size.coerceAtLeast(1)).coerceAtLeast(2_000)
        return buildString {
            append(head)
            sections.forEach { (title, body) ->
                appendLine()
                appendLine("## $title")
                val text = body.ifBlank { "(vazio)" }
                appendLine(if (text.length <= budget) text.trimEnd() else "…(início cortado)…\n" + text.takeLast(budget).trimEnd())
            }
        }
    }
}
