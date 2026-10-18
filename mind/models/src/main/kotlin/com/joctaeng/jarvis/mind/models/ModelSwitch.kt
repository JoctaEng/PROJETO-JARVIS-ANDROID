package com.joctaeng.jarvis.mind.models

/**
 * Escolha de modelo com "Salvar" explícito (pedido do usuário): escolher NÃO aplica. Ao salvar, o app abre o motor,
 * gera uma frase curta de teste e só então confirma — com o que o motor diz que carregou, não com o que foi tocado.
 * Se falhar, o modelo anterior continua ativo.
 */
data class ModelDraft(val active: String, val pending: String) {
    val dirty: Boolean get() = active != pending

    fun select(path: String) = copy(pending = path)
    fun discard() = copy(pending = active)
    fun saved() = copy(active = pending)
}

/** O que o motor confirmou depois de carregar e responder ao teste. */
data class EngineReport(
    val fileName: String,
    val displayName: String,
    val backend: String,
    val vision: Boolean,
    val audio: Boolean,
    val nativeTools: Boolean,
    val testReply: String,
    val loadMillis: Long,
)

sealed interface SwitchOutcome {
    data class Done(val report: EngineReport) : SwitchOutcome
    data class Failed(val reason: String, val keptActive: String) : SwitchOutcome
}

object SwitchText {
    const val PENDING = "Alteração não salva"
    const val NONE = "nenhum (cérebro do celular desligado)"

    fun confirmation(r: EngineReport): String = buildString {
        append("Troca concluída: agora o Euno usa ").append(r.displayName).append(" (").append(r.backend).append(")")
        val extras = buildList {
            if (r.nativeTools) add("ferramentas nativas")
            if (r.vision) add("lê imagens")
            if (r.audio) add("ouve áudio")
        }
        if (extras.isNotEmpty()) append(" — ").append(extras.joinToString(", "))
        append(". Carregou em ").append(String.format(java.util.Locale.ROOT, "%.1f", r.loadMillis / 1000.0).replace('.', ',')).append(" s")
        if (r.testReply.isNotBlank()) append("; teste: \"").append(r.testReply.trim().take(60)).append("\"")
        append('.')
    }

    fun failure(reason: String, keptActiveName: String): String =
        "A troca falhou: ${reason.trim().take(300)}. Continua ativo: ${keptActiveName.ifBlank { NONE }}."

    fun turnedOff(): String = "Troca concluída: o cérebro do celular foi desligado e a memória liberada."
}
