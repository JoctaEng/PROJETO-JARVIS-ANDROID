package com.joctaeng.jarvis.mind.intent

/** O celular: bateria, internet, lanterna, alarme, timer, Não perturbe, notificações e navegação. */
internal object CatalogPhone {
    private fun days(rec: RecSlot?): List<String> = rec?.days?.sorted()?.map { Kit.dayCodes[it - 1] }.orEmpty()
    private fun recText(rec: RecSlot?): String = when {
        rec == null -> ""
        rec.days.size == 7 -> " todo dia"
        rec.days == (1..5).toSet() -> " de segunda a sexta"
        rec.days == setOf(6, 7) -> " nos fins de semana"
        else -> " " + Speak.list(rec.days.sorted().map { "às " + listOf("segundas", "terças", "quartas", "quintas", "sextas", "sábados", "domingos")[it - 1] })
    }

    private fun status(r: StepResult) = ToolText.obj(r.text)

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "phone.battery", "Celular", "diz a bateria do celular",
            templates = listOf(
                "(qual|como esta|quanto tem de|quanto de|quanta) (a |de )?bateria (do celular|do telefone|esta|ta|tem)?",
                "(estou|to|ta|esta) com quanto de bateria",
                "quanto (eu )?(tenho|tem|resta|sobrou|falta) de bateria",
                "bateria (do celular)?",
                "(tem|ainda tem|tenho) bateria (suficiente)?",
                "quanto (falta|resta) de bateria",
            ),
        ) { m ->
            if (!m.has("estado_do_celular")) return@IntentDef Kit.noTool(m, "o estado do celular")
            Kit.run(m, Step("estado_do_celular")) { r ->
                val f = Kit.firstFailure(r)
                if (f != null) return@run Kit.failure(f)
                val o = status(r[0])
                val pct = ToolText.str(o, "bateria_percentual")?.toIntOrNull()
                val charging = ToolText.str(o, "carregando") == "true"
                if (pct == null || pct < 0) return@run Reply("Não consegui ler a bateria agora.", "concerned")
                Reply(
                    "A bateria está em $pct%" + (if (charging) ", carregando." else ".") + if (!charging && pct <= 20) " Está baixa: melhor carregar." else "",
                    if (pct <= 20 && !charging) "concerned" else "happy",
                )
            }
        },
        IntentDef(
            "phone.internet", "Celular", "diz se há internet e qual conexão",
            templates = listOf(
                "(estou|to|ta|esta|tem) (conectado|online|com internet|com wifi|com wi fi|com dados|sem internet)",
                "(tem|ha) internet (ai|aqui|no celular)?",
                "(a )?internet (esta|ta) (funcionando|ligada|boa|ok)",
                "(qual|que) (e a |a )?(conexao|rede) (estou usando|esta ativa|do celular)?",
                "(estou|to) no (wifi|wi fi|4g|5g|dados moveis)",
            ),
        ) { m ->
            if (!m.has("estado_do_celular")) return@IntentDef Kit.noTool(m, "o estado do celular")
            Kit.run(m, Step("estado_do_celular")) { r ->
                val f = Kit.firstFailure(r)
                if (f != null) return@run Kit.failure(f)
                val o = status(r[0])
                val net = ToolText.str(o, "conexao").orEmpty()
                val works = ToolText.str(o, "internet_funcionando") == "true"
                Reply(
                    when {
                        net == "sem conexão" || net.isBlank() -> "Estou sem internet agora: só funciono com o que está no celular."
                        works -> "Você está no ${if (net == "Wi-Fi") "Wi-Fi" else net} e a internet está funcionando."
                        else -> "Está conectado ao $net, mas a internet não está respondendo."
                    },
                    if (works) "happy" else "concerned",
                )
            }
        },
        IntentDef(
            "phone.storage", "Celular", "diz o espaço livre do celular",
            templates = listOf(
                "(quanto|qual) (de )?(espaco|armazenamento|memoria) (livre |disponivel )?(eu )?(tenho|tem|sobrou|resta)? (livre |disponivel )?(no celular|no telefone)?",
                "(tenho|tem) espaco (no celular|livre)?",
                "espaco (livre|disponivel|no celular)",
            ),
        ) { m ->
            if (!m.has("estado_do_celular")) return@IntentDef Kit.noTool(m, "o estado do celular")
            Kit.run(m, Step("estado_do_celular")) { r ->
                val f = Kit.firstFailure(r)
                if (f != null) return@run Kit.failure(f)
                val gb = ToolText.str(status(r[0]), "espaco_livre_gb")?.toLongOrNull()
                Reply(if (gb == null || gb < 0) "Não consegui ler o espaço livre." else "Você tem $gb GB livres no celular.")
            }
        },
        IntentDef(
            "phone.status", "Celular", "resumo do celular: bateria, conexão e espaço",
            templates = listOf("(como esta|estado|status|situacao) (d)?o (celular|telefone|aparelho)", "(como|qual) (esta|e) o estado do celular"),
        ) { m ->
            if (!m.has("estado_do_celular")) return@IntentDef Kit.noTool(m, "o estado do celular")
            Kit.run(m, Step("estado_do_celular")) { r ->
                val f = Kit.firstFailure(r)
                if (f != null) return@run Kit.failure(f)
                val o = status(r[0])
                val pct = ToolText.str(o, "bateria_percentual")
                val charging = ToolText.str(o, "carregando") == "true"
                val net = ToolText.str(o, "conexao")
                val gb = ToolText.str(o, "espaco_livre_gb")
                Reply("Bateria em $pct%${if (charging) " (carregando)" else ""}, conexão: $net, $gb GB livres.")
            }
        },
        IntentDef(
            "phone.flashlight_on", "Celular", "liga a lanterna",
            templates = listOf("(liga|ligue|ligar|acende|acenda|acender|ativa|ative|ativar|usa|use|coloca|bota) (a )?lanterna", "lanterna (ligada|acesa)?", "(quero|preciso de|preciso da) (a )?lanterna"),
        ) { m ->
            if (!m.has("lanterna")) return@IntentDef Kit.noTool(m, "a lanterna")
            Kit.run(m, Step("lanterna", mapOf("ligar" to true))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(Speak.pick(Kit.seed(m), "Lanterna ligada.", "Pronto, lanterna acesa.", "Acendi a lanterna."))
            }
        },
        IntentDef(
            "phone.flashlight_off", "Celular", "desliga a lanterna",
            templates = listOf("(desliga|desligue|desligar|apaga|apague|apagar|desativa|desative|desativar|fecha|tira|tire) (a )?lanterna", "lanterna (desligada|apagada)"),
            priority = 2,
        ) { m ->
            if (!m.has("lanterna")) return@IntentDef Kit.noTool(m, "a lanterna")
            Kit.run(m, Step("lanterna", mapOf("ligar" to false))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Lanterna desligada.")
            }
        },
        IntentDef(
            "phone.alarm", "Relógio", "cria um alarme (hora, rótulo, dias da semana)",
            slots = listOf(SlotSpec("time", "a hora HH:mm", true), SlotSpec("label", "nome do alarme")),
            templates = listOf(
                "(me )?(acorda|acorde|acordar|desperta|desperte|despertar|chama|chame) (as|pras|pelas|umas)? <T> <R?> {rotulo?}",
                "(cria|crie|criar|coloca|coloque|poe|ponha|programa|programe|define|defina|configura|configure|marca|marque|seta|ativa|ative|faz|faca|bota|agenda|agende)? (um|o|outro)? alarme (para|pra|pras|as|de|pelas|umas)? <T> <R?> {rotulo?}",
                "alarme (para|pra|pras|as|de|pelas|umas)? <T> <R?> {rotulo?}",
                "(cria|crie|criar|coloca|coloque|poe|ponha|programa|programe|define|defina|bota)? (um|o)? alarme (para|pra|as|de)? {rotulo?}",
                "<T> <R?> alarme {rotulo?}",
            ),
        ) { m ->
            if (!m.has("criar_alarme")) return@IntentDef Kit.noTool(m, "o alarme")
            val t = m.time
            if (t == null) {
                return@IntentDef Kit.ask(m, "Para que horas?") { ex2, _ ->
                    val t2 = ex2.time ?: Kit.bareHour(ex2)
                    if (t2 == null) null else CatalogPhone.defs.first { it.id == "phone.alarm" }.build(M(Kit.merge(m.ex, ex2.copy(time = t2)), m.caps, m.ctx, m.intentId))
                }
            }
            val label = m["rotulo"]?.takeIf { it.length <= 40 }
            val rec = m.ex.rec
            val args = mutableMapOf<String, Any?>("hora" to t.hour, "minuto" to t.minute)
            label?.let { args["rotulo"] = Kit.cap(it) }
            if (rec != null) args["dias"] = days(rec)
            Kit.run(m, Step("criar_alarme", args)) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val part = if (t.ambiguous && t.hour in 1..11) " da manhã" else ""
                Reply("${Kit.ok(m)}, alarme criado para ${Speak.at(t.hour, t.minute).removePrefix("às ").removePrefix("à ").let { "as $it" }.replace("as ao meio-dia", "o meio-dia").replace("as à", "à")}$part${recText(rec)}.".replace("para as ao", "para o"))
            }
        },
        IntentDef(
            "phone.timer", "Relógio", "inicia um timer (contagem regressiva)",
            slots = listOf(SlotSpec("duration_min", "minutos", true), SlotSpec("label", "nome do timer")),
            templates = listOf(
                "(cria|crie|criar|coloca|coloque|poe|ponha|inicia|inicie|iniciar|programa|programe|liga|ligue|faz|faca|marca|marque|bota|comeca|comece)? (um|o|uma)? (timer|temporizador|cronometro|contagem regressiva) (de|para|pra|com|por)? <DUR> {rotulo?}",
                "(timer|temporizador|cronometro) (de|para|pra|com|por)? <DUR>",
                "<DUR> (de|no|num)? (timer|temporizador|cronometro)",
                "(me )?(avisa|avise|lembra|lembre|chama|chame|desperta|acorda|alerta) (em|daqui a|daqui|dentro de|daqui a uns) <DUR> {rotulo?}",
                "(em|daqui a|daqui|dentro de) <DUR> (me )?(avisa|avise|lembra|lembre|chama|desperta|acorda|alerta) {rotulo?}",
                "(cria|crie|coloca|coloque|inicia|inicie|programa|programe)? (um|o)? (timer|temporizador|cronometro)",
            ),
        ) { m ->
            if (!m.has("criar_timer")) return@IntentDef Kit.noTool(m, "o timer")
            val d = m.dur
            if (d == null || d.seconds <= 0) {
                return@IntentDef Kit.ask(m, "De quanto tempo?") { ex2, _ ->
                    if (ex2.dur == null) null else CatalogPhone.defs.first { it.id == "phone.timer" }.build(M(Kit.merge(m.ex, ex2), m.caps, m.ctx, m.intentId))
                }
            }
            val label = m["rotulo"]?.takeIf { it.length <= 40 && PtText.fold(it) !in setOf("de", "para", "pra") }
            Kit.run(m, Step("criar_timer", buildMap { put("segundos", d.seconds.coerceIn(1, 86_400)); label?.let { put("rotulo", Kit.cap(it)) } })) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("${Kit.ok(m)}, timer de ${Speak.duration(d.seconds)} iniciado" + (label?.let { " ($it)" } ?: "") + ".")
            }
        },
        IntentDef(
            "phone.dnd_on", "Celular", "liga o Não perturbe (silêncio)",
            templates = listOf(
                "(ativa|ative|ativar|liga|ligue|ligar|coloca|coloque|poe|ponha|entra|entre|bota) (no|o|em|na) (modo)? (nao perturbe|silencioso|silencio|foco)",
                "(silencia|silencie|silenciar|muta|mute|cala|cale) (o )?(celular|telefone|aparelho)",
                "modo (nao perturbe|silencioso)",
                "nao perturbe",
            ),
        ) { m ->
            if (!m.has("nao_perturbe")) return@IntentDef Kit.noTool(m, "o Não perturbe")
            Kit.run(m, Step("nao_perturbe", mapOf("ligar" to true))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Não perturbe ligado. Só as prioridades tocam.")
            }
        },
        IntentDef(
            "phone.dnd_off", "Celular", "desliga o Não perturbe",
            templates = listOf(
                "(desativa|desative|desativar|desliga|desligue|desligar|tira|tire|sai|saia|sair|encerra|encerre) (do|o|de|no)? (modo)? (nao perturbe|silencioso|silencio|foco)",
                "(volta|volte|devolve|liga|ligue) (o )?(som|sino|toque) (do celular)?",
                "(tira|tire) (o celular )?do (silencioso|mudo)",
            ),
            priority = 2,
        ) { m ->
            if (!m.has("nao_perturbe")) return@IntentDef Kit.noTool(m, "o Não perturbe")
            Kit.run(m, Step("nao_perturbe", mapOf("ligar" to false))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Não perturbe desligado. O som voltou.")
            }
        },
        IntentDef(
            "phone.notifications", "Celular", "lê as notificações que chegaram",
            templates = listOf(
                "(o que|quais|que) (foi que )?(chegou|chegaram|tem|apareceu|teve) (de novo|de novidade|de notificacao|de notificacoes|no celular|ai)?",
                "(tenho|tem|chegou|chegaram) (alguma |alguma coisa de |algo de )?(notificacao|notificacoes|novidade|novidades|mensagem nova|mensagens novas|aviso|avisos)",
                "(le|leia|ler|me le|me leia|mostra|mostre|ver|veja|confere|confira|verifica|verifique|checa|cheque) (as |minhas |meus |os )?(minhas )?(notificacoes|notificacao|avisos)",
                "(minhas|as) notificacoes",
                "novidades (no celular)?",
                "alguma novidade (no celular)?",
            ),
        ) { m ->
            if (!m.has("notificacoes_ler")) return@IntentDef Kit.noTool(m, "as notificações")
            Kit.run(m, Step("notificacoes_ler", mapOf("quantas" to 15))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(Replies.notifications(ToolText.notifications(r[0].text)))
            }
        },
        IntentDef(
            "phone.notifications_app", "Celular", "lê as notificações de um app (WhatsApp, Gmail, Instagram, banco...)",
            slots = listOf(SlotSpec("app", "nome do app", true)),
            templates = listOf(
                "(o que|quais|que) (foi que )?(chegou|chegaram|tem|apareceu|teve) (de novo |de notificacao |de mensagem )?(no|na|do|da|de) {app}",
                "(tenho|tem|chegou|chegaram) (alguma |algum |alguma coisa )?(notificacao|notificacoes|novidade|novidades|mensagem|mensagens|aviso|avisos) (nova|novas|novo|novos)? (no|na|do|da|de|em) {app}",
                "(le|leia|ler|mostra|mostre|ver|veja|confere|confira|verifica|verifique) (as )?(notificacoes|mensagens|avisos) (do|da|de|no|na) {app}",
                "(notificacoes|novidades|mensagens novas) (do|da|de|no|na) {app}",
            ),
            priority = 1,
        ) { m ->
            if (!m.has("notificacoes_ler")) return@IntentDef Kit.noTool(m, "as notificações")
            val raw = m["app"] ?: return@IntentDef NoMatch
            val app = Apps.resolve(raw, m.ctx)
            if (app == null && PtText.fold(raw).split(' ').all { it in setOf("novo", "nova", "novos", "novas", "novidade", "novidades", "celular", "telefone", "ai", "aqui") }) return@IntentDef NoMatch
            val filter = app?.first ?: raw
            Kit.run(m, Step("notificacoes_ler", mapOf("app" to filter, "quantas" to 15))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(Replies.notifications(ToolText.notifications(r[0].text), appFilter = filter))
            }
        },
        IntentDef(
            "phone.nav_notifications", "Celular", "abre a barra de notificações do Android",
            templates = listOf("(abre|abra|abrir|puxa|puxe|puxar|desce|desca|mostra|mostre) (a |as )?(barra de )?(notificacoes|notificacao) (do android|do celular)?"),
            priority = 1,
        ) { m ->
            if (!m.has("tela_navegar")) return@IntentDef Kit.noTool(m, "o controle do celular")
            Kit.run(m, Step("tela_navegar", mapOf("acao" to "notificacoes"))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Pronto, abri as notificações.")
            }
        },
        IntentDef(
            "phone.nav_recents", "Celular", "abre os apps recentes",
            templates = listOf("(abre|abra|abrir|mostra|mostre|vai pros|vai para os|ver|veja) (os )?(apps |aplicativos )?(recentes|abertos)", "(abre|abra|mostra|mostre) (a )?multitarefa"),
        ) { m ->
            if (!m.has("tela_navegar")) return@IntentDef Kit.noTool(m, "o controle do celular")
            Kit.run(m, Step("tela_navegar", mapOf("acao" to "recentes"))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Pronto, apps recentes.")
            }
        },
        IntentDef(
            "phone.nav_quick", "Celular", "abre as configurações rápidas (Wi-Fi, brilho...)",
            templates = listOf("(abre|abra|abrir|puxa|puxe|mostra|mostre) (as |o )?(configuracoes rapidas|painel rapido|central de controle|atalhos rapidos)"),
        ) { m ->
            if (!m.has("tela_navegar")) return@IntentDef Kit.noTool(m, "o controle do celular")
            Kit.run(m, Step("tela_navegar", mapOf("acao" to "configuracoes_rapidas"))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Pronto, configurações rápidas.")
            }
        },
    )
}
