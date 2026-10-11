package com.joctaeng.jarvis.mind.intent

/** WhatsApp (mandar mensagem com confirmação no "Enviar", abrir conversa, quem escreveu) e Instagram (seções do app). */
internal object CatalogChat {
    private val sendVerbs = "manda|mande|mandar|envia|envie|enviar|escreve|escreva|escrever|diz|diga|dizer|fala|fale|falar|avisa|avise|avisar|pergunta|pergunte|responde|responda|responder"
    private val sepWords = "dizendo|falando|avisando|perguntando|informando|respondendo|escrevendo|que"
    private val appTail = Regex("""(?i)\s+(?:no|pelo|pela|via|em)\s+(?:o\s+)?(?:zap|zapzap|whats|whatsapp|watsapp|wpp)(?:\s+(?:business|comercial))?\s*$""")
    private val nonWhats = Regex("""\b(telegram|instagram|insta|direct|email|e mail|gmail|sms|ligacao|ligar|facebook|messenger|linkedin)\b""")
    private val preps = setOf("para", "pra", "pro", "a", "ao", "o", "mensagem", "msg", "uma", "um", "zap", "whats", "whatsapp", "de", "que", "dizendo")

    private fun contactOf(raw: String?): String? {
        val c = raw?.replace(appTail, "")?.trim()?.trim(',', '.')?.replace(Regex("""(?i)^(a|o|para|pra|pro|ao)\s+"""), "")?.trim()
        return c?.takeIf { it.length >= 2 && PtText.fold(it) !in preps }
    }

    private fun business(ex: Extracted) = Regex("""\b(business|comercial)\b""").containsMatchIn(PtText.fold(ex.raw))

    private fun capFirst(s: String) = s.trim().replaceFirstChar { it.uppercase() }

    /** A ferramenta achou mais de um contato: pergunta qual e tenta de novo com o nome inteiro. */
    private fun whichContact(m: M, failure: String, retry: (String) -> Outcome): Reply? {
        if (!failure.contains("Mais de um contato")) return null
        val names = failure.substringAfter(": ", "").substringBefore(". Pergunte").split(", ").map { it.trim() }.filter { it.isNotBlank() }
        if (names.size < 2) return null
        return Reply(
            "Tenho mais de um contato assim: ${Speak.list(names)}. Qual deles?", "neutral",
            Ask("Qual contato?", m.intentId) { ex, _ ->
                val said = PtText.fold(Kit.bareText(ex))
                val chosen = names.singleOrNull { n -> PtText.fold(n).split(' ').all { w -> w in said.split(' ') } || said in PtText.fold(n) && said.length >= 3 }
                    ?: names.singleOrNull { n -> said.split(' ').any { w -> w.length >= 3 && PtText.fold(n).split(' ').contains(w) } }
                chosen?.let { retry(it) }
            },
        )
    }

    private fun sendMessage(m: M, contact: String, message: String, bus: Boolean): Outcome {
        val args = buildMap<String, Any?> { put("contato", contact); put("mensagem", message); if (bus) put("business", true) }
        return Kit.plan(m, listOf(Step("whatsapp_mensagem", args))) { r ->
            val f = Kit.firstFailure(r)
            if (f != null) {
                return@plan Finish(whichContact(m, f.text) { full -> sendMessage(m, full, message, bus) } ?: Kit.failure(f))
            }
            if (!m.has("tela_tocar")) {
                return@plan Finish(Reply("Escrevi a mensagem para $contact no WhatsApp. Falta tocar em Enviar: ligue o controle do celular para eu enviar, ou toque você.", "neutral"))
            }
            More(
                Plan.simple(Step("tela_tocar", mapOf("nome" to "Enviar"), settleMs = 2500, retries = 2)) { r2 ->
                    val f2 = Kit.firstFailure(r2)
                    when {
                        f2 == null -> Reply("Mensagem enviada para $contact: \"$message\".", "happy")
                        f2.text.contains("não confirmou", true) -> Reply("Certo, deixei a mensagem escrita para $contact e não enviei.", "neutral")
                        else -> Reply("Escrevi a mensagem para $contact, mas não consegui tocar em Enviar (${Kit.humanFailure(f2.text).removeSuffix(".")}). Toque você no botão de enviar.", "concerned")
                    }
                },
            )
        }
    }

