package com.joctaeng.jarvis.mind.intent

/**
 * A mensagem quando nenhuma IA respondeu (v0.29): em vez de "todos os cérebros falharam", diz o que houve, o que o Euno
 * ainda faz sozinho e como pedir, sem despejar erro técnico em quem fala.
 */
object FailureText {
    fun build(reason: String, online: Boolean, localTried: Boolean, localConfigured: Boolean): String {
        val r = reason.lowercase()
        val cause = when {
            !online && localConfigured && localTried -> "estou sem internet e a IA do celular também não respondeu"
            !online && localConfigured -> "estou sem internet e a IA do celular não está carregada"
            !online -> "estou sem internet e não tenho uma IA instalada no celular"
            "429" in r || "quota" in r || "rate" in r || "limite" in r || "exceeded" in r -> "as IAs online estão sem cota agora"
            "engine is not initialized" in r || "litert" in r || "modelo" in r && localTried -> "a IA do celular não conseguiu carregar"
            "timeout" in r || "timed out" in r || "tempo" in r -> "as IAs demoraram demais para responder"
            else -> "nenhuma IA respondeu agora"
        }
        return "Não consegui conversar com uma IA: $cause. O que eu faço sozinho continua valendo, mesmo sem IA e sem internet: " +
            "hora e data, agenda (\"o que tenho amanhã\", \"marca dentista terça às 15h\"), alarmes e timers, bateria, e-mail, finanças e abrir apps. " +
            "Se quiser conversar, tente de novo daqui a pouco."
    }

    /** Só o essencial para falar em voz alta. */
    fun spoken(online: Boolean): String =
        if (online) "Não consegui falar com a IA agora, mas o que eu faço sozinho continua funcionando."
        else "Estou sem internet e a IA do celular não respondeu, mas o que eu faço sozinho continua funcionando."
}
