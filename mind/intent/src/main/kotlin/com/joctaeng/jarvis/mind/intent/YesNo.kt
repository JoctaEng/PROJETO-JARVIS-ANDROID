package com.joctaeng.jarvis.mind.intent

/** "sim"/"não" falados de muitas maneiras. */
object YesNo {
    private val yes = setOf(
        "sim", "s", "isso", "isso mesmo", "pode", "pode sim", "pode ser", "confirma", "confirmo", "confirmado", "claro", "ok", "okay", "certo",
        "manda", "vai", "positivo", "com certeza", "faz", "faca", "fazer", "uhum", "aham", "ta bom", "tudo bem", "beleza", "bora", "lanca", "salva", "apaga",
        "pode lancar", "pode salvar", "pode apagar", "pode mandar", "pode fazer", "pode enviar", "envia", "pode sim senhor",
    )
    private val no = setOf(
        "nao", "n", "nem", "cancela", "cancelar", "cancele", "deixa", "deixa pra la", "deixa para la", "esquece", "esqueca", "para", "pare",
        "melhor nao", "negativo", "nunca", "nao quero", "nao precisa", "deixa quieto", "desisto", "para tudo", "nao nao",
    )

    /** true = sim, false = não, null = não é resposta de sim/não. */
    fun parse(raw: String): Boolean? {
        val n = PtText.tokenize(raw).joinToString(" ") { it.norm }
        if (n in no || n.startsWith("nao ") || n.startsWith("cancel")) return false
        if (n in yes) return true
        if (n.startsWith("sim ") || n.startsWith("pode ")) return true
        return null
    }

    fun isCancel(raw: String): Boolean = parse(raw) == false
}
