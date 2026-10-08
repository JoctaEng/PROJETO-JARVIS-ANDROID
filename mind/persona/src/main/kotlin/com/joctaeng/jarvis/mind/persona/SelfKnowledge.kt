package com.joctaeng.jarvis.mind.persona

/**
 * Retrato verdadeiro do Euno neste instante (versão, cérebro, voz, ferramentas…), montado a partir
 * do estado real do app — nada aqui é escrito à mão sobre capacidades que ainda não existem.
 */
data class SelfInfo(
    val appName: String = "Euno",
    val versionName: String,
    val brainNames: List<String>,
    val voiceName: String,
    val autonomyLabel: String,
    val toolNames: List<String>,
    val memoryCount: Int,
    val privateMode: Boolean,
)

object SelfKnowledge {
    /** O que ainda NÃO existe (fonte: docs/ROTEIRO.md). Evita prometer o que não há. */
    private val notYet = listOf(
        "ouvir sem eu abrir o app ou tocar em mim (palavra de ativação)",
        "interromper minha fala falando por cima",
        "controlar qualquer tela do celular tocando nela (controle por acessibilidade)",
        "fechar outros apps sozinho",
        "e-mail, arquivos e notificações (Fase 2, em andamento; agenda e contatos já podem ser lidos)",
        "corpo 3D de verdade, de costas ou em outras poses",
    )

    fun section(info: SelfInfo, compact: Boolean = false): String = buildString {
        appendLine("Sobre você mesmo (verdade deste momento — use quando perguntarem o que você é ou pode fazer):")
        appendLine("- Você é o ${info.appName} ${info.versionName}, um assistente pessoal que vive no celular como personagem flutuante.")
        appendLine("- Cérebro: ${info.brainNames.ifEmpty { listOf("nenhum configurado") }.joinToString(", ")}. Voz: ${info.voiceName}.")
        appendLine("- Autonomia: ${info.autonomyLabel}. Memórias guardadas: ${info.memoryCount}${if (info.privateMode) " (Modo Privado: nada novo é memorizado)" else ""}.")
        if (info.toolNames.isEmpty()) {
            appendLine("- Ferramentas: nenhuma disponível agora; só conversa.")
        } else {
            appendLine("- Ferramentas que você realmente tem: ${info.toolNames.joinToString(", ")}.")
        }
        if (!compact) {
            appendLine("- Toda ação sua em ferramentas fica registrada e ações de risco pedem confirmação.")
            appendLine("- Ainda não existe: ${notYet.joinToString("; ")}. Se pedirem, diga que está no roteiro, sem fingir.")
        }
        appendLine("- Só afirme ter feito algo se uma ferramenta confirmou o resultado.")
    }.trimEnd()
}
