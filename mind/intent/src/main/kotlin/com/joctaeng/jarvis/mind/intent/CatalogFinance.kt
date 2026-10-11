package com.joctaeng.jarvis.mind.intent

import java.time.LocalDate

/** Joca Finanças (porta MCP `joca_financas`): saldo, gastos, o que falta pagar, fatura e lançar com confirmação. */
internal object CatalogFinance {
    private const val T_SUMMARY = "joca_financas_resumo"
    private const val T_LIST = "joca_financas_lancamentos"
    private const val T_INVOICE = "joca_financas_fatura"
    private const val T_ADD = "joca_financas_lancar"

    private fun gate(m: M, tool: String): Outcome? = if (!m.has(tool)) Kit.noTool(m, "o Joca Finanças") else null

    private fun monthKey(d: LocalDate) = "%04d-%02d".format(d.year, d.monthValue)

    private fun monthName(d: LocalDate, today: LocalDate): String = when {
        d.year == today.year && d.month == today.month -> "neste mês"
        d.year == today.year -> "em ${Speak.month(d)}"
        else -> "em ${Speak.month(d)} de ${d.year}"
    }

    /** O mês que o pedido quer: "mês passado", "em setembro", "este mês" (padrão). */
    private fun monthOf(m: M): LocalDate {
        return m.date?.start ?: m.today
    }

    private data class Line(val day: String, val sign: Char, val cents: Long, val title: String, val category: String, val pending: Boolean)

    private fun lines(text: String): List<Line> = ToolText.plain(text).lines().mapNotNull { l ->
        val g = Regex("""^- (\d{1,2}/\d{1,2}) ([+\-⇄])(R\$ [\d.]+,\d{2}) (.*?)(?: \[(.*?)])?( \(a pagar\))?$""").find(l.trim()) ?: return@mapNotNull null
        Line(g.groupValues[1], g.groupValues[2][0], ToolText.cents(g.groupValues[3]) ?: return@mapNotNull null, g.groupValues[4], g.groupValues[5], g.groupValues[6].isNotEmpty())
    }

    private fun centsArg(c: Long) = "%d,%02d".format(c / 100, c % 100)

