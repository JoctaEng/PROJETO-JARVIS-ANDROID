package com.joctaeng.jarvis.system.resources

import java.text.Normalizer

/** Um elemento da tela lido pelo serviço de acessibilidade (sem referência ao Android, para testar aqui). */
data class UiNode(
    val text: String,
    val description: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val scrollable: Boolean = false,
) {
    val label: String get() = text.ifBlank { description }.trim()
}

/** Texto da tela para o cérebro, busca do elemento pelo nome e lista de ações que pedem confirmação. */
object ScreenText {
    /** Palavras de botões que têm efeito real (dinheiro, mensagem enviada, coisa apagada): pedem confirmação do usuário. */
    private val sensitive = listOf(
        "enviar", "pagar", "pagamento", "comprar", "finalizar", "confirmar compra", "transferir", "transferencia", "pix",
        "excluir", "apagar", "deletar", "remover", "sair da conta", "encerrar conta", "assinar", "contratar", "aceitar", "autorizar",
    )

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    fun isSensitive(label: String): Boolean {
        val n = " " + normalize(label) + " "
        return sensitive.any { " $it " in n || n.contains(" $it") }
    }

    /** Linhas curtas "- rótulo [botão/campo]"; senhas nunca aparecem; repetições seguidas saem; corta em [maxChars]. */
    fun render(nodes: List<UiNode>, maxChars: Int = 3_500): String {
        val out = StringBuilder()
        var last = ""
        var cut = false
        for (n in nodes) {
            val line = when {
                n.password -> "- [campo de senha: não lido]"
                n.label.isBlank() -> continue
                else -> "- ${n.label.replace('\n', ' ').take(160)}" + when {
                    n.editable -> " [campo de texto]"
                    n.clickable -> " [toca]"
                    else -> ""
                }
            }
            if (line == last) continue
            if (out.length + line.length + 1 > maxChars) { cut = true; break }
            out.appendLine(line)
            last = line
        }
        if (cut) out.appendLine("(tela cortada; role para ver mais)")
        return out.toString().trimEnd().ifBlank { "(a tela não tem texto legível)" }
    }

    /** Índice do elemento que melhor combina com [query]: igual > começa com > contém; prefere o que se pode tocar. */
    fun bestMatch(nodes: List<UiNode>, query: String): Int? {
        val q = normalize(query)
        if (q.isBlank()) return null
        var best: Int? = null
        var bestScore = 0
        nodes.forEachIndexed { i, n ->
            if (n.password) return@forEachIndexed
            val l = normalize(n.label)
            if (l.isBlank()) return@forEachIndexed
            var score = when {
                l == q -> 100
                l.startsWith(q) -> 80
                l.contains(q) -> 60
                q.contains(l) && l.length >= 3 -> 40
                else -> 0
            }
            if (score == 0) return@forEachIndexed
            if (n.clickable) score += 10
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }
}
