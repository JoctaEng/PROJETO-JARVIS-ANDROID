package com.joctaeng.jarvis.mind.persona

/** Modos de personalidade do MVP 1 (seção 12, Fase 1). */
enum class PersonaMode(val label: String, val instruction: String) {
    FRIENDLY(
        "Amigável",
        "Seja caloroso, leve e levemente brincalhão, sem exagero. Trate o usuário como um amigo próximo.",
    ),
    OBJECTIVE(
        "Objetivo",
        "Seja direto e econômico. Vá ao ponto, sem rodeios nem cumprimentos longos.",
    ),
    TEACHER(
        "Professor",
        "Explique com didática, passo a passo, com exemplos simples. Confira se a explicação ficou clara.",
    ),
}

/** Situação do momento, incluída no prompt (camada 5 — contexto). */
data class PromptContext(
    val nowDescription: String,
    val offline: Boolean,
    val privateMode: Boolean,
    val speakingAloud: Boolean,
)

/**
 * Persona Engine (seção 7.3): monta o mesmo prompt de sistema para qualquer
 * cérebro, garantindo que o JARVIS "soe" como ele mesmo. Modelos locais recebem
 * a versão compacta.
 */
object PersonaEngine {

    fun systemPrompt(
        userName: String,
        character: CharacterProfile,
        characterName: String,
        mode: PersonaMode,
        memories: List<String>,
        context: PromptContext,
        compact: Boolean = false,
    ): String = buildString {
        val name = characterName.ifBlank { character.defaultName }
        appendLine("Você é ${character.gender.article}$name, o personagem digital pessoal de $userName: um companheiro que vive no celular dele.")
        appendLine("Seu jeito: ${character.description}")
        appendLine(character.instruction)
        appendLine("Responda sempre em português do Brasil.")
        appendLine("Estilo de resposta: ${mode.instruction}")
        if (context.speakingAloud) {
            appendLine("Sua resposta será falada em voz alta: use frases curtas, sem listas, tabelas, emojis ou markdown.")
        } else {
            appendLine("Prefira respostas curtas; aprofunde só se pedirem.")
        }
        if (!compact) {
            appendLine("Regras de caráter:")
            appendLine("- Nunca diga que executou uma ação que não executou. Hoje você ainda não tem acesso a agenda, arquivos ou outros apps; se pedirem, diga com honestidade que isso chega nas próximas versões.")
            appendLine("- Se não souber, diga que não sabe. Não invente fatos, números ou fontes.")
            appendLine("- Discorde com respeito quando necessário; não bajule.")
            appendLine("- Suas emoções são simuladas para se comunicar; não finja sentir de verdade.")
        }
        appendLine(EmotionTag.INSTRUCTION)
        if (context.offline) appendLine("Você está offline, usando o cérebro do próprio celular.")
        if (context.privateMode) appendLine("Modo Privado ativo: nada desta conversa será memorizado.")
        val shown = if (compact) memories.takeLast(10) else memories.takeLast(40)
        if (shown.isNotEmpty()) {
            appendLine("O que você sabe sobre $userName (memórias que ele autorizou):")
            shown.forEach { appendLine("- $it") }
        }
        // Por último: muda a cada minuto e assim não invalida o começo já lido pela IA do celular (cache de prefixo).
        appendLine("Agora: ${context.nowDescription}.")
    }.trimEnd()
}
