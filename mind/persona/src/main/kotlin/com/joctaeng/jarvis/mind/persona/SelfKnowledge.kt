package com.joctaeng.jarvis.mind.persona

/**
 * Retrato verdadeiro do Euno neste instante (versão, cérebro, voz, ferramentas…), montado a partir
 * do estado real do app — nada aqui é escrito à mão sobre capacidades que ainda não existem.
 */
data class SelfInfo(
    val appName: String = "Euno",
    /** Nome do personagem/avatar escolhido (ex.: "Clara"); vazio = só o nome do app. */
    val assistantName: String = "",
    /** Artigo do personagem ("o ", "a " ou ""). */
    val article: String = "o ",
    val versionName: String,
    val brainNames: List<String>,
    /** O cérebro que responde primeiro agora (o primeiro da ordem que não está descansando). */
    val activeBrain: String = "",
    val voiceName: String,
    val autonomyLabel: String,
    val toolNames: List<String>,
    val memoryCount: Int,
    val privateMode: Boolean,
    /** Recursos de conversa ligados agora (chamado por nome, legenda, fila...), descritos pelo app a partir dos ajustes. */
    val features: List<String> = emptyList(),
)

object SelfKnowledge {
    /** O que ainda NÃO existe (fonte: docs/ROTEIRO.md). Evita prometer o que não há. */
    private val notYet = listOf(
        "reconhecer QUEM está falando (qualquer voz que diga meu nome me chama)",
        "agir sozinho em botões de enviar, pagar ou apagar (sempre peço sua confirmação)",
        "encerrar (matar) outros apps — para sair de um app, vá para a tela inicial com tela_navegar \"inicio\"",
        "enviar e-mail ou mensagem em segundo plano (enviar é sempre pela tela, com confirmação do usuário)",
        "corpo 3D de verdade, de costas ou em outras poses",
    )

    fun section(info: SelfInfo, compact: Boolean = false): String = buildString {
        appendLine("Sobre você mesmo (verdade deste momento — use quando perguntarem o que você é ou pode fazer):")
        if (info.assistantName.isBlank()) {
            appendLine("- Você é o ${info.appName} ${info.versionName}, um assistente pessoal que vive no celular como personagem flutuante.")
        } else {
            appendLine("- Você é ${info.article}${info.assistantName}, o personagem do app ${info.appName} (versão ${info.versionName}), um assistente pessoal que vive no celular como personagem flutuante. Seu nome é ${info.assistantName}.")
        }
        appendLine("- Cérebro: ${info.brainNames.ifEmpty { listOf("nenhum configurado") }.joinToString(", ")}. Voz: ${info.voiceName}.")
        if (info.activeBrain.isNotBlank()) appendLine("- Cérebro respondendo AGORA: ${info.activeBrain} (se ele falhar, o próximo da lista assume). Se perguntarem qual IA você está usando, diga este.")
        appendLine("- Autonomia: ${info.autonomyLabel}. Memórias guardadas: ${info.memoryCount}${if (info.privateMode) " (Modo Privado: nada novo é memorizado)" else ""}.")
        if (info.toolNames.isEmpty()) {
            appendLine("- Ferramentas: não vieram nesta resposta. Se pedirem uma ação, não diga que é incapaz: peça para repetir o pedido de forma direta (ex.: \"abra o Gmail\").")
        } else {
            appendLine("- Ferramentas que você realmente tem: ${info.toolNames.joinToString(", ")}.")
            if ("abrir_app" in info.toolNames) {
                appendLine("- Você abre QUALQUER app instalado (Gmail, Instagram, banco, EduMath, Arquivos...) com abrir_app" +
                    if (info.toolNames.any { it.startsWith("tela_") }) " e, dentro dele, lê, olha, toca, digita e rola com as ferramentas tela_*." else ".")
            }
        }
        if (info.features.isNotEmpty()) appendLine("- Como você conversa agora: ${info.features.joinToString("; ")}.")
        if (!compact) {
            appendLine("- Toda ação sua em ferramentas fica registrada e ações de risco pedem confirmação.")
            appendLine("- Ainda não existe: ${notYet.joinToString("; ")}. Se pedirem, diga que está no roteiro, sem fingir.")
        }
        appendLine("- Só afirme ter feito algo se uma ferramenta confirmou o resultado.")
    }.trimEnd()
}
