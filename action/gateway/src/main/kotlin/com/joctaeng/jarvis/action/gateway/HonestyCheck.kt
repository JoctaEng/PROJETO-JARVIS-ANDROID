package com.joctaeng.jarvis.action.gateway

import java.text.Normalizer

/**
 * Pega as duas mentiras mais comuns do cérebro (conversa exportada da 0.24, pedido 69):
 * 1. escrever "[usando agenda_consultar]" como texto, sem chamar a ferramenta de verdade;
 * 2. dizer "pronto, abri / voltei para a tela inicial / rolei" sem nenhuma ferramenta ter feito isso.
 * Devolve a mensagem de correção para o cérebro, ou null se está tudo certo.
 */
object HonestyCheck {
    private val fakeTool = Regex("""\[\s*(usando|chamando|executando|ferramenta|tool)\b|\(\s*usando\s+[a-z_]+\s*\)|tool_call|<\s*tool""")

    private val claimVerbs = listOf(
        "abri", "rolei", "toquei", "voltei", "agendei", "salvei", "enviei", "mandei", "criei", "deslizei", "liguei", "desliguei",
        "cliquei", "digitei", "marquei", "naveguei", "fechei", "apaguei", "pesquisei", "procurei na tela", "li a tela", "li aqui",
    )
    private val claimPhrases = listOf(
        "ja estamos na tela inicial", "voltando para a tela inicial", "ja esta aberto", "ja esta aberta", "esta aberta na sua tela",
        "esta aberto na sua tela", "ja esta na tela", "rolagem feita",
        // teste da 0.26.1: "Fechado. Estamos na tela inicial agora", "A tela já foi rolada", "está devidamente marcado"
        "estamos na tela inicial", "ja estou na tela inicial", "ja estou na sua tela inicial", "voltamos para a tela inicial",
        "foi rolada", "foi rolado", "foi aberto", "foi aberta", "foi fechado", "foi fechada", "foi salvo", "foi salva",
        "foi enviado", "foi enviada", "foi apagado", "foi apagada", "registrado com sucesso", "salvo com sucesso",
        "devidamente marcado", "devidamente salvo", "esta marcado na sua agenda", "ja esta salvo", "ja esta marcado",
    )

    /** Ferramentas que MUDAM a tela: depois delas, o conteúdo novo só é conhecido lendo (tela_ler/tela_ver). */
    private val screenChangers = setOf(
        "tela_tocar", "tela_tocar_ponto", "tela_rolar", "tela_deslizar", "tela_navegar", "tela_pesquisar", "abrir_app", "agenda_abrir", "abrir_link",
    )
    private val describing = listOf(
        "aparece", "aparecem", "vejo", "estou vendo", "traz ", "mostra ", "o conteudo", "esta escrito", "diz que", "na tela tem", "na lista",
    )

    /**
     * [lastTool] = a última ferramenta que deu certo nesta resposta. Se ela mudou a tela e o texto descreve o que há na tela,
     * o cérebro está inventando (teste da 0.26.1: abriu o e-mail e "resumiu" sem ler).
     */
    private val verbPatterns by lazy { claimVerbs.associateWith { Regex("""\b${Regex.escape(it)}\b""") } }
    private val whitespaceRegex = Regex("""\s+""")
    private val accentsRegex = Regex("""\p{Mn}+""")

    fun problem(text: String, anyToolSucceeded: Boolean, lastTool: String? = null): String? {
        val t = normalize(text)
        if (t.isBlank()) return null
        if (fakeTool.containsMatchIn(t)) {
            return "ATENÇÃO: você escreveu que usou uma ferramenta, mas não chamou nenhuma (texto como \"[usando …]\" não executa nada). " +
                "Chame a ferramenta de verdade agora, no formato <tool_call>{\"name\": …, \"arguments\": {…}}</tool_call>, e só responda depois do resultado."
        }
        if (lastTool != null && lastTool in screenChangers) {
            describing.firstOrNull { it in t }?.let {
                return "ATENÇÃO: a última ação ($lastTool) mudou a tela e você ainda não leu a tela nova, mas descreveu o que há nela " +
                    "(\"${it.trim()}\"). Chame tela_ler (ou tela_ver) agora e só descreva depois do resultado."
            }
        }
        if (anyToolSucceeded) return null
        val claimed = claimVerbs.firstOrNull { verb -> verbPatterns[verb]?.findAll(t)?.any { !negated(t, it.range.first) } == true }
            ?: claimPhrases.firstOrNull { it in t }
        return claimed?.let {
            "ATENÇÃO: você disse \"$it\", mas nenhuma ferramenta foi executada nesta resposta, então isso NÃO aconteceu. " +
                "Se era para fazer agora, chame a ferramenta certa e só confirme depois do resultado. " +
                "Se você estava falando de algo feito antes nesta conversa, reescreva deixando isso claro."
        }
    }

    /** "não abri", "ainda não rolei": a negação nas 2 palavras antes não é afirmação de ter feito. */
    private fun negated(t: String, at: Int): Boolean {
        val before = t.substring(0, at).trimEnd().split(whitespaceRegex).takeLast(2)
        return before.any { it == "nao" || it == "nem" || it == "sem" }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(accentsRegex, "")
}
