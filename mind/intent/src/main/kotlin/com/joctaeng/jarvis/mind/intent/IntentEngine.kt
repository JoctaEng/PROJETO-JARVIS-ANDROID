package com.joctaeng.jarvis.mind.intent

/**
 * O motor de pedidos do próprio Euno (v0.29, pedido 81): entende o pedido e monta o que fazer SEM pedir nada à IA.
 * Online ou offline, igual, sem gasto e sem demora. Quando não entende, devolve [NoMatch] e quem chamou decide
 * (IA tradutora, depois o caminho antigo).
 */
class IntentEngine(
    private val defs: List<IntentDef> = Catalog.all,
    /** Nomes dos personagens ("Guardião", "Joca"...): são "oi, fulano" e saem do pedido. */
    names: Collection<String> = emptyList(),
) {
    private val nameWords: Set<String> = names.flatMap { PtText.fold(it).split(Regex("[^a-z0-9]+")) }.filter { it.length >= 3 }.toSet()

    /** Para respostas de acompanhamento ("e amanhã?"): a última intenção e o que ela entendeu. */
    private var last: Pair<IntentDef, M>? = null

    private class Cand(val def: IntentDef, val template: Template, val caps: Map<String, String>, val order: Int)

    // ---------------------------------------------------------------- pedido novo

    @Synchronized
    fun handle(raw: String, ctx: Ctx): Outcome {
        if (raw.isBlank()) return NoMatch
        val ex = prepare(raw, ctx)
        val whole = resolve(ex, ctx)
        if (whole !is NoMatch) return remember(whole)
        followUp(ex, ctx)?.let { return remember(it.first, it.second) }
        compound(raw, ctx)?.let { return it }
        return NoMatch
    }

    /** Responde a pergunta que o Euno deixou aberta (um dado que faltava, ou sim/não). Null = a fala não era resposta. */
    @Synchronized
    fun answer(pending: Ask, raw: String, ctx: Ctx): Outcome? {
        if (YesNo.isCancel(raw)) return Say("Tudo bem, deixei para lá.", pending.intentId)
        return pending.resume(prepare(raw, ctx), ctx)
    }

    /** Intenções que mais se parecem com o pedido (para a IA tradutora escolher entre poucas). */
    fun candidates(raw: String, ctx: Ctx, max: Int = 14): List<IntentDef> {
        val words = prepare(raw, ctx).toks.map { it.norm }.filter { it.length >= 3 }
        if (words.isEmpty()) return emptyList()
        return defs.map { d ->
            val score = d.keywords.count { k -> k.length >= 3 && words.any { w -> w.startsWith(k.take(5)) || k.startsWith(w.take(5)) } } +
                (if (d.app.length >= 3 && words.any { it.startsWith(PtText.fold(d.app).take(5)) }) 3 else 0)
            d to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** A IA traduziu o pedido em intenção + dados; o Euno executa pelo mesmo caminho dos pedidos normais. */
    @Synchronized
    fun fromTranslation(intentId: String, slots: Map<String, String>, ctx: Ctx): Outcome {
        val def = defs.firstOrNull { it.id == intentId } ?: return NoMatch
        val ex = syntheticExtract(slots, ctx)
        val out = def.build(M(ex, slots, ctx, def.id))
        return if (out is NoMatch) NoMatch else remember(out, def to M(ex, slots, ctx, def.id))
    }

    fun definition(id: String): IntentDef? = defs.firstOrNull { it.id == id }
    val all: List<IntentDef> get() = defs

    // ---------------------------------------------------------------- por dentro

    private fun remember(o: Outcome, keep: Pair<IntentDef, M>? = null): Outcome {
        if (keep != null) last = keep
        else if (o is Run || o is Say) {
            val id = if (o is Run) o.intentId else (o as Say).intentId
            last = last?.takeIf { it.first.id == id }
        }
        return o
    }

    internal fun prepare(raw: String, ctx: Ctx): Extracted {
        val ex = Extract.of(raw, ctx.today)
        return ex.copy(toks = stripFillers(ex.toks))
    }

    private val leadPhrases = Regex(
        "^(?:oi|ola|opa|ei|e ai|ai|entao|olha|olhe|escuta|escute|por favor|pf|pode|poderia|podia|consegue|conseguiria|voce|tu|vc|" +
            "eu quero|quero|eu queria|queria|eu preciso|preciso|gostaria|eu gostaria|me ajuda a|ajuda a|vamos|bora|so|ta|beleza|ok|euno|joca|" +
            "que voce|que vc|que tu|de novo|mais uma vez)(?= |$)",
    )
    private val trailPhrases = Regex("(?<![^ ])(?:por favor|pf|ai|ne|valeu|obrigado|obrigada|pra mim|para mim|rapido|rapidinho|ta bom|ok|beleza)$")
    private val midPhrases = Regex("(?<![^ ])(?:por favor|pf|ai)(?= |$)")

    private fun stripFillers(toks: List<Tok>): List<Tok> {
        val t = Toks(toks.toMutableList())
        // nomes de personagem e "euno" no começo/fim ("Guardião, abre a agenda")
        fun isName(n: String) = n in nameWords || n == "euno" || n == "joca"
        while (t.list.isNotEmpty() && isName(t.list.first().norm)) t.list.removeAt(0)
        while (t.list.isNotEmpty() && isName(t.list.last().norm)) t.list.removeAt(t.list.lastIndex)
        var guard = 0
        while (guard++ < 8 && t.replaceFirst(leadPhrases) { _, _ -> Toks.DROP }) Unit
        guard = 0
        while (guard++ < 6 && t.replaceFirst(trailPhrases) { _, _ -> Toks.DROP }) Unit
        guard = 0
        while (guard++ < 4 && t.replaceFirst(midPhrases) { _, _ -> Toks.DROP }) Unit
        return t.list
    }

    private fun resolve(ex: Extracted, ctx: Ctx): Outcome {
        val cands = mutableListOf<Cand>()
        var order = 0
        for (d in defs) for (tpl in d.compiled) {
            val caps = tpl.match(ex)
            if (caps != null) cands += Cand(d, tpl, caps, order)
            order++
        }
        for (c in cands.sortedWith(compareByDescending<Cand> { it.def.priority }.thenByDescending { it.template.specificity }.thenBy { it.order })) {
            val m = M(ex, c.caps, ctx, c.def.id)
            val out = c.def.build(m)
            if (out !is NoMatch) {
                last = c.def to m
                return out
            }
        }
        return NoMatch
    }

    /** "e amanhã?" depois de "o que tenho hoje?": repete a última intenção trocando só o que mudou. */
    private fun followUp(ex: Extracted, ctx: Ctx): Pair<Outcome, Pair<IntentDef, M>>? {
        val (def, prev) = last ?: return null
        if (def.follow.isEmpty()) return null
        for (f in def.follow) {
            val caps = Template(f).match(ex) ?: continue
            val merged = prev.ex.copy(
                date = ex.date ?: prev.ex.date, time = ex.time ?: prev.ex.time, dur = ex.dur ?: prev.ex.dur,
                money = ex.money ?: prev.ex.money, part = ex.part ?: prev.ex.part, rec = ex.rec ?: prev.ex.rec,
            )
            val m = M(merged, prev.caps + caps, ctx, def.id)
            val out = def.build(m)
            if (out !is NoMatch) return out to (def to m)
        }
        return null
    }

    private val splitter = Regex("""(?i)\s+(?:e\s+)?(?:depois|em seguida|logo em seguida|logo depois|e então|aí depois)\s+""")

    /** "abre o gmail e depois me diz a agenda": dois pedidos, um depois do outro. */
    private fun compound(raw: String, ctx: Ctx): Outcome? {
        val parts = raw.split(splitter).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val a = resolve(prepare(parts[0], ctx), ctx)
        val b = resolve(prepare(parts[1], ctx), ctx)
        if (a is NoMatch || b is NoMatch) return null
        return when {
            a is Say && b is Say -> Say(a.text + " " + b.text, a.intentId)
            a is Say && b is Run -> b.copy(plan = prefix(a.text, b.plan))
            a is Run && b is Say -> a.copy(plan = suffix(a.plan, b.text))
            a is Run && b is Run && a.confirm == null && b.confirm == null -> Run(join(a.plan, b.plan), a.intentId)
            else -> null
        }
    }

    private fun prefix(text: String, p: Plan) = Plan(p.steps) { r ->
        when (val n = p.then(r)) { is Finish -> Finish(n.reply.copy(text = "$text ${n.reply.text}")); is More -> n }
    }

    private fun suffix(p: Plan, text: String) = Plan(p.steps) { r ->
        when (val n = p.then(r)) { is Finish -> Finish(n.reply.copy(text = "${n.reply.text} $text")); is More -> n }
    }

    private fun join(a: Plan, b: Plan): Plan = Plan(a.steps + b.steps) { r ->
        val ra = a.then(r.take(a.steps.size))
        val rb = b.then(r.drop(a.steps.size))
        if (ra is Finish && rb is Finish) Finish(Reply((ra.reply.text + " " + rb.reply.text).trim(), rb.reply.emotion))
        else Finish(Reply((ra as? Finish)?.reply?.text ?: "Fiz a primeira parte; me peça a segunda de novo.", "neutral"))
    }

    private fun syntheticExtract(slots: Map<String, String>, ctx: Ctx): Extracted {
        val date = slots["date"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }?.let {
            DateSlot(it, slots["days"]?.toIntOrNull()?.coerceIn(1, 31) ?: 1, if ((slots["days"]?.toIntOrNull() ?: 1) > 1) "range" else "day")
        }
        val time = slots["time"]?.split(':')?.let { p ->
            val h = p.getOrNull(0)?.toIntOrNull() ?: return@let null
            val mi = p.getOrNull(1)?.toIntOrNull() ?: 0
            if (h in 0..23 && mi in 0..59) TimeSlot(h, mi, periodGiven = true) else null
        }
        val dur = (slots["duration_min"]?.toDoubleOrNull())?.let { DurSlot((it * 60).toInt()) }
        val money = slots["amount"]?.let { a -> ToolText.cents("R$ " + a.removePrefix("R$").trim())?.let { MoneySlot(it) } }
        return Extracted(emptyList(), date, time, dur, money, null, null)
    }
}
