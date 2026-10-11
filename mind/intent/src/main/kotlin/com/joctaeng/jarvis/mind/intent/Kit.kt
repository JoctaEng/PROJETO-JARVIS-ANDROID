package com.joctaeng.jarvis.mind.intent

/** Peças usadas por todas as intenções do catálogo. */
internal object Kit {
    fun seed(m: M): Long = m.ctx.now.toEpochSecond()

    fun say(m: M, text: String, emotion: String = "neutral"): Outcome = Say(text, m.intentId, emotion)

    fun run(m: M, vararg steps: Step, confirm: String? = null, reply: (List<StepResult>) -> Reply): Outcome =
        Run(Plan.simple(*steps, reply = reply), m.intentId, confirm)

    fun plan(m: M, steps: List<Step>, confirm: String? = null, then: (List<StepResult>) -> Next): Outcome =
        Run(Plan(steps, then), m.intentId, confirm)

    fun ask(m: M, question: String, resume: (Extracted, Ctx) -> Outcome?): Outcome = Ask(question, m.intentId, resume)

    /** Junta o que o usuário acabou de dizer ao pedido anterior (data/hora/etc. novos ganham). */
    fun merge(a: Extracted, b: Extracted): Extracted = a.copy(
        date = b.date ?: a.date, time = b.time ?: a.time, dur = b.dur ?: a.dur, money = b.money ?: a.money, part = b.part ?: a.part, rec = b.rec ?: a.rec,
    )

    /** Palavras ditas, sem marcadores, como foram ditas. */
    fun bareText(ex: Extracted): String =
        ex.toks.filterNot { it.norm in Template.PLACEHOLDERS }.joinToString(" ") { it.orig }.trim()

    /** Resposta solta com só um número ("7", "sete") a uma pergunta de hora: vira a hora cheia. */
    fun bareHour(ex: Extracted): TimeSlot? {
        val n = ex.numbers.singleOrNull() ?: return null
        val onlyNumber = ex.toks.filterNot { it.norm in Template.PLACEHOLDERS }.size == 1
        return if (onlyNumber && n in 0..24) TimeSlot(n % 24, 0, periodGiven = false, ambiguous = n in 1..12) else null
    }

    fun firstFailure(results: List<StepResult>): StepResult? = results.firstOrNull { !it.ok }

    /** O motivo de uma falha de ferramenta, em palavras para o usuário (as ferramentas falam com a IA, não com a pessoa). */
    fun humanFailure(text: String): String {
        val t = text.lowercase()
        return when {
            "permissão" in t || "permissao" in t -> "Preciso da sua permissão para isso. Abri o pedido na tela: toque em Permitir e me peça de novo."
            "acessibilidade" in t && ("conectado" in t || "ligado" in t) ->
                "O controle do celular desligou sozinho. Abri a página: desligue e ligue o \"Euno - controle do celular\" e me peça de novo."
            "gmail ainda não foi conectado" in t -> "O Gmail ainda não está conectado. Em Meu Euno → Notificações e Gmail, toque em Conectar Gmail."
            "notificações do euno está desligado" in t || "notificacoes do euno esta desligado" in t ->
                "O acesso às notificações está desligado. Abri a página: ligue o Euno ali e me peça de novo."
            "controle do celular" in t && "desligado" in t -> "O controle do celular está desligado. Ligue em Meu Euno → Controle do celular e me peça de novo."
            else -> "Não consegui: " + text.lineSequence().first().trim().take(220).trimEnd('.') + "."
        }
    }

    fun failure(r: StepResult): Reply = Reply(humanFailure(r.text), "concerned")

    /** Falta alguma ferramenta? Devolve a explicação (ou null se estão todas). */
    fun missing(m: M, vararg tools: String): List<String> = tools.filter { !m.has(it) }

    fun noTool(m: M, what: String): Outcome =
        say(m, "Não tenho acesso a $what agora. Veja em Meu Euno → Ferramentas se está ligado.", "concerned")

    fun cap(s: String): String = Speak.capitalize(s.trim())

    /** Variação de frase: "Pronto", "Feito", "Certo". */
    fun ok(m: M): String = Speak.pick(seed(m), "Pronto", "Feito", "Certo", "Tudo certo")

    /** Dia da semana ISO → sigla que a ferramenta de alarme usa. */
    val dayCodes = listOf("seg", "ter", "qua", "qui", "sex", "sab", "dom")

    /** "tela_*" só existe com o controle do celular ligado. */
    fun screenOk(m: M): Boolean = m.has("tela_tocar") || m.has("tela_ler")
}
