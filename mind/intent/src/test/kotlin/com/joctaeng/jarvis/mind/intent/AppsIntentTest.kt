package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppsIntentTest {
    private val ctx = Fixtures.ctx()
    private fun e() = Fixtures.engine()
    private fun run(en: IntentEngine, s: String, c: Ctx = ctx): Run = en.handle(s, c) as? Run ?: error("sem plano para: $s → ${en.handle(s, c)}")
    private fun first(s: String) = run(e(), s).plan.steps.first()

    private val mails = "- [id a1] 11/10 · Banco Azul · Fatura disponível — vence dia 20\n- [id a2] 10/10 · Maria Souza · Reunião de pais — confirmada"

    // ---- Gmail
    @Test fun gmailUnread() {
        for (s in listOf("tenho e-mail novo", "chegou algum email", "lê meus emails", "tem email não lido", "o que chegou no gmail")) {
            val out = run(e(), s)
            assertEquals("gmail.unread", out.intentId, s)
            assertEquals("is:unread in:inbox", out.plan.steps.first().args["consulta"])
        }
        val r = Fixtures.play(run(e(), "tenho e-mail novo")) { Fixtures.ok(it, mails) }
        assertTrue(r.text.contains("de Banco Azul"), r.text)
    }

    @Test fun gmailFromAndSearch() {
        assertEquals("{from:\"Maria\" \"Maria\"}", first("chegou email da Maria").args["consulta"])
        assertEquals("fatura", first("procura no gmail fatura").args["consulta"])
        assertEquals("matrícula", first("procura um email sobre matrícula").args["consulta"])
        assertEquals("gmail.bills", run(e(), "chegou alguma fatura no email").intentId)
    }

    @Test fun gmailReadLastChains() {
        val out = run(e(), "lê o último email")
        val r = Fixtures.play(out) { st ->
            if (st.tool == "gmail_buscar") Fixtures.ok(st, mails) else Fixtures.ok(st, """{"de":"Banco Azul","assunto":"Fatura","data":"11/10","texto":"Sua fatura vence dia 20."}""")
        }
        assertTrue(r.text.contains("Sua fatura vence dia 20."), r.text)
    }

    @Test fun gmailNotConnectedIsHonest() {
        val out = e().handle("tenho e-mail novo", Fixtures.ctx(gmail = false))
        assertTrue(out is Say && out.text.contains("Conectar Gmail"), "$out")
    }

    // ---- Joca Finanças
    private val summary = "Contas:\n- Itaú (Itaú): R\$ 1.500,00\nTotal em contas: R\$ 2.100,77\nCartão Nubank: fatura do mês R\$ 850,10, limite livre R\$ 3.000,00\nMês 2026-10: receitas recebidas R\$ 5.000,00, despesas pagas R\$ 1.200,50, despesas a pagar R\$ 640,00"

    @Test fun financeBalance() {
        val out = run(e(), "qual é o meu saldo")
        assertEquals("fin.balance", out.intentId)
        val r = Fixtures.play(out) { Fixtures.ok(it, summary) }
        assertTrue(r.text.startsWith("Você tem R\$ 2.100,77 nas contas."), r.text)
        assertTrue(r.text.contains("ainda tem R\$ 640,00 a pagar"), r.text)
    }

    @Test fun financeSpent() {
        val out = run(e(), "quanto gastei com mercado esse mês")
        assertEquals("fin.spent", out.intentId)
        assertEquals("mercado", out.plan.steps.first().args["busca"])
        assertEquals("2026-10", out.plan.steps.first().args["mes"])
        val r = Fixtures.play(out) { Fixtures.ok(it, "- 05/10 -R\$ 120,30 Mercado [Alimentação]\n- 08/10 -R\$ 80,00 Mercado B [Alimentação]\n- 12/10 -R\$ 50,00 Feira [Alimentação] (a pagar)") }
        assertEquals("Você gastou R\$ 200,30 com mercado neste mês, e ainda tem R\$ 50,00 a pagar.", r.text)
        assertEquals("2026-09", run(e(), "quanto gastei no mês passado").plan.steps.first().args["mes"])
    }

    @Test fun financePending() {
        val r = Fixtures.play(run(e(), "o que eu tenho para pagar")) { Fixtures.ok(it, "- 15/10 -R\$ 300,00 Internet [Casa] (a pagar)\n- 20/10 -R\$ 340,00 Cartão [Cartão] (a pagar)") }
        assertTrue(r.text.startsWith("Faltam 2 despesas a pagar, R\$ 640,00"), r.text)
    }

    @Test fun financeInvoice() {
        val out = run(e(), "quanto está a fatura do cartão nubank")
        assertEquals("Nubank", (out.plan.steps.first().args["cartao"] as String).replaceFirstChar { it.uppercase() })
        val r = Fixtures.play(out) { Fixtures.ok(it, "Cartão Nubank: fatura atual R\$ 850,10, próxima R\$ 400,00, parcelas futuras R\$ 100,00, limite R\$ 3.850,10 (livre R\$ 3.000,00). Fecha dia 5, vence dia 12.") }
        assertTrue(r.text.startsWith("A fatura do Nubank está em R\$ 850,10"), r.text)
        assertTrue(r.text.contains("Vence dia 12"), r.text)
        val all = Fixtures.play(run(e(), "quanto está a minha fatura")) { Fixtures.ok(it, summary) }
        assertTrue(all.text.contains("Nubank, R\$ 850,10"), all.text)
    }

    @Test fun financeAddAlwaysConfirms() {
        val en = e()
        val a = en.handle("gastei 50 reais no mercado", ctx)
        assertTrue(a is Ask && a.question.startsWith("Lançar despesa de R\$ 50,00 — Mercado, hoje"), "$a")
        val yes = en.answer(a as Ask, "sim", ctx) as Run
        val st = yes.plan.steps.first()
        assertEquals("joca_financas_lancar", st.tool)
        assertEquals("50,00", st.args["valor"]); assertEquals("DESPESA", st.args["tipo"]); assertEquals("2026-10-11", st.args["data"])
        assertTrue(st.confirmed)
        val r = Fixtures.play(yes) { Fixtures.ok(it, "Lançado (id 7): Mercado R\$ 50,00 em 11/10/2026.") }
        assertTrue(r.text.startsWith("Lançado: Mercado, R\$ 50,00"), r.text)
        assertTrue(en.answer(en.handle("gastei 50 reais no mercado", ctx) as Ask, "não", ctx) is Say)
    }

    @Test fun financeAddVariants() {
        val en = e()
        for ((s, tipo, valor) in listOf(
            Triple("lança uma despesa de 152,30 de energia", "DESPESA", "152,30"),
            Triple("paguei 89 reais de internet ontem", "DESPESA", "89,00"),
            Triple("recebi 1200 reais de salário", "RECEITA", "1200,00"),
            Triple("registra um gasto de R$ 35 com farmácia", "DESPESA", "35,00"),
        )) {
            val a = en.handle(s, ctx)
            assertTrue(a is Ask, "$s → $a")
            val st = (en.answer(a as Ask, "sim", ctx) as Run).plan.steps.first()
            assertEquals(tipo, st.args["tipo"], s); assertEquals(valor, st.args["valor"], s)
        }
        assertEquals("2026-10-10", ((en.answer(en.handle("paguei 89 reais de internet ontem", ctx) as Ask, "sim", ctx) as Run).plan.steps.first().args["data"]))
    }

    @Test fun financeAddDuplicateAsksToForce() {
        val en = e()
        val yes = en.answer(en.handle("gastei 50 reais no mercado", ctx) as Ask, "sim", ctx) as Run
        val r = Fixtures.play(yes) { Fixtures.ok(it, "NÃO gravei: parece já cadastrado (\"Mercado\", R\$ 50,00). Para gravar mesmo assim, use forcar=true.") }
        assertTrue(r.text.startsWith("Não lancei: parece já cadastrado"), r.text)
        val forced = en.answer(r.ask!!, "pode", ctx) as Run
        assertEquals(true, forced.plan.steps.first().args["forcar"])
    }

    @Test fun financeAddLeavesInstallmentsToTheAi() {
        assertEquals(NoMatch, e().handle("comprei uma tv de 1200 reais em 10 vezes", ctx))
    }

    @Test fun financeAddAsksTitle() {
        val en = e()
        val a = en.handle("lança 40 reais", ctx)
        assertTrue(a is Ask && a.question.contains("Com o que foi"), "$a")
    }

    // ---- EduMath
    @Test fun edumath() {
        assertEquals("edumath_listar_turmas", first("quais são as minhas turmas").tool)
        assertEquals("edumath_sincronizar_agora", first("sincroniza o edumath").tool)
        assertEquals("edumath_estado_sincronizacao", first("o edumath está sincronizado").tool)
        val s = first("pergunta ao mathie quando é a reposição da turma de cálculo")
        assertEquals("edumath_perguntar", s.tool)
        assertTrue((s.args["pergunta"] as String).contains("reposição"), "${s.args}")
        assertEquals("edu.ask_topic", run(e(), "quando é o seminário da turma B").intentId)
    }

    @Test fun edumathGradeConfirms() {
        val en = e()
        val a = en.handle("lança a nota 8,5 na P1 do aluno Carlos Silva", ctx)
        assertTrue(a is Ask && a.question.contains("8,5 na P1") && a.question.contains("Carlos Silva"), "$a")
        val st = (en.answer(a as Ask, "sim", ctx) as Run).plan.steps.first()
        assertEquals("edumath_lancar_nota", st.tool)
        assertEquals(8.5, st.args["valor"]); assertEquals("Carlos Silva", st.args["aluno"]); assertEquals("P1", st.args["campo"])
    }

    // ---- WhatsApp
    @Test fun whatsappSendFlow() {
        val en = e()
        val out = run(en, "manda uma mensagem para a Maria dizendo que já estou chegando")
        assertEquals("wa.send", out.intentId)
        val s = out.plan.steps.first()
        assertEquals("whatsapp_mensagem", s.tool)
        assertEquals("Maria", s.args["contato"]); assertEquals("Já estou chegando", s.args["mensagem"])
        val r = Fixtures.play(out) { st -> if (st.tool == "whatsapp_mensagem") Fixtures.ok(st, """{"aberto":"conversa com Maria"}""") else Fixtures.ok(st, "toquei em Enviar") }
        assertTrue(r.text.startsWith("Mensagem enviada para Maria"), r.text)
        // 2º passo é o toque em Enviar, com espera
        val plan2 = (out.plan.then(listOf(Fixtures.ok(s, "{}"))) as More).plan.steps.first()
        assertEquals("tela_tocar", plan2.tool); assertEquals("Enviar", plan2.args["nome"]); assertTrue(plan2.settleMs > 0)
    }

    @Test fun whatsappVariants() {
        val en = e()
        assertEquals("Ana", run(en, "diz pra Ana que eu vou atrasar").plan.steps.first().args["contato"])
        assertEquals("Pedro", run(en, "avisa o Pedro que a aula foi cancelada").plan.steps.first().args["contato"])
        assertEquals("Oi", run(en, "manda oi para a Clara").plan.steps.first().args["mensagem"])
        assertEquals(true, run(en, "manda mensagem no whatsapp business para o João dizendo que o pedido saiu").plan.steps.first().args["business"])
        val a = en.handle("manda uma mensagem para a Maria", ctx)
        assertTrue(a is Ask && a.question == "O que digo para Maria?", "$a")
        assertEquals("Chego às 8", ((en.answer(a as Ask, "chego às 8", ctx) as Run).plan.steps.first().args["mensagem"]).toString().replace("8h", "8").replace("às 8:00", "às 8"))
    }

    @Test fun whatsappDoesNotStealEmail() {
        assertEquals(NoMatch, e().handle("manda um email para o Pedro dizendo que cheguei", ctx))
    }

    @Test fun whatsappAmbiguousContactAsksWhich() {
        val en = e()
        val out = run(en, "manda mensagem para a Maria dizendo oi")
        val r = Fixtures.play(out) { Fixtures.bad(it, "Mais de um contato combina com \"Maria\": Maria Souza, Maria Clara. Pergunte qual.") }
        assertTrue(r.text.contains("Maria Souza e Maria Clara"), r.text)
        val again = en.answer(r.ask!!, "a Clara", ctx)
        assertEquals("Maria Clara", (again as Run).plan.steps.first().args["contato"])
    }

    @Test fun whatsappOpenAndWho() {
        assertEquals("", first("abre a conversa com a Maria").args["mensagem"])
        val r = Fixtures.play(run(e(), "quem me mandou mensagem")) { Fixtures.ok(it, "- 08:10 · WhatsApp · Maria: oi\n- 08:12 · WhatsApp · João: bom dia\n- 08:13 · WhatsApp · Maria: vc vem?") }
        assertTrue(r.text.contains("Maria mandou 2 mensagens"), r.text)
        val q = Fixtures.play(run(e(), "o que a Maria me mandou")) { Fixtures.ok(it, "- 08:10 · WhatsApp · Maria: oi\n- 08:12 · WhatsApp · João: bom dia") }
        assertTrue(q.text.startsWith("Maria escreveu: \"oi\""), q.text)
    }

    // ---- Instagram e apps
    @Test fun instagram() {
        val out = run(e(), "abre as mensagens do instagram")
        assertEquals(listOf("abrir_app", "tela_tocar"), out.plan.steps.map { it.tool })
        assertEquals("Mensagens", out.plan.steps[1].args["nome"])
        assertEquals("Reels", run(e(), "abre os reels no insta").plan.steps[1].args["nome"])
        assertEquals("Perfil", run(e(), "vai pro meu perfil do instagram").plan.steps[1].args["nome"])
    }

    @Test fun openApps() {
        assertEquals("WhatsApp", first("abre o zap").args["nome"])
        assertEquals("Instagram", first("abre o intagram").args["nome"])
        assertEquals("EduMath", first("abrir o edu math").args["nome"])
        assertEquals(NoMatch, e().handle("abre o aplicativo xyzzy inexistente", ctx))
    }

    @Test fun searchInApp() {
        val out = run(e(), "pesquisa funções do segundo grau no youtube")
        assertEquals(listOf("abrir_app", "tela_pesquisar"), out.plan.steps.map { it.tool })
        assertEquals("funções do segundo grau", out.plan.steps[1].args["texto"])
    }

    @Test fun webSearchWorksOnlineAndSaysOfflineHonestly() {
        assertEquals("pesquisar_na_web", first("pesquisa receita de bolo no google").tool)
        val off = e().handle("pesquisa receita de bolo na internet", Fixtures.ctx(online = false))
        assertTrue(off is Say && off.text.contains("sem internet"), "$off")
    }

    @Test fun brainAndHelp() {
        val b = e().handle("qual cérebro você está usando", ctx) as Say
        assertTrue(b.text.startsWith("Agora estou usando o cérebro Gemma 4 E2B (no celular)"), b.text)
        assertTrue((e().handle("o que você sabe fazer", ctx) as Say).text.contains("agenda"))
    }

    @Test fun conversationIsNotHijacked() {
        val en = e()
        for (s in listOf("oi tudo bem", "me conta uma piada", "explica o teorema de pitágoras", "o que é uma derivada", "estou cansado hoje", "obrigado", "você acha que vai chover", "quem descobriu o Brasil", "como foi sua noite")) {
            assertEquals(NoMatch, en.handle(s, ctx), s)
        }
    }
}

