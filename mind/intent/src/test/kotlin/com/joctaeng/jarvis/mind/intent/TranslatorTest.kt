package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslatorTest {
    private val ctx = Fixtures.ctx()
    private val engine = Fixtures.engine()

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

    @Test fun translationExecutesThroughTheSamePath() {
        val t = Translator.parse("""{"intent":"agenda.create","slots":{"titulo":"ortopedista","date":"2026-10-15","time":"15:00","duration_min":"45"}}""", engine.all)!!
        val out = engine.fromTranslation(t.intentId, t.slots, ctx)
        val s = (out as Run).plan.steps.first()
        assertEquals("agenda_salvar", s.tool)
        assertEquals("2026-10-15 15:00", s.args["inicio"]); assertEquals(45, s.args["duracao_min"]); assertEquals("Ortopedista", s.args["titulo"])
    }

    @Test fun translatedMoneyStillAsksConfirmation() {
        val t = Translator.parse("""{"intent":"fin.add","slots":{"titulo":"farmácia","amount":"37,90","tipo":"DESPESA"}}""", engine.all)!!
        val out = engine.fromTranslation(t.intentId, t.slots, ctx)
        assertTrue(out is Ask && out.question.contains("R$ 37,90"), "$out")
        val inc = engine.fromTranslation("fin.add", mapOf("titulo" to "aulas particulares", "amount" to "300,00", "tipo" to "RECEITA"), ctx)
        assertTrue(inc is Ask && inc.question.startsWith("Lançar receita"), "$inc")
    }

    @Test fun translationOfMessageAndQuery() {
        val w = engine.fromTranslation("wa.send", mapOf("contato" to "Maria", "mensagem" to "chego em 10 minutos"), ctx) as Run
        assertEquals("Chego em 10 minutos", w.plan.steps.first().args["mensagem"])
        val a = engine.fromTranslation("agenda.query", mapOf("date" to "2026-10-14"), ctx) as Run
        assertEquals(mapOf<String, Any?>("data" to "2026-10-14"), a.plan.steps.first().args)
        assertNotNull(engine.definition("edu.grade"))
        val g = engine.fromTranslation("edu.grade", mapOf("aluno" to "Carlos Silva", "campo" to "P1", "valor" to "8,5"), ctx)
        assertTrue(g is Ask && g.question.contains("Carlos Silva"), "$g")
    }
}
