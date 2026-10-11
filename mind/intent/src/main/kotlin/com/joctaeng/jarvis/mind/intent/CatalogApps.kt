package com.joctaeng.jarvis.mind.intent

/** Abrir apps, pesquisar dentro de um app, pesquisar na web, e as perguntas do Euno sobre si mesmo. */
internal object CatalogApps {
    private val openVerbs = "abre|abra|abrir|abri|inicia|inicie|iniciar|executa|execute|roda|rode|abre pra mim|vai para|va para|vai pro|vai pra|entra no|entra na|entra em|me leva para|me leva pro|quero abrir|quero usar|preciso do|preciso da|usa|use|usar|liga|ligue|chama|chame"
    private val searchVerbs = "pesquisa|pesquise|pesquisar|procura|procure|procurar|busca|busque|buscar|acha|ache|achar|encontra|encontre|ve|veja"

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "app.open", "Apps", "abre um app instalado pelo nome",
            slots = listOf(SlotSpec("app", "nome do app", true)),
            templates = listOf(
                "($openVerbs) (o |a |os |as |meu |minha )?(app |aplicativo |programa )?(do |da |de )?{app}",
                "(app|aplicativo) (do |da |de )?{app}",
            ),
            priority = -1,
        ) { m ->
            if (!m.has("abrir_app")) return@IntentDef NoMatch
            val raw = m["app"] ?: return@IntentDef NoMatch
            val app = Apps.resolve(raw, m.ctx) ?: return@IntentDef NoMatch
            val name = Apps.toolName(app, m.ctx)
            Kit.run(m, Step("abrir_app", mapOf("nome" to name))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Pronto, abri o ${app.first}.")
            }
        },
        IntentDef(
            "app.search_in", "Apps", "pesquisa dentro de um app (YouTube, Gmail, WhatsApp, Play Store, Spotify...)",
            slots = listOf(SlotSpec("app", "nome do app", true), SlotSpec("consulta", "o que pesquisar", true)),
            templates = listOf(
                "($searchVerbs) {consulta} (no|na|pelo|pela|em|dentro do|dentro da) {app}",
                "($searchVerbs) (no|na|pelo|pela|em|dentro do|dentro da) {app} (por|sobre|a respeito de|pelo|pela|o|a)? {consulta}",
            ),
            priority = 1,
        ) { m ->
            if (!m.has("abrir_app") || !m.has("tela_pesquisar")) return@IntentDef NoMatch
            val q = m["consulta"]?.trim() ?: return@IntentDef NoMatch
            if (PtText.fold(m["app"].orEmpty()) in setOf("google", "internet", "web", "navegador", "chrome", "net", "rede")) return@IntentDef NoMatch
            val app = Apps.resolve(m["app"].orEmpty(), m.ctx) ?: return@IntentDef NoMatch
            Kit.run(m, Step("abrir_app", mapOf("nome" to Apps.toolName(app, m.ctx))), Step("tela_pesquisar", mapOf("texto" to q), settleMs = 2500, retries = 2)) { r ->
                Kit.firstFailure(r)?.let { f ->
                    return@run if (f.step.tool == "abrir_app") Kit.failure(f) else Reply("Abri o ${app.first}, mas não consegui pesquisar \"$q\" lá: ${Kit.humanFailure(f.text).removeSuffix(".")}.", "concerned")
                }
                Reply("Pesquisei \"$q\" no ${app.first}.")
            }
        },
        IntentDef(
            "web.search", "Web", "pesquisa na internet (abre o navegador)",
            slots = listOf(SlotSpec("consulta", "o que pesquisar", true)),
            templates = listOf(
                "($searchVerbs) {consulta} (no google|na internet|na web|no navegador|online|no chrome)",
                "($searchVerbs) (no google|na internet|na web|no navegador|online|no chrome) (por|sobre|a respeito de|o que e|quem e)? {consulta}",
                "(googla|google|pesquisa|pesquise) {consulta}",
            ),
            priority = -1,
        ) { m ->
            if (!m.has("pesquisar_na_web")) return@IntentDef NoMatch
            val q = m["consulta"]?.trim() ?: return@IntentDef NoMatch
            if (PtText.fold(q).split(' ').size > 14 || PtText.fold(q).split(' ').first() in setOf("no", "na", "em", "pelo", "pela", "do", "da", "dentro")) return@IntentDef NoMatch
            if (!m.ctx.online) return@IntentDef Kit.say(m, "Estou sem internet agora, então não consigo pesquisar \"$q\". Quando voltar a conexão, me peça de novo.", "concerned")
            Kit.run(m, Step("pesquisar_na_web", mapOf("consulta" to q))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Abri a pesquisa por \"$q\" no navegador.")
            }
        },
        IntentDef(
            "tasks.list", "Tarefas", "lista as tarefas programadas",
            templates = listOf(
                "(quais|lista|liste|listar|mostra|mostre|ver|veja|me diz|diga) (sao )?(as |minhas |todas as )?(minhas )?(tarefas|rotinas|automacoes) (programadas|agendadas|automaticas)?",
                "(que|quais) (tarefas|rotinas) (eu )?(tenho|programei|estao ligadas|estao rodando)",
            ),
            priority = 2,
        ) { m ->
            if (!m.has("tarefa_listar")) return@IntentDef Kit.noTool(m, "as tarefas programadas")
            Kit.run(m, Step("tarefa_listar")) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val t = ToolText.plain(r[0].text).trim()
                Reply(if (t.isBlank()) "Você não tem tarefas programadas." else "Suas tarefas programadas: $t".take(900), "neutral")
            }
        },
        IntentDef(
            "tasks.stop_all", "Tarefas", "desliga todas as tarefas programadas",
            templates = listOf(
                "(para|pare|parar|desliga|desligue|desligar|pausa|pause|pausar|cancela|cancele|cancelar|desativa|desative) (todas |tudo )?(as |minhas )?(minhas )?(tarefas|rotinas|automacoes)( programadas| agendadas)?",
            ),
            priority = 2,
        ) { m ->
            if (!m.has("tarefa_pausar")) return@IntentDef Kit.noTool(m, "as tarefas programadas")
            Kit.run(m, Step("tarefa_pausar", mapOf("nome" to "todas", "ligar" to false)), confirm = "Desligar todas as tarefas programadas? Diga sim para confirmar.") { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Pronto, desliguei todas as tarefas programadas. Elas continuam salvas; você liga de novo em Meu Euno.")
            }
        },
        IntentDef(
            "self.brain", "Euno", "diz qual cérebro (IA) está em uso agora e se está online",
            templates = listOf(
                "(qual|que) (e |eh )?(o )?(seu )?(cerebro|modelo|ia|inteligencia)( artificial)?( que )?(voce |vc )?(esta |ta )?(usando|rodando|ativo|ativa|em uso)?",
                "(com )?(qual|que) (ia|cerebro|modelo|inteligencia) (voce |vc )?(esta |ta )?(usando|rodando|funcionando|respondendo)",
                "(voce|vc) (esta|ta) (online|offline|conectado|conectada|sem internet|com internet)",
                "(qual|que) (e |eh )?(o )?(seu )?(motor|cerebro) agora",
                "(quem|o que) (esta |ta )?(respondendo|pensando) (agora|por voce|em voce)",
            ),
            priority = 3,
        ) { m ->
            val brain = m.ctx.brainName
            val net = if (m.ctx.online) "O celular está com internet" else "O celular está sem internet"
            val text = if (brain == null) "$net, mas não estou com nenhum cérebro de IA ativo agora. Respondo sozinho o que sei fazer: hora, data, agenda, celular, e-mail e finanças."
            else "Agora estou usando o cérebro $brain. $net. Os pedidos que eu sei fazer sozinho (hora, data, agenda, celular, e-mail, finanças) não precisam de IA nem de internet."
            Kit.say(m, text)
        },
        IntentDef(
            "screen.home", "Celular", "vai para a tela inicial / fecha o app que está aberto",
            templates = listOf(
                "(vai|va|volta|volte|ir|sai|saia|sair|fecha|feche|fechar|leva|leve|me leva) (para|pra|pro|da|do|de|a|o|na|no|ate)? (a |o |minha |meu |nossa )?(tela inicial|tela principal|area principal|pagina inicial|inicio|home|menu principal|area de trabalho)( do celular| do telefone)?",
                "(fecha|feche|fechar|sai|saia|sair|encerra|encerre) (do |da |de |o |a |esse |este |essa |esta |desse |deste )?(app|aplicativo|programa|{app})",
                "(sai|saia|sair) (da|do|de) (tela|app|aplicativo)( do celular| do app)?",
            ),
            priority = 1,
        ) { m ->
            if (!m.has("tela_navegar")) return@IntentDef NoMatch
            val raw = m["app"]
            if (raw != null && Apps.resolve(raw, m.ctx) == null && PtText.fold(raw) !in setOf("app", "aplicativo", "programa", "tela", "esse", "este")) return@IntentDef NoMatch
            Kit.run(m, Step("tela_navegar", mapOf("acao" to "inicio"))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Pronto, voltei para a tela inicial.")
            }
        },
        IntentDef(
            "self.name", "Euno", "diz o nome do assistente e o que ele é",
            templates = listOf("(qual|como) (e|eh) (o )?(seu )?nome", "como (voce )?(se chama|te chamo|posso te chamar)", "quem (e )?(voce|vc)", "(qual|como) (e )?(que )?(voce )?(se chama|e seu nome)", "(qual|como) (o )?(seu )?nome"),
            priority = 2,
        ) { m ->
            val n = m.ctx.selfName.ifBlank { "Euno" }
            Kit.say(m, "Eu sou ${if (n == "Euno") "o Euno" else n}, seu segundo eu digital aqui no celular. Eu cuido da sua agenda, e-mails, finanças, mensagens e do que mais você pedir.", "happy")
        },
        IntentDef(
            "self.hear", "Euno", "responde que está ouvindo",
            templates = listOf(
                "(voce |tu )?(esta|ta|continua|consegue|conseguiu|ainda esta) (me )?(ouvindo|escutando|ouvir|escutar)( agora| direito| bem| mesmo)?",
                "(me )?(ouve|escuta|ouviu|escutou) (agora|direito|bem)?",
                "consegue (falar|responder) (comigo|agora)",
            ),
            priority = 2,
        ) { m -> Kit.say(m, Speak.pick(Kit.seed(m), "Estou ouvindo, sim. Pode falar.", "Te ouço bem. Pode falar.", "Estou aqui, ouvindo."), "happy") },
        IntentDef(
            "self.help", "Euno", "diz o que o Euno sabe fazer sozinho",
            templates = listOf(
                "(o que|oq) (voce|vc) (sabe|consegue|pode|faz|faz de) (fazer|bom)?",
                "(o que|oq) (eu )?(posso|devo) (te )?(pedir|falar|dizer)",
                "(me )?(ajuda|ajude|socorro)",
                "(quais|que) (sao )?(os )?(seus )?(comandos|poderes|recursos|funcoes|habilidades)",
                "como (te )?(uso|usar|funciona voce|voce funciona)",
                "o que (e )?(possivel|da pra|da para) (voce )?(fazer|pedir)",
                "(a )?lista (completa )?(do que|de tudo que|de tudo o que) (voce )?(ja )?(consegue|sabe|pode) (abrir|fazer)?",
            ),
            priority = 1,
        ) { m ->
            val parts = mutableListOf<String>()
            parts += "hora, data, feriados e contas"
            if (m.has("agenda_consultar")) parts += "agenda (\"o que tenho amanhã\", \"marca dentista terça às 15h\", \"apaga a reunião\")"
            if (m.has("criar_alarme")) parts += "alarmes e timers"
            if (m.has("estado_do_celular")) parts += "bateria, internet e espaço"
            if (m.has("notificacoes_ler")) parts += "notificações"
            if (m.has("lanterna")) parts += "lanterna e Não perturbe"
            if (m.has("gmail_buscar") && m.ctx.gmailConnected) parts += "e-mails do Gmail"
            if (m.has("whatsapp_mensagem")) parts += "mensagens de WhatsApp"
            if (m.hasPrefixTool("joca_financas")) parts += "Joca Finanças (saldo, gastos, fatura, lançar)"
            if (m.hasPrefixTool("edumath")) parts += "EduMath (turmas, notas, perguntas)"
            parts += "abrir e pesquisar em apps"
            Kit.say(m, "Sem precisar de IA nem de internet eu faço: " + Speak.list(parts) + ". Para o resto, converso e uso a IA." , "happy")
        },
    )

    private fun M.hasPrefixTool(prefix: String) = ctx.hasPrefix(prefix)
}
