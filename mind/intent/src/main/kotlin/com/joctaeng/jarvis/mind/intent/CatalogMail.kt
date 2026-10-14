package com.joctaeng.jarvis.mind.intent

/** Gmail: e-mails não lidos, de alguém, sobre um assunto, e ler o último. Tudo em segundo plano (sem abrir o app). */
internal object CatalogMail {
    private const val notConnected = "O Gmail ainda não está conectado. Em Meu Euno → Notificações e Gmail, toque em Conectar Gmail."

    private fun gate(m: M): Outcome? = when {
        !m.has("gmail_buscar") -> Kit.noTool(m, "o Gmail")
        !m.ctx.gmailConnected -> Kit.say(m, notConnected, "concerned")
        else -> null
    }

    private fun search(m: M, query: String, intro: String, none: String, count: Int = 8): Outcome =
        Kit.run(m, Step("gmail_buscar", mapOf("consulta" to query, "quantos" to count))) { r ->
            Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
            val list = ToolText.mails(r[0].text)
            if (list.isEmpty()) Reply(none)
            else Reply(Mail.spoken(list, intro.trimEnd(':')), "happy", detail = Mail.detail(list), facts = "E-mails ($query):\n" + ToolText.plain(r[0].text))
        }

    private fun day(d: java.time.LocalDate) = "%04d/%02d/%02d".format(d.year, d.monthValue, d.dayOfMonth)

    /** "de hoje", "da semana passada", "dia 10": a busca do Gmail por período (after/before). */
    private fun period(m: M, date: DateSlot): Outcome {
        val q = "in:inbox after:${day(date.start)} before:${day(date.start.plusDays(date.days.toLong()))}"
        val w = CatalogAgenda.whenText(date, m.today)
        return search(m, q, "E-mails $w", "Não achei e-mails $w.", count = 10)
    }

    /** Pergunta aberta (pedido de 14/10): "quer os de hoje, um resumo dos não lidos ou os mais antigos?". */
    private fun choose(m: M): Outcome = Kit.ask(m, "Quer os e-mails de hoje, um resumo dos não lidos, ou procuro mais antigos (por exemplo, da semana passada)?") { ex, c ->
        val said = PtText.fold(ex.raw)
        val mm = M(ex, emptyMap(), c, m.intentId)
        when {
            ex.date != null && ex.date.start != c.today -> period(mm, ex.date)
            Regex("""\b(hoje|do dia|de hoje)\b""").containsMatchIn(said) -> period(mm, DateSlot(c.today))
            Regex("""\b(resumo|nao lidos?|novos?|importantes?)\b""").containsMatchIn(said) || Kit.yes(ex) == true ->
                search(mm, "is:unread in:inbox", "Seus e-mails não lidos mais recentes", "Você não tem e-mails novos.")
            Regex("""\b(antigos?|anteriores?|passad[ao]s?|ontem|semana|mes)\b""").containsMatchIn(said) ->
                period(mm, DateSlot(c.today.minusDays(7), 7, "lastweek"))
            else -> null
        }
    }

    /** Remetente OU assunto/texto: "email do banco" acha o banco como remetente e como assunto. */
    private fun fromQuery(who: String) = "{from:\"$who\" \"$who\"}"

    private fun senderOf(raw: String?): String? = raw?.trim()?.takeIf { it.length >= 2 && PtText.fold(it) !in setOf("novo", "nova", "ontem", "hoje") }