/** Falas reais dos testes do usuário (nomes trocados): o motor tem de acertar o que é pedido e deixar a conversa para a IA. */
class RealPhrasesTest {
    private val ctx = Fixtures.ctx()
    private fun e() = Fixtures.engine()
    private fun id(s: String): String? = when (val r = e().handle(s, ctx)) { is Say -> r.intentId; is Run -> r.intentId; is Ask -> r.intentId; NoMatch -> null }

    @Test fun commandsTheEngineMustTake() {
        for ((s, want) in listOf(
            "e o que tem na agenda para mim hoje" to "agenda.query",
            "como é que está minha agenda" to "agenda.query",
            "Veja a minha agenda da próxima semana" to "agenda.query",
            "O que tem na minha agenda para dia 15 dá uma olhadinha" to "agenda.query",
            "e verifique O que que tem na minha agenda para o dia 15" to "agenda.query",
            "Anote aí no dia 15 de Outubro às 17 horas ir para casa" to "agenda.create",
            "Anote almoço em família dia 12" to "agenda.create",
            "manda um oi para meu amor" to "wa.send",
            "Abra o aplicativo Gmail" to "app.open",
            "eu quero que você abra o agenda" to "app.open",
            "abra por favor no meu WhatsApp" to "app.open",
            "pode fechar o Instagram" to "screen.home",
            "Vá para a tela inicial" to "screen.home",
            "tá me ouvindo" to "self.hear",
            "Olá como é seu nome" to "self.name",
            "O que é possível fazer" to "self.help",
            "Que dia é hoje" to "time.date",
            "e o feriado Nossa Senhora Aparecida é hoje ou é amanhã" to "time.holiday",
            // do relatório da 0.29.0: estavam indo para a IA com ferramentas (caminho antigo)
            "Como está o meu e-mail" to "gmail.unread",
            "agora você vai me fazer um resumo do Joca finanças" to "fin.balance",
            "confira minhas finanças" to "fin.balance",
            "quais dívidas eu tenho fixas para esse mês" to "fin.pending",
            "verifique se eu tenho algum cartão para pagar" to "fin.invoice",
        )) assertEquals(want, id(s), s)
    }

    @Test fun conversationAndComplaintsGoToTheAi() {
        for (s in listOf(
            "E como você disse que hoje era feriado",
            "você foi burrinho",
            "eu tô com sono",
            "Abra o Gmail que você já pode fazer isso sim",
            "Abra o WhatsApp agora e mande uma mensagem para o meu amor coração vermelho avise para ela que eu estou com saudade e que amo",
            "Abra o Gmail no dia 15 o Gmail não abra o a agenda eu quero olhar a agenda na minha tela",
            "O que tem na página do Instagram que você está vendo agora",
            "Pesquise no Gmail",
            "agenda Abra a agenda",
            "o que tem na minha tela agora",
        )) assertEquals(null, id(s), s)
    }
}