    private fun moneyOrNull(c: Long?) = c?.let { Speak.money(it) }

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "fin.balance", "Joca Finanças", "diz o saldo das contas e o resumo do mês",
            templates = listOf(
                "(qual|como esta|como ta|quanto e|quanto esta) (e )?(o )?(meu )?(saldo|dinheiro)( total| das contas| em conta| nas contas)?",
                "quanto (eu )?(tenho|tem) (de dinheiro|de saldo|em conta|nas contas|na conta|guardado|no banco|disponivel)",
                "(meu |o )?saldo( total| das contas)?",
                "(como estao|como esta|como andam|como anda) (as |minhas )?(minhas )?(financas|contas|financas do mes)",
                "(resumo|situacao|panorama|balanco) (financeiro|financeira|das financas|do dinheiro|das contas)",
                "(me )?(mostra|mostre|diz|diga|fala|fale) (o |meu )?(resumo financeiro|saldo|resumo das financas)",
            ),
            priority = 1,
        ) { m ->
            gate(m, T_SUMMARY) ?: Kit.run(m, Step(T_SUMMARY)) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val t = ToolText.plain(r[0].text)
                val total = ToolText.moneyAfter(t, "Total em contas:")
                val got = ToolText.moneyAfter(t, "receitas recebidas")
                val paid = ToolText.moneyAfter(t, "despesas pagas")
                val toPay = ToolText.moneyAfter(t, "despesas a pagar")
                if (total == null) return@run Reply("Abri o resumo, mas não consegui ler o saldo: ${t.lineSequence().first().take(120)}", "concerned")
                val month = buildString {
                    if (got != null || paid != null || toPay != null) {
                        append(" Neste mês: ")
                        append(Speak.list(listOfNotNull(got?.let { "recebeu ${Speak.money(it)}" }, paid?.let { "pagou ${Speak.money(it)}" }, toPay?.let { "ainda tem ${Speak.money(it)} a pagar" })))
                        append('.')
                    }
                }
                Reply("Você tem ${Speak.money(total)} nas contas.$month", "happy")
            }
        },
        IntentDef(
            "fin.spent", "Joca Finanças", "quanto gastou (no mês, ou com uma categoria)",
            slots = listOf(SlotSpec("categoria", "categoria ou texto do gasto"), SlotSpec("date", "um dia do mês")),
            templates = listOf(
                "quanto (eu )?(gastei|gasto|paguei|desembolsei|gastamos) (com|em|no|na|de|nos|nas|do|da)? {cat?} <D?> (mes passado|no mes passado|ultimo mes)?",
                "(qual|quanto) (foi |e |sao )?(o )?(meu )?(gasto|gastos|total de gastos|total gasto) (com|em|no|na|de|do|da|nos|nas)? {cat?} <D?> (mes passado|no mes passado)?",
                "(quanto|o que) (ja )?(saiu|foi) (de dinheiro|em gastos)? (com|em|no|na)? {cat?} <D?>",
            ),
            priority = 1,
        ) { m ->
            gate(m, T_LIST) ?: run {
                val cat = m["cat"]?.takeIf { PtText.fold(it) !in setOf("mes", "este mes", "esse mes", "mes passado", "total", "tudo", "dinheiro", "mes que vem") }
                val month = monthOf(m)
                val args = buildMap<String, Any?> { put("mes", monthKey(month)); put("limite", 100); cat?.let { put("busca", it) } }
                Kit.run(m, Step(T_LIST, args)) { r ->
                    Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                    val raw = ToolText.plain(r[0].text)
                    val of = (cat?.let { " com $it" } ?: "") + " " + monthName(month, m.today)
                    if (raw.startsWith("Nenhum lançamento")) return@run Reply("Não achei nenhum gasto$of.")
                    val l = lines(raw)
                    val spent = l.filter { it.sign == '-' && !it.pending }.sumOf { it.cents }
                    val pending = l.filter { it.sign == '-' && it.pending }.sumOf { it.cents }
                    val partial = if (raw.contains("…e mais")) " (pelo menos; a lista tinha mais itens)" else ""
                    Reply(
                        "Você gastou ${Speak.money(spent)}$of$partial" + (if (pending > 0) ", e ainda tem ${Speak.money(pending)} a pagar" else "") + ".",
                        "happy",
                    )
                }
            }
        },
        IntentDef(
            "fin.income", "Joca Finanças", "quanto recebeu no mês",
            templates = listOf(
                "quanto (eu )?(recebi|ganhei|entrou|entrou de dinheiro|caiu) <D?> (mes passado|no mes passado)?",
                "(qual|quanto) (foi |e )?(a |o )?(minha |meu )?(receita|renda|ganho|ganhos|entrada|entradas) <D?>",
            ),
            priority = 1,
        ) { m ->
            gate(m, T_LIST) ?: Kit.run(m, Step(T_LIST, mapOf("mes" to monthKey(monthOf(m)), "limite" to 100))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val raw = ToolText.plain(r[0].text)
                val month = monthOf(m)
                if (raw.startsWith("Nenhum lançamento")) return@run Reply("Não achei nenhuma receita ${monthName(month, m.today)}.")
                val got = lines(raw).filter { it.sign == '+' }.sumOf { it.cents }
                Reply("Você recebeu ${Speak.money(got)} ${monthName(month, m.today)}.", "happy")
            }
        },
        IntentDef(
            "fin.pending", "Joca Finanças", "o que falta pagar no mês",
            templates = listOf(
                "(o que|quanto|quais|que contas|quais contas|quais despesas) (eu )?(tenho|falta|devo|ainda tenho|ainda falta|ainda devo|preciso) (a |de |para |pra )?pagar( ainda| esse mes| este mes| neste mes)? <D?>",
                "(contas|despesas|boletos) (a pagar|pendentes|em aberto|que faltam|para pagar|pra pagar) <D?>",
                "quanto falta pagar <D?>",
            ),
            priority = 2,
        ) { m ->
            gate(m, T_LIST) ?: Kit.run(m, Step(T_LIST, mapOf("mes" to monthKey(monthOf(m)), "limite" to 100))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val raw = ToolText.plain(r[0].text)
                val month = monthOf(m)
                val todo = lines(raw).filter { it.sign == '-' && it.pending }
                if (todo.isEmpty()) return@run Reply("Você não tem nenhuma despesa a pagar ${monthName(month, m.today)}.", "happy")
                val show = todo.take(5).joinToString("; ") { "${it.title}, ${Speak.money(it.cents)} (dia ${it.day.substringBefore('/')})" }
                Reply("Faltam ${Speak.plural(todo.size, "despesa", "despesas")} a pagar, ${Speak.money(todo.sumOf { it.cents })} no total: $show" + (if (todo.size > 5) "; e mais ${todo.size - 5}." else "."))
            }
        },
        IntentDef(
            "fin.invoice", "Joca Finanças", "valor da fatura do cartão de crédito",
            slots = listOf(SlotSpec("cartao", "nome do cartão; vazio = todos")),
            templates = listOf(
                "(qual|quanto|como esta|como ta|quanto e|quanto esta|me mostra|mostra|mostre|ver|veja|diz|diga) (e |esta |ta )?(a |minha |o valor da )?(minha )?fatura (atual )?(do |da |de |no |na )?(cartao )?(do |da |de )?{cartao?}",
                "quanto (eu )?(devo|tenho|estou devendo|to devendo) (no |do |de |na |da )?(cartao|credito)( do| da| de)? {cartao?}",
                "(fatura|faturas) (do |da |de )?(cartao|cartoes|credito)? {cartao?}",
                "(qual|quanto) (e |esta )?(o )?(limite|limite livre|limite disponivel) (do |da |de )?(cartao )?(do |da |de )?{cartao?}",
            ),
            priority = 2,
        ) { m ->
            val card = m["cartao"]?.takeIf { PtText.fold(it) !in setOf("cartao", "cartoes", "credito", "atual", "mes") }
            if (card != null) {
                gate(m, T_INVOICE) ?: Kit.run(m, Step(T_INVOICE, mapOf("cartao" to card))) { r ->
                    Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                    val t = ToolText.plain(r[0].text)
                    val name = Regex("""^Cartão (.+?): fatura atual""").find(t)?.groupValues?.get(1) ?: card
                    val now = ToolText.moneyAfter(t, "fatura atual")
                    val next = ToolText.moneyAfter(t, "próxima")
                    val free = ToolText.moneyAfter(t, "(livre")
                    val due = Regex("""vence dia (\d{1,2})""").find(t)?.groupValues?.get(1)
                    if (now == null) return@run Reply(t.lineSequence().first().take(200), "concerned")
                    Reply(
                        "A fatura do $name está em ${Speak.money(now)}" + (next?.let { ", e a próxima, ${Speak.money(it)}" } ?: "") +
                            (due?.let { ". Vence dia $it" } ?: "") + (free?.let { ". Limite livre: ${Speak.money(it)}" } ?: "") + ".",
                        "happy",
                    )
                }
            } else {
                gate(m, T_SUMMARY) ?: Kit.run(m, Step(T_SUMMARY)) { r ->
                    Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                    val cards = Regex("""Cartão (.+?): fatura do mês (R\$ [\d.]+,\d{2}), limite livre (R\$ [\d.]+,\d{2})""").findAll(ToolText.plain(r[0].text)).toList()
                    if (cards.isEmpty()) Reply("Não achei nenhum cartão cadastrado no Joca Finanças.")
                    else Reply("Faturas deste mês: " + Speak.list(cards.map { "${it.groupValues[1]}, ${it.groupValues[2]}" }) + ".", "happy")
                }
            }
        },
        IntentDef(
            "fin.add", "Joca Finanças", "lança uma despesa ou receita (sempre confirma antes)",
            slots = listOf(SlotSpec("titulo", "com o que foi", true), SlotSpec("amount", "valor, ex.: 152,30", true), SlotSpec("tipo", "DESPESA ou RECEITA"), SlotSpec("date", "dia AAAA-MM-DD")),
            templates = listOf(
                "(lanca|lance|lancar|registra|registre|registrar|anota|anote|anotar|adiciona|adicione|cadastra|cadastre|cadastrar|coloca|coloque|poe|ponha|bota|inclui|inclua) (uma |um |o |a |no joca |no joca financas )?(despesa|gasto|receita|entrada|saida|conta|pagamento|compra)? (de |no valor de |com valor de |ate |:)?<M> (de |com |em |no |na |do |da |para |pra |por |referente a |referente ao )?{titulo?}",
                "(gastei|paguei|comprei|pago|gasto|desembolsei) <M> (de |com |em |no |na |do |da |nos |nas |para |pra |por |numa |num )?{titulo?}",
                "(gastei|paguei|comprei|pago) {titulo} (por|de|a|em|no valor de) <M>",
                "(recebi|ganhei|entrou|entrada de|caiu|recebemos) <M> (de |do |da |com |em |no |na |por |referente a )?{titulo?}",
                "(lanca|lance|registra|registre|anota|anote) {titulo} (por|de|a|no valor de) <M>",
            ),
            priority = 3,
        ) { m ->
            if (!m.has(T_ADD)) return@IntentDef Kit.noTool(m, "o Joca Finanças")
            val cents = m.money?.cents?.takeIf { it > 0 } ?: return@IntentDef NoMatch
            val said = PtText.fold(Kit.bareText(m.ex))
            // parcelado, financiamento, juros: a conta é outra; deixa para a IA.
            if (Regex("""\b(vezes|parcela|parcelas|parcelado|parcelei|parcelamento|x sem juros|financiamento|juros)\b""").containsMatchIn(said)) return@IntentDef NoMatch
            val first = m.ex.toks.firstOrNull()?.norm.orEmpty()
            val income = first in setOf("recebi", "ganhei", "entrou", "entrada", "caiu", "recebemos") || Regex("""\b(receita|entrada|salario|recebi)\b""").containsMatchIn(said)
            // cartão: "no cartão Nubank"
            val cardMention = Regex("""(?i)\bcart[aã]o\s+(?:d[eoa]s?\s+)?(\w+)""").find(Kit.bareText(m.ex))
            if (cardMention == null && Regex("""\bcartao\b|\bcredito\b""").containsMatchIn(said)) return@IntentDef NoMatch
            var title = m["titulo"].orEmpty()
            title = title.replace(Regex("""(?i)\b(no|do|pelo|com o)?\s*cart[aã]o\s+(?:d[eoa]s?\s+)?\w+"""), " ").replace(Regex("""\s+"""), " ").trim()
            title = title.replace(Regex("""(?i)^(de|com|em|no|na|do|da|para|pra|por|um|uma|o|a)\s+"""), "").trim().trim(',', '.')
            if (PtText.fold(title) in setOf("reais", "real", "dinheiro")) title = ""

            fun build(title: String): Outcome {
                val t = Kit.cap(title)
                val day = m.date?.start ?: m.today
                val args = mutableMapOf<String, Any?>("titulo" to t, "valor" to centsArg(cents), "tipo" to if (income) "RECEITA" else "DESPESA", "data" to day.toString())
                cardMention?.let { args["cartao"] = it.groupValues[1] }
                val kind = if (income) "receita" else "despesa"
                val ask = "Lançar $kind de ${Speak.money(cents)} — $t, ${Speak.date(day, m.today)}" + (cardMention?.let { ", no cartão ${it.groupValues[1]}" } ?: "") + "? Diga sim para confirmar."
                return Kit.run(m, Step(T_ADD, args), confirm = ask) { r ->
                    Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                    val text = ToolText.plain(r[0].text)
                    if (text.startsWith("NÃO gravei")) {
                        val why = text.substringAfter("NÃO gravei:").substringBefore(" Para gravar").trim().trimEnd('.')
                        return@run Reply(
                            "Não lancei: $why. Quer lançar mesmo assim?", "concerned",
                            Ask("Lançar mesmo assim?", m.intentId) { ex, _ ->
                                when (Kit.yes(ex)) {
                                    true -> Run(Plan.simple(Step(T_ADD, args + ("forcar" to true), confirmed = true)) { r2 ->
                                        Kit.firstFailure(r2)?.let { return@simple Kit.failure(it) }
                                        Reply("Lancei mesmo assim: $t, ${Speak.money(cents)}.")
                                    }, m.intentId)
                                    false -> Say("Certo, não lancei.", m.intentId)
                                    null -> null
                                }
                            },
                        )
                    }
                    Reply("Lançado: $t, ${Speak.money(cents)}${if (income) " de receita" else ""}, ${Speak.date(day, m.today)}.", "happy")
                }
            }
            if (title.length < 2) Kit.ask(m, "Com o que foi essa ${if (income) "receita" else "despesa"} de ${Speak.money(cents)}?") { ex, _ ->
                val t = Kit.bareText(ex).takeIf { it.isNotBlank() && Kit.yes(ex) == null } ?: return@ask null
                build(t)
            } else build(title)
        },
    )
}
