package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgendaIntentTest {
    private val ctx = Fixtures.ctx()
    private fun e() = Fixtures.engine()
    private fun run(en: IntentEngine, s: String): Run = en.handle(s, ctx) as? Run ?: error("sem plano para: $s → ${en.handle(s, ctx)}")

    private val today = "Agenda para hoje:\n- 08:00–09:00 Aula de Cálculo (local: Sala 3) [id 11]\n- 14:00–15:30 Reunião de colegiado [id 12]\n- dia inteiro Prova P1 [id 13]"
    private val tomorrow = "Nenhum compromisso na agenda para amanhã."
    private val week = "Agenda para 7 dias a partir de 12/10:\nsegunda-feira, 12/10:\n- 10:00–11:00 Dentista [id 21]\nquarta-feira, 14/10:\n- 15:00–16:00 Dentista de novo [id 22]\n- 19:00–20:00 Aula de Física [id 23]"

    @Test fun queryToday() {
        val out = run(e(), "o que eu tenho hoje")
        assertEquals("agenda.query", out.intentId)
        assertEquals(mapOf<String, Any?>("periodo" to "hoje"), out.plan.steps.first().args)
        val r = Fixtures.play(out) { Fixtures.ok(it, today) }
        assertTrue(r.text.startsWith("Hoje você tem 3 compromissos:"), r.text)
        assertTrue(r.text.contains("às 8h, Aula de Cálculo, em Sala 3"), r.text)
    }

    @Test fun queryVariants() {
        val en = e()
        for (s in listOf("o que tenho na agenda hoje", "como está minha agenda", "me mostra a agenda", "tenho algum compromisso hoje", "quais são meus compromissos", "agenda de hoje", "tem alguma coisa marcada hoje", "Guardião, o que eu tenho hoje por favor")) {
            assertEquals("agenda.query", run(en, s).intentId, s)
        }
    }

    @Test fun queryTomorrowAndDate() {
        val en = e()
        assertEquals(mapOf<String, Any?>("periodo" to "amanha"), run(en, "o que tenho amanhã").plan.steps.first().args)
        val s = run(en, "o que eu tenho dia 15").plan.steps.first()
        assertEquals(mapOf<String, Any?>("data" to "2026-10-15"), s.args)
        val r = Fixtures.play(run(en, "o que tenho amanhã")) { Fixtures.ok(it, tomorrow) }
        assertEquals("Você não tem nenhum compromisso amanhã.", r.text)
    }

    @Test fun followUpKeepsTheIntent() {
        val en = e()
        run(en, "o que tenho hoje")
        val f = run(en, "e amanhã?")
        assertEquals("agenda.query", f.intentId)
        assertEquals("amanha", f.plan.steps.first().args["periodo"])
    }

    @Test fun nextAppointment() {
        val out = run(e(), "qual é o meu próximo compromisso")
        assertEquals("agenda.next", out.intentId)
        val r = Fixtures.play(out) { Fixtures.ok(it, today) }
        assertTrue(r.text.startsWith("Seu próximo compromisso é hoje às 14h"), r.text)
    }

    @Test fun count() {
        val r = Fixtures.play(run(e(), "quantos compromissos eu tenho hoje")) { Fixtures.ok(it, today) }
        assertEquals("Hoje você tem 3 compromissos.", r.text)
    }

    @Test fun freeTime() {
        val r = Fixtures.play(run(e(), "tenho algum horário livre hoje")) { Fixtures.ok(it, today) }
        assertTrue(r.text.contains("Livre"), r.text)
    }

    @Test fun createWithEverything() {
        val out = run(e(), "marca dentista amanhã às 15h")
        assertEquals("agenda.create", out.intentId)
        val s = out.plan.steps.first()
        assertEquals("agenda_salvar", s.tool)
        assertEquals("Dentista", s.args["titulo"])
        assertEquals("2026-10-12 15:00", s.args["inicio"])
        val r = Fixtures.play(out) { Fixtures.ok(it, """{"salvo":true,"id":300,"titulo":"Dentista"}""") }
        assertTrue(r.text.contains("marquei Dentista para amanhã às 15h"), r.text)
    }

    @Test fun createVariants() {
        val en = e()
        for ((s, inicio, titulo) in listOf(
            Triple("agende uma reunião com o Pedro na terça às 14h", "2026-10-13 14:00", "Reunião com o Pedro"),
            Triple("coloca na agenda aula de física dia 20 às 19h", "2026-10-20 19:00", "Aula de física"),
            Triple("marque consulta no dia 15 às 10 da manhã", "2026-10-15 10:00", "Consulta"),
            Triple("cria um compromisso almoço com a Ana amanhã ao meio-dia", "2026-10-12 12:00", "Almoço com a Ana"),
            Triple("me lembra de ligar para o banco amanhã às 9h", "2026-10-12 09:00", "Ligar para o banco"),
        )) {
            val st = run(en, s).plan.steps.first()
            assertEquals(inicio, st.args["inicio"], s)
            assertEquals(titulo, st.args["titulo"], s)
        }
    }

    @Test fun createAsksMissingTime() {
        val en = e()
        val a = en.handle("marca dentista amanhã", ctx)
        assertTrue(a is Ask, "$a")
        val next = en.answer(a as Ask, "às 3 da tarde", ctx)
        assertEquals("2026-10-12 15:00", (next as Run).plan.steps.first().args["inicio"])
    }

    @Test fun createAsksWhen() {
        val en = e()
        val a = en.handle("marca dentista", ctx)
        assertTrue(a is Ask, "$a")
        val next = en.answer(a as Ask, "sexta às 8h", ctx)
        assertEquals("2026-10-16 08:00", (next as Run).plan.steps.first().args["inicio"])
    }

    @Test fun createAmbiguousHourAsksPeriod() {
        val en = e()
        val a = en.handle("marca dentista amanhã às 3", ctx)
        assertTrue(a is Ask && a.question.contains("manhã ou da tarde"), "$a")
    }

    @Test fun duplicateIsSaidHonestly() {
        val r = Fixtures.play(run(e(), "marca dentista amanhã às 15h")) { Fixtures.ok(it, """{"salvo":true,"id":9,"ja_existia":true}""") }
        assertTrue(r.text.startsWith("Esse compromisso já estava na agenda"), r.text)
    }

    @Test fun deleteFindsThenAsksThenDeletes() {
        val en = e()
        val out = run(en, "apaga o dentista")
        assertEquals("agenda.delete", out.intentId)
        // 1ª etapa: consulta; achou 2 "Dentista" → pergunta qual
        val r1 = Fixtures.play(out) { Fixtures.ok(it, week) }
        assertTrue(r1.text.startsWith("Achei 2"), r1.text)
        val ask = r1.ask!!
        val chosen = en.answer(ask, "o primeiro", ctx)
        assertTrue(chosen is Ask && chosen.question.startsWith("Apagar"), "$chosen")
        val yes = en.answer(chosen as Ask, "sim", ctx)
        val del = (yes as Run).plan.steps.first()
        assertEquals("agenda_apagar", del.tool)
        assertEquals(21L, del.args["id"])
        assertTrue(del.confirmed)
    }

    @Test fun deleteSingleAsksConfirmation() {
        val en = e()
        val r = Fixtures.play(run(en, "cancela a reunião de colegiado hoje")) { Fixtures.ok(it, today) }
        assertTrue(r.text.startsWith("Apagar"), r.text)
        assertEquals(12L, ((en.answer(r.ask!!, "pode", ctx) as Run).plan.steps.first().args["id"]))
        assertTrue(en.answer(r.ask!!, "não", ctx) is Say)
    }

    @Test fun deleteNotFoundIsHonest() {
        val r = Fixtures.play(run(e(), "apaga o jantar com a Sofia")) { Fixtures.ok(it, today) }
        assertTrue(r.text.startsWith("Não achei"), r.text)
    }

    @Test fun move() {
        val out = run(e(), "remarca o dentista para quinta às 16h")
        assertEquals("agenda.move", out.intentId)
        val r = Fixtures.play(out) { st ->
            if (st.tool == "agenda_consultar") Fixtures.ok(st, "Agenda para 31 dias:\nsegunda-feira, 12/10:\n- 10:00–11:00 Dentista [id 21]") else Fixtures.ok(st, """{"alterado":true,"id":21}""")
        }
        assertTrue(r.text.contains("passei \"Dentista\" para quinta-feira, dia 15 às 16h"), r.text)
    }

    @Test fun notAnAppointment() {
        val en = e()
        assertEquals(NoMatch, en.handle("anota que a senha do wifi é 1234", ctx))
        assertTrue(en.handle("anota dentista amanhã 15h", ctx) is Run)
    }

    @Test fun openAgendaOnDay() {
        val s = run(e(), "abre a agenda do google dia 20").plan.steps.first()
        assertEquals("agenda_abrir", s.tool)
        assertEquals("google", s.args["app"]); assertEquals("2026-10-20", s.args["data"])
    }
}