    private fun whatsAvailable(m: M): Outcome? = when {
        !m.has("whatsapp_mensagem") -> Kit.noTool(m, "o WhatsApp")
        m.ctx.apps.none { it.second.startsWith("com.whatsapp") } && m.ctx.apps.isNotEmpty() -> Kit.say(m, "Não achei o WhatsApp instalado neste celular.", "concerned")
        else -> null
    }

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "wa.send", "WhatsApp", "manda mensagem de WhatsApp para um contato (confirma o Enviar na tela)",
            slots = listOf(SlotSpec("contato", "nome do contato", true), SlotSpec("mensagem", "texto da mensagem, na voz do usuário", true)),
            templates = listOf(
                "($sendVerbs) (uma |a )?(mensagem|msg|zap|whats|whatsapp|um zap|um whats)? (no zap|no whatsapp|no whats|pelo zap|pelo whatsapp)? (pra|para|pro|a|ao) (a |o )?{contato} ($sepWords) {mensagem}",
                "($sendVerbs) (um |uma )?(oi|ola|bom dia|boa tarde|boa noite|abraco|beijo|parabens|obrigado|obrigada|feliz aniversario) (pra|para|pro|ao|a) (a |o )?{contato}",
                "($sendVerbs) {mensagem} (pra|para|pro|ao) (a |o )?{contato} (no zap|no whatsapp|no whats|pelo zap|pelo whatsapp)?",
                "($sendVerbs) (uma |a )?(mensagem|msg) (no zap|no whatsapp|no whats|pelo zap|pelo whatsapp)? (pra|para|pro|a|ao) (a |o )?{contato}",
                "(whatsapp|zap|whats) (pra|para|pro|a|ao|do|da)? {contato} ($sepWords) {mensagem}",
                "(avisa|avise|avisar|pergunta|pergunte|perguntar|responde|responda|responder|fala com|fale com|diz a|diga a|manda um recado para|mande um recado para) (a |o )?{contato} (que|dizendo|se|pra|para) {mensagem}",
            ),
            priority = 4,
        ) { m ->
            whatsAvailable(m)?.let { return@IntentDef it }
            val said = PtText.fold(m.ex.raw)
            if (nonWhats.containsMatchIn(said)) return@IntentDef NoMatch
            // "manda pra Maria" sem dizer WhatsApp, mas com palavras de e-mail/sms, já saiu acima; aqui o pedido é de mensagem.
            val contact = contactOf(m["contato"]) ?: return@IntentDef NoMatch
            val hello = Regex("""^(?:\w+\s+)(?:uma |um )?(oi|ola|bom dia|boa tarde|boa noite|abraco|beijo|parabens|obrigado|obrigada|feliz aniversario)\b""", RegexOption.IGNORE_CASE)
                .find(PtText.fold(Kit.bareText(m.ex)))?.groupValues?.get(1)
            val rawMsg = (m["mensagem+"] ?: m["mensagem"])?.trim()?.trim('"', '“', '”')?.replace(Regex("""(?i)^(que|:)\s+"""), "")
            val msg = (hello ?: rawMsg)?.takeIf { it.isNotBlank() && PtText.fold(it) !in preps }
            val bus = business(m.ex)
            if (msg == null) {
                return@IntentDef Kit.ask(m, "O que digo para $contact?") { ex, _ ->
                    val t = ex.raw.trim().takeIf { it.isNotBlank() && Kit.yes(ex) != true } ?: return@ask null
                    sendMessage(m, contact, capFirst(t), bus)
                }
            }
            sendMessage(m, contact, capFirst(msg), bus)
        },
        IntentDef(
            "wa.open_chat", "WhatsApp", "abre a conversa de WhatsApp com um contato",
            slots = listOf(SlotSpec("contato", "nome do contato", true)),
            templates = listOf(
                "(abre|abra|abrir|vai para|va para|vai pra|me leva para|chama|chame|fala com|fale com|conversa com|converse com) (a |o |minha |meu )?(conversa|chat|whatsapp|zap|whats)? (com|da|do|de) (a |o )?{contato}",
                "(abre|abra|abrir) (o )?(zap|whatsapp|whats) (da|do|de) (a |o )?{contato}",
                "(abre|abra|abrir|chama|chame) (a |o )?{contato} (no|pelo) (zap|whatsapp|whats)",
            ),
            priority = 3,
        ) { m ->
            whatsAvailable(m)?.let { return@IntentDef it }
            if (nonWhats.containsMatchIn(PtText.fold(m.ex.raw))) return@IntentDef NoMatch
            val contact = contactOf(m["contato"]) ?: return@IntentDef NoMatch
            val args = buildMap<String, Any?> { put("contato", contact); put("mensagem", ""); if (business(m.ex)) put("business", true) }
            Kit.plan(m, listOf(Step("whatsapp_mensagem", args))) { r ->
                val f = Kit.firstFailure(r)
                if (f != null) Finish(whichContact(m, f.text) { full -> Run(Plan.simple(Step("whatsapp_mensagem", args + ("contato" to full))) { r2 -> Kit.firstFailure(r2)?.let { Kit.failure(it) } ?: Reply("Pronto, abri a conversa com $full.") }, m.intentId) } ?: Kit.failure(f))
                else Finish(Reply("Pronto, abri a conversa com $contact no WhatsApp."))
            }
        },
        IntentDef(
            "wa.who", "WhatsApp", "diz quem mandou mensagem no WhatsApp (pelas notificações)",
            templates = listOf(
                "quem (me )?(mandou|chamou|escreveu|falou|mandaram) (mensagem|msg|no zap|no whatsapp|no whats|agora|pra mim|por mensagem)? <D?>",
                "(tem|tenho|chegou|chegaram) (alguma |algum )?(mensagem|mensagens|msg|recado|recados|zap) (nova|novas|nao lida|nao lidas)? (no zap|no whatsapp|no whats|pra mim|de alguem)?",
                "(o que|oq) (chegou|tem) (no|de) (zap|whatsapp|whats)",
            ),
            priority = 3,
        ) { m ->
            if (!m.has("notificacoes_ler")) return@IntentDef Kit.noTool(m, "as notificações")
            // sem dizer o app, "mensagem" pode ser e-mail: só vale com zap/whats ou com "quem me mandou".
            val said = PtText.fold(m.ex.raw)
            if (!Regex("""\b(zap|whats|whatsapp|quem)\b""").containsMatchIn(said) && !Regex("""\bmensagens?\b""").containsMatchIn(said)) return@IntentDef NoMatch
            Kit.run(m, Step("notificacoes_ler", mapOf("app" to "WhatsApp", "quantas" to 20))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val list = ToolText.notifications(r[0].text)
                if (list.isEmpty()) return@run Reply("Nenhuma mensagem nova no WhatsApp nas notificações.")
                val by = list.groupBy { it.title }.entries.sortedByDescending { it.value.size }
                Reply("No WhatsApp, " + Speak.list(by.take(6).map { (who, items) -> if (items.size > 1) "$who mandou ${items.size} mensagens" else "$who mandou: ${items.first().text.take(80).trim()}" }) + ".", "happy")
            }
        },
        IntentDef(
            "wa.read_from", "WhatsApp", "o que uma pessoa mandou (nas notificações do WhatsApp)",
            slots = listOf(SlotSpec("contato", "nome", true)),
            templates = listOf(
                "(o que|oq|que) (a |o )?{contato} (me )?(mandou|falou|disse|escreveu|perguntou|respondeu)",
                "(le|leia|ler) (a |as )?(mensagem|mensagens) (da|do|de) {contato}",
                "(a |o )?{contato} (me )?(mandou|escreveu|respondeu) (alguma coisa|algo|mensagem)",
            ),
            priority = 3,
        ) { m ->
            if (!m.has("notificacoes_ler")) return@IntentDef Kit.noTool(m, "as notificações")
            val who = contactOf(m["contato"]) ?: return@IntentDef NoMatch
            if (nonWhats.containsMatchIn(PtText.fold(m.ex.raw)) || PtText.fold(who).split(' ').size > 3) return@IntentDef NoMatch
            Kit.run(m, Step("notificacoes_ler", mapOf("app" to "WhatsApp", "quantas" to 30))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val key = PtText.fold(who)
                val mine = ToolText.notifications(r[0].text).filter { PtText.fold(it.title).contains(key) }
                if (mine.isEmpty()) return@run Reply("Não vi nenhuma notificação de $who no WhatsApp. Se quiser, abro a conversa.")
                Reply("$who escreveu: " + Speak.list(mine.take(4).map { "\"${it.text.take(100).trim()}\" (às ${it.time})" }) + ".", "happy")
            }
        },
        IntentDef(
            "ig.section", "Instagram", "abre uma parte do Instagram (mensagens, reels, perfil, início, explorar)",
            slots = listOf(SlotSpec("secao", "mensagens, reels, perfil, inicio ou explorar", true)),
            templates = listOf(
                "(abre|abra|abrir|vai para|va para|vai pro|me leva para|mostra|mostre|ver|veja|entra|entra em|entra nos|entra no) (as |os |o |a |meu |minha |minhas |meus )?{secao} (do|no|de|pelo|na|em) (instagram|insta|ig)",
                "(abre|abra|abrir|vai para|va para|me leva para|entra no) (o )?(instagram|insta|ig) (e |na |no |nos |nas |em )?(abre |vai para |mostra )?(aba |tela |secao )?(de |dos |das |do |da )?{secao}",
            ),
            priority = 4,
        ) { m ->
            if (!m.has("abrir_app")) return@IntentDef NoMatch
            val said = PtText.fold(m["secao"].orEmpty())
            val label = when {
                Regex("""\b(dm|dms|direct|mensagens|mensagem|conversas|inbox)\b""").containsMatchIn(said) -> "Mensagens"
                "reels" in said || "reel" in said -> "Reels"
                "perfil" in said -> "Perfil"
                Regex("""\b(feed|inicio|pagina inicial|home)\b""").containsMatchIn(said) -> "Página inicial"
                Regex("""\b(explorar|pesquisa|busca|lupa)\b""").containsMatchIn(said) -> "Pesquisar e explorar"
                else -> return@IntentDef NoMatch
            }
            val app = Apps.find(m.ctx, "instagram") ?: return@IntentDef Kit.say(m, "Não achei o Instagram instalado neste celular.", "concerned")
            val open = Step("abrir_app", mapOf("nome" to Apps.toolName(app, m.ctx)))
            if (!m.has("tela_tocar")) return@IntentDef Kit.run(m, open) { r -> Kit.firstFailure(r)?.let { Kit.failure(it) } ?: Reply("Abri o Instagram. Para ir direto a $label, ligue o controle do celular.") }
            Kit.run(m, open, Step("tela_tocar", mapOf("nome" to label), settleMs = 2500, retries = 2)) { r ->
                Kit.firstFailure(r)?.let { f ->
                    return@run if (f.step.tool == "abrir_app") Kit.failure(f) else Reply("Abri o Instagram, mas não achei o botão $label na tela.", "concerned")
                }
                Reply("Pronto, $label do Instagram.")
            }
        },
    )
}