    /** Lê o primeiro e-mail que a busca achar (busca → lê). */
    private fun readFirst(m: M, query: String, none: String): Outcome =
        Kit.plan(m, listOf(Step("gmail_buscar", mapOf("consulta" to query, "quantos" to 1)))) { r ->
            r.firstOrNull { !it.ok }?.let { return@plan Finish(Kit.failure(it)) }
            val first = ToolText.mails(r[0].text).firstOrNull() ?: return@plan Finish(Reply(none))
            if (!m.has("gmail_ler")) return@plan Finish(Reply(Replies.mails(listOf(first), "O e-mail mais recente é"), "happy"))
            More(
                Plan.simple(Step("gmail_ler", mapOf("id" to first.id))) { r2 ->
                    r2.firstOrNull { !it.ok }?.let { return@simple Kit.failure(it) }
                    val o = ToolText.obj(r2[0].text)
                    val body = (ToolText.str(o, "texto") ?: ToolText.plain(r2[0].text)).replace(Regex("""\s+"""), " ").trim()
                    val shown = if (body.length > 600) body.take(600).substringBeforeLast(' ') + "… (o resto está no Gmail)" else body
                    Reply(
                        "E-mail de ${first.from.ifBlank { "remetente desconhecido" }}, assunto \"${(ToolText.str(o, "assunto") ?: first.subject).take(80)}\": $shown",
                        "neutral",
                    )
                },
            )
        }

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "gmail.info", "Gmail", "pergunta o que o usuário quer dos e-mails (de hoje, resumo dos não lidos ou mais antigos)",
            templates = listOf(
                "(me )?(da|de|passa|passe|traz|traga|mostra|mostre|fala|fale|diz|diga) (as |umas )?(informacoes|novidades|noticias|coisas) (do|dos|de|no) (meu |meus )?(email|e mail|emails|e mails|gmail|correio)",
                "(o que|oq) (tem|ha) (de novo )?(no|nos) (meu |meus )?(email|e mail|emails|e mails|gmail)",
                "(verifica|verifique|olha|olhe|ve|veja|confere|confira|checa|cheque) (o |os )?(meu |meus )?(email|e mail|emails|e mails|gmail) (pra mim|para mim)?",
                "(e )?(os |o )?(meus |meu )?(emails|e mails|email|e mail)",
            ),
            priority = 5,
        ) { m ->
            gate(m) ?: if (m.date != null) period(m, m.date!!) else choose(m)
        },
        IntentDef(
            "gmail.period", "Gmail", "e-mails de um dia ou período (hoje, ontem, semana passada, dia 10)",
            slots = listOf(SlotSpec("date", "dia AAAA-MM-DD"), SlotSpec("days", "quantos dias")),
            templates = listOf(
                "(os |meus )?(emails|e mails|email|e mail) (que chegaram|que recebi|recebidos)? <D>",
                "(quais|que) (emails|e mails) (chegaram|recebi|eu recebi|tenho) <D>",
                "(me )?(mostra|mostre|le|leia|ve|veja|procura|procure|busca|busque|traz|traga) (os )?(meus )?(emails|e mails) <D>",
                "(o que|oq) (chegou|tem) (no|de) (meu )?(email|e mail|gmail) <D>",
            ),
            priority = 6,
        ) { m ->
            val d = m.date ?: return@IntentDef NoMatch
            gate(m) ?: period(m, d)
        },
        IntentDef(
            "gmail.unread", "Gmail", "diz os e-mails novos (não lidos)",
            templates = listOf(
                "(tenho|tem|chegou|chegaram|ha|recebi) (algum|alguma|algum outro)? (email|e mail|emails|e mails|correio) (novo|novos|nao lido|nao lidos|importante|importantes)? (agora|no gmail)? <D?>",
                "(tenho|tem|chegou|chegaram|ha|recebi) (alguma )?(mensagem|mensagens|novidade|novidades) (nova|novas)? (no|do|de) (gmail|email|e mail|correio)",
                "(quais|que|quantos) (sao )?(os )?(meus )?(emails|e mails|emails novos|e mails novos|emails nao lidos) (novos|nao lidos|chegaram|tenho)?",
                "(le|leia|ler|mostra|mostre|ver|veja|confere|confira|verifica|verifique|checa|cheque|olha|olhe) (os |meus |o |meu )?(meus )?(emails|e mails|email|e mail|gmail|caixa de entrada)( novos| nao lidos)?",
                "(o que|oq) (chegou|tem|tenho) (no|de) (gmail|email|e mail|correio)",
                "(meus )?(emails|e mails|email) (novos|nao lidos)",
                "(como esta|como ta|como anda|como) (o |a )?(meu |minha )?(email|e mail|emails|e mails|gmail|correio|caixa de entrada)",
            ),
            priority = 4,
        ) { m ->
            gate(m) ?: search(m, "is:unread in:inbox", "Seus e-mails não lidos mais recentes:", "Você não tem e-mails novos.")
        },
        IntentDef(
            "gmail.from", "Gmail", "e-mails de uma pessoa ou empresa",
            slots = listOf(SlotSpec("quem", "nome do remetente", true)),
            templates = listOf(
                "(tenho|tem|chegou|chegaram|ha|recebi|recebeu) (algum|alguma|algum outro)? (email|e mail|emails) (novo)? (do|da|de|dos|das) {quem}",
                "(procura|procure|busca|busque|acha|ache|encontra|encontre|ve|veja|mostra|mostre|me mostra) (os |o |um |algum )?(email|e mail|emails|e mails) (do|da|de|dos|das) {quem}",
                "(emails|e mails) (do|da|de|dos|das) {quem}",
            ),
            priority = 4,
        ) { m ->
            gate(m) ?: run {
                val who = senderOf(m["quem"]) ?: return@run NoMatch
                search(m, fromQuery(who), "E-mails de $who:", "Não achei e-mails de $who.")
            }
        },
        IntentDef(
            "gmail.search", "Gmail", "procura e-mails sobre um assunto",
            slots = listOf(SlotSpec("assunto", "o que procurar", true)),
            templates = listOf(
                "(procura|procure|busca|busque|acha|ache|encontra|encontre|pesquisa|pesquise|ve|veja) (no|na|em|nos) (gmail|email|e mail|emails|correio) (sobre|a respeito de|por|de|do|da|com|que tenha|que tem)? {assunto}",
                "(procura|procure|busca|busque|acha|ache|encontra|encontre|pesquisa|pesquise|ve|veja|mostra|mostre) (um |o |os |algum )?(email|e mail|emails|e mails) (sobre|a respeito de|com|que tenha|que tem|que fala de|que fala sobre|de|do|da)? {assunto}",
                "(procura|procure|busca|busque|acha|ache|encontra|encontre|pesquisa|pesquise) {assunto} (no|na|em|nos) (gmail|email|e mail|emails|correio)",
                "(emails|e mails|email|e mail) (sobre|a respeito de|com) {assunto}",
            ),
            priority = 3,
        ) { m ->
            gate(m) ?: run {
                val q = m["assunto"]?.trim() ?: return@run NoMatch
                if (PtText.fold(q).split(' ').size > 7) return@run NoMatch
                search(m, q, "Achei estes e-mails sobre \"$q\":", "Não achei nenhum e-mail sobre \"$q\".")
            }
        },
        IntentDef(
            "gmail.bills", "Gmail", "procura faturas, boletos e cobranças recebidos por e-mail",
            templates = listOf(
                "(tenho|tem|chegou|chegaram|ha|recebi) (alguma|algum|alguma nova|algum novo)? (fatura|faturas|boleto|boletos|cobranca|cobrancas|conta de luz|conta de agua|nota fiscal) (nova|novo|novas|novos)? (no email|no gmail|por email|por e mail)?",
                "(qual|quais) (e |sao )?(a |as )?(fatura|faturas|boleto|boletos|cobranca|cobrancas) (que chegou|que chegaram|do email|do gmail|no email|no gmail)",
            ),
            priority = 4,
        ) { m ->
            gate(m) ?: search(m, "(fatura OR boleto OR cobrança OR vencimento) newer_than:30d", "Achei estes e-mails de cobrança dos últimos 30 dias:", "Não achei faturas nem boletos nos últimos 30 dias.")
        },
        IntentDef(
            "gmail.read_last", "Gmail", "lê o último e-mail recebido",
            templates = listOf(
                "(le|leia|ler|me le|me leia|abre|abra|mostra|mostre) (o )?(meu )?(ultimo|mais recente|primeiro) (email|e mail|gmail)",
                "(qual|o que) (e |foi |diz |dizia )?(o )?(ultimo|mais recente) (email|e mail)( que chegou| que recebi)?",
            ),
            priority = 3,
        ) { m -> gate(m) ?: readFirst(m, "in:inbox", "Sua caixa de entrada está vazia.") },
        IntentDef(
            "gmail.read_from", "Gmail", "lê o último e-mail de alguém",
            slots = listOf(SlotSpec("quem", "remetente", true)),
            templates = listOf(
                "(le|leia|ler|me le|me leia|abre|abra|mostra|mostre) (o )?(ultimo |mais recente )?(email|e mail) (do|da|de) {quem}",
                "(o que|oq) (o|a) {quem} (me )?(escreveu|mandou|enviou) (no|por) (email|e mail)",
            ),
            priority = 5,
        ) { m ->
            gate(m) ?: run {
                val who = senderOf(m["quem"]) ?: return@run NoMatch
                readFirst(m, fromQuery(who), "Não achei e-mails de $who.")
            }
        },
    )
}
