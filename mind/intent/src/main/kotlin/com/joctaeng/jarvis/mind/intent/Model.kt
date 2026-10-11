package com.joctaeng.jarvis.mind.intent

import java.time.LocalDate
import java.time.ZonedDateTime

/** O que o motor precisa saber do momento: relógio, ferramentas disponíveis, apps instalados, conexão. */
class Ctx(
    val now: ZonedDateTime,
    val online: Boolean = true,
    val tools: Set<String> = emptySet(),
    val gmailConnected: Boolean = false,
    /** (rótulo, pacote) dos apps instalados. */
    val apps: List<Pair<String, String>> = emptyList(),
    val userName: String = "",
    val brainName: String? = null,
    val phoneControl: Boolean = true,
) {
    val today: LocalDate get() = now.toLocalDate()
    fun has(tool: String): Boolean = tool in tools
    fun hasPrefix(prefix: String): Boolean = tools.any { it.startsWith(prefix) }
    fun toolsWithPrefix(prefix: String): List<String> = tools.filter { it.startsWith(prefix) }.sorted()
}

/** Um passo: chamar uma ferramenta do Euno com estes argumentos. */
data class Step(val tool: String, val args: Map<String, Any?> = emptyMap(), val label: String = "")

/** O que a ferramenta devolveu. [text] é a saída (ou o motivo da falha). */
data class StepResult(val step: Step, val ok: Boolean, val text: String)

/** A resposta do Euno ao usuário. [ask] deixa uma pergunta aberta (ex.: "Que horas?"). */
data class Reply(val text: String, val emotion: String = "happy", val ask: Ask? = null)

sealed interface Next
data class Finish(val reply: Reply) : Next
/** Depois dos primeiros passos, mais passos (ex.: achar o compromisso e só então apagar). */
data class More(val plan: Plan) : Next

class Plan(val steps: List<Step>, val then: (List<StepResult>) -> Next) {
    companion object {
        fun simple(vararg steps: Step, reply: (List<StepResult>) -> Reply): Plan = Plan(steps.toList()) { Finish(reply(it)) }
    }
}

sealed interface Outcome

/** Resposta direta, sem ferramenta (data, hora, conta, ajuda). */
data class Say(val text: String, val intentId: String, val emotion: String = "neutral") : Outcome

/** Executar este plano. [confirm]: pergunta antes de executar (ex.: dinheiro). */
data class Run(val plan: Plan, val intentId: String, val confirm: String? = null) : Outcome

/**
 * Falta um dado (ou uma confirmação): o Euno pergunta e a próxima fala do usuário completa o pedido.
 * [resume] recebe o que ele disse e devolve o resultado, ou null se a fala não responde à pergunta.
 */
class Ask(val question: String, val intentId: String, val resume: (Extracted, Ctx) -> Outcome?) : Outcome

data object NoMatch : Outcome

/** Dados de um pedido casado, para quem monta o plano. */
class M(val ex: Extracted, val caps: Map<String, String>, val ctx: Ctx, val intentId: String) {
    operator fun get(name: String): String? = caps[name]?.takeIf { it.isNotBlank() }
    val date: DateSlot? get() = ex.date
    val time: TimeSlot? get() = ex.time
    val dur: DurSlot? get() = ex.dur
    val money: MoneySlot? get() = ex.money
    val part: String? get() = ex.part
    val today: LocalDate get() = ctx.today
    fun has(tool: String) = ctx.has(tool)
}

/** Um dado que o pedido pode ter (para a IA tradutora e para as perguntas). */
data class SlotSpec(val name: String, val help: String, val required: Boolean = false)

/**
 * Uma intenção: [templates] reconhecem o pedido, [build] monta o que fazer. [priority] desempata (maior ganha);
 * [describe] é a linha que a IA tradutora lê.
 */
class IntentDef(
    val id: String,
    val app: String,
    val describe: String,
    val slots: List<SlotSpec> = emptyList(),
    val templates: List<String>,
    val priority: Int = 0,
    /** Frases de acompanhamento ("e amanhã?") que repetem esta intenção trocando só o dado que mudou. */
    val follow: List<String> = emptyList(),
    val build: (M) -> Outcome,
) {
    internal val compiled: List<Template> by lazy { templates.map { Template(it) } }
    val keywords: Set<String> by lazy { compiled.flatMap { it.words }.toSet() }
}
