package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslatorTest {
    private val ctx = Fixtures.ctx()
    private val engine = Fixtures.engine()
    private val UTTER_ORTHO = "encaixar uma consulta com o ortopedista na quinta de tarde"

    @Test fun candidatesAreFewAndRelevant() {
        val c = engine.candidates("preciso encaixar uma consulta com o ortopedista na quinta de tarde", ctx)
        assertTrue(c.size <= 14)
        assertTrue(c.any { it.id == "agenda.create" }, c.joinToString { it.id })
        val (system, user) = Translator.prompt("preciso encaixar uma consulta", c, ctx)
        assertTrue(system.length < 3500, "prompt grande: ${system.length}")
        assertTrue(system.contains("agenda.create") && user.startsWith("preciso"))
    }

    @Test fun parsesCleanAndMessyReplies() {
        val defs = engine.all
        val t1 = Translator.parse("""{"intent":"agenda.create","slots":{"titulo":"Ortopedista","date":"2026-10-15","time":"15:00"}}""", defs)
        assertEquals("agenda.create", t1?.intentId); assertEquals("15:00", t1?.slots?.get("time"))
        val t2 = Translator.parse("Claro!\n```json\n{\"intent\": \"fin.balance\", \"slots\": {}}\n```", defs)
        assertEquals("fin.balance", t2?.intentId)
        assertNull(Translator.parse("""{"intent":"none"}""", defs))
        assertNull(Translator.parse("""{"intent":"inventado.demais","slots":{}}""", defs))
        assertNull(Translator.parse("não sei", defs))
    }

    @Test fun createAskedFirstThenRunsOnYes() {
        val t = Translator.parse("""{"intent":"agenda.create","slots":{"titulo":"ortopedista","date":"2026-10-15","time":"15:00","duration_min":"45"}}""", engine.all)!!
        // criar compromisso mexe na agenda: a IA só executa depois do "sim"
        val ask = engine.fromTranslation(t.intentId, t.slots, ctx, UTTER_ORTHO) as Ask
        assertTrue(ask.question.startsWith("Entendi que você quer:"), ask.question)
        val s = (engine.answer(ask, "sim", ctx) as Run).plan.steps.first()
        assertEquals("agenda_salvar", s.tool)
        assertEquals("2026-10-15 15:00", s.args["inicio"]); assertEquals(45, s.args["duracao_min"]); assertEquals("Ortopedista", s.args["titulo"])
        assertTrue(s.confirmed)
    }

    @Test fun translatedMoneyStillAsksConfirmation() {
        val inc = engine.fromTranslation("fin.add", mapOf("titulo" to "aulas particulares", "amount" to "300,00", "tipo" to "RECEITA"), ctx, "recebi 300 reais de aulas particulares")
        assertTrue(inc is Ask && inc.question.startsWith("Lançar receita"), "$inc")
        val out = engine.fromTranslation("fin.add", mapOf("titulo" to "farmácia", "amount" to "37,90", "tipo" to "DESPESA"), ctx, "gastei 37,90 com farmácia")
        assertTrue(out is Ask && out.question.contains("R$ 37,90"), "$out")
    }

    @Test fun readQueriesRunDirectly() {
        val a = engine.fromTranslation("agenda.query", mapOf("date" to "2026-10-14"), ctx, "o que eu tenho dia 14") as Run
        assertEquals(mapOf<String, Any?>("data" to "2026-10-14"), a.plan.steps.first().args)
    }

    @Test fun messageNeedsYesAndKeepsItsText() {
        val w = engine.fromTranslation("wa.send", mapOf("contato" to "Maria", "mensagem" to "chego em 10 minutos"), ctx, "manda pra Maria que eu chego em 10 minutos")
        assertTrue(w is Ask, "$w")
        val run = engine.answer(w as Ask, "pode mandar", ctx) as Run
        assertEquals("Chego em 10 minutos", run.plan.steps.first().args["mensagem"])
    }

    @Test fun gradeNeedsYes() {
        val g = engine.fromTranslation("edu.grade", mapOf("aluno" to "Carlos Silva", "campo" to "P1", "valor" to "8,5"), ctx, "lança a nota 8,5 na P1 do aluno Carlos Silva")
        assertTrue(g is Ask && g.question.contains("Carlos Silva"), "$g")
    }

    // ---- as falas reais que a IA estragou no teste da 0.29.0 (relatório de 12/10): nada disso pode virar ação

    @Test fun complaintsNeverBecomeActions() {
        // "procure melhor" → a IA inventou uma pesquisa na web
        assertEquals(NoMatch, engine.fromTranslation("web.search", mapOf("consulta" to "melhores formas de melhorar o desempenho do meu computador"), ctx, "procure melhor"))
        // "ou Guardião" → a IA mandou ver o saldo do Joca Finanças
        assertEquals(NoMatch, engine.fromTranslation("fin.balance", emptyMap(), ctx, "ou Guardião"))
        // "pronto acabei de ligar e de ligar" → a IA acendeu a lanterna
        assertEquals(NoMatch, engine.fromTranslation("phone.flashlight_on", emptyMap(), ctx, "pronto acabei de ligar e de ligar"))
        // "isso eu não pedi para você me dizer novamente..." → a IA criou um evento "Marcar um compromisso"
        assertEquals(NoMatch, engine.fromTranslation("agenda.create", mapOf("titulo" to "Marcar um compromisso", "date" to "2026-10-14", "time" to "13:01"), ctx, "isso eu não pedi para você me dizer novamente se teria o que que teria dia 14"))
        // "procure novamente o Gmail" → a IA pesquisou "novas buscas"
        assertEquals(NoMatch, engine.fromTranslation("gmail.search", mapOf("assunto" to "novas buscas"), ctx, "procure novamente o Gmail"))
        // "beleza que os testes estão satisfatórios..." → a IA leu o resumo do dia
        assertEquals(NoMatch, engine.fromTranslation("agenda.summary", emptyMap(), ctx, "beleza que os testes estão satisfatórios você consegue fazer muita coisa offline"))
        // "Escreva uma mensagem a ela..." → a IA escolheu "ela" como contato
        assertEquals(NoMatch, engine.fromTranslation("wa.send", mapOf("contato" to "ela", "mensagem" to "Eu te amo"), ctx, "escreva uma mensagem a ela diga a ela que eu a amo"))
        // "Você consegue ... olhar" sem pedido de agenda → a IA chamou a agenda
        assertEquals(NoMatch, engine.fromTranslation("agenda.query", mapOf("date" to "2026-10-14"), ctx, "tudo bem então"))
    }

    @Test fun invented_texts_are_refused() {
        // o texto escolhido pela IA tem de sair das palavras ditas
        assertEquals(NoMatch, engine.fromTranslation("agenda.create", mapOf("titulo" to "Reunião de pais", "date" to "2026-10-15"), ctx, "marca um compromisso amanhã"))
    }
}
