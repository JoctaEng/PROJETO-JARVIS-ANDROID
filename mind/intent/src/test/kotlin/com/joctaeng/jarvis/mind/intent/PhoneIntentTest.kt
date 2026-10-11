package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneIntentTest {
    private val e = Fixtures.engine()
    private val ctx = Fixtures.ctx()

    private fun run(s: String): Run = e.handle(s, ctx) as? Run ?: error("sem plano para: $s → ${e.handle(s, ctx)}")
    private fun first(s: String): Step = run(s).plan.steps.first()

    @Test fun battery() {
        val out = run("quanto tenho de bateria")
        assertEquals("phone.battery", out.intentId)
        val r = Fixtures.play(out) { Fixtures.ok(it, """{"bateria_percentual":15,"carregando":false,"conexao":"Wi-Fi","internet_funcionando":true,"espaco_livre_gb":42}""") }
        assertTrue(r.text.startsWith("A bateria está em 15%"))
        assertTrue(r.text.contains("baixa"))
    }

    @Test fun internetAndStorage() {
        assertEquals("phone.internet", run("estou conectado?").intentId)
        assertEquals("phone.internet", run("tem internet aí").intentId)
        assertEquals("phone.storage", run("quanto de espaço livre eu tenho").intentId)
    }

    @Test fun flashlight() {
        assertEquals(mapOf<String, Any?>("ligar" to true), first("liga a lanterna").args)
        assertEquals(mapOf<String, Any?>("ligar" to false), first("desliga a lanterna").args)
        assertEquals(mapOf<String, Any?>("ligar" to false), first("apaga a lanterna por favor").args)
    }

    @Test fun alarm() {
        val s = first("cria um alarme para as 7 da manhã")
        assertEquals("criar_alarme", s.tool)
        assertEquals(7, s.args["hora"]); assertEquals(0, s.args["minuto"])
        val s2 = first("me acorda às 6h30 de segunda a sexta")
        assertEquals(6, s2.args["hora"]); assertEquals(30, s2.args["minuto"])
        assertEquals(listOf("seg", "ter", "qua", "qui", "sex"), s2.args["dias"])
    }

    @Test fun alarmAsksForTime() {
        val out = e.handle("cria um alarme", ctx)
        assertTrue(out is Ask, "esperava pergunta: $out")
        val next = e.answer(out as Ask, "às 5 e meia", ctx)
        assertEquals(5, (next as Run).plan.steps.first().args["hora"])
    }

    @Test fun timer() {
        val s = first("coloca um timer de 10 minutos")
        assertEquals("criar_timer", s.tool); assertEquals(600, s.args["segundos"])
        assertEquals(300, first("me avisa daqui a 5 minutos").args["segundos"])
        assertEquals(5400, first("timer de uma hora e meia").args["segundos"])
    }

    @Test fun dnd() {
        assertEquals("nao_perturbe", first("ativa o não perturbe").tool)
        assertEquals(false, first("desliga o não perturbe").args["ligar"])
    }

    @Test fun notifications() {
        val out = run("o que chegou de novo")
        assertEquals("phone.notifications", out.intentId)
        val r = Fixtures.play(out) { Fixtures.ok(it, "- 08:10 · WhatsApp · Maria: oi\n- 08:12 · Gmail · Banco: fatura") }
        assertTrue(r.text.startsWith("Chegaram 2"))
        val o2 = run("tem alguma mensagem nova no zap")
        assertEquals("phone.notifications_app", o2.intentId)
        assertEquals("WhatsApp", o2.plan.steps.first().args["app"])
    }

    @Test fun missingToolIsSaidHonestly() {
        val c = Fixtures.ctx(tools = emptySet())
        val out = e.handle("liga a lanterna", c)
        assertTrue(out is Say && out.text.contains("lanterna"))
    }
}
