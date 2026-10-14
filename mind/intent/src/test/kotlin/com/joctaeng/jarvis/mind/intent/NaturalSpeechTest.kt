package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.31: a agenda falada não lê barra, hífen nem número de processo (teste de 14/10). Dados fictícios. */
class NaturalSpeechTest {
    @Test fun titlesAreShortAndWithoutCodes() {
        assertEquals("Vence prazo SEDUC", Speak.title("[MP] Vence prazo SEDUC – Ofício nº 101/2026 – Escola Modelo – SIMP 012345-500/2026"))
        assertEquals("Prazo da NF vence em 10 dias (24/10)", Speak.title("[MP] Prazo da NF vence em 10 dias (24/10) – prorrogar? – Fulano, Centro – SIMPs 011111, 022222-500/2026"))
        assertEquals("Futebol", Speak.title("Futebol"))
        assertEquals("MP", Speak.title("[MP] 012345-500/2026"))
    }

    @Test fun busyDayIsGroupedAndShort() {
        val raw = """
            Agenda para hoje:
            - dia inteiro [MP] Vence prazo SEDUC – Ofício nº 101/2026 – Escola A – SIMP 011111-500/2026 [id 1]
            - dia inteiro [MP] Vence prazo SEDUC – Ofício nº 102/2026 – Escola B – SIMP 022222-500/2026 [id 2]
            - dia inteiro [MP] Vence prazo SEDUC – Ofício nº 103/2026 – Escola C – SIMP 033333-500/2026 [id 3]
            - 15:00–16:00 Valor [id 4]
            - 19:00–20:00 Futebol [id 5]
        """.trimIndent()
        val p = ToolText.agenda(raw)
        val said = Replies.agenda(p, "hoje")
        assertTrue(said.startsWith("Hoje você tem 5 compromissos. O dia todo, Vence prazo SEDUC, 3 vezes."), said)
        assertTrue("às 19h, Futebol" in said, said)
        assertFalse(said.contains("/2026") || said.contains("SIMP") || said.contains("–"), said)
        val detail = Replies.agendaDetail(p)!!
        assertTrue("SIMP 011111-500/2026" in detail, detail)
    }

    @Test fun dayLabelIsSpoken() {
        assertEquals("Segunda-feira, dia 12", Replies.spokenDay("segunda-feira, 12/10"))
    }

    @Test fun pleaseDeleteAfterFiller() {
        val en = IntentEngine()
        val ctx = Fixtures.ctx()
        for (s in listOf("entendi pode apagar futebol", "agora você vai fazer o seguinte apagar o futebol", "certo, apaga o futebol")) {
            val out = en.handle(s, ctx)
            assertTrue(out is Run && out.intentId == "agenda.delete", "$s → $out")
        }
    }

    @Test fun rememberWithDateIsAgenda() {
        val en = IntentEngine()
        val out = en.handle("lembre que no dia 15 às 17 horas ir para casa", Fixtures.ctx())
        assertTrue(out is Run && out.intentId == "agenda.create", out.toString())
        val step = (out as Run).plan.steps.first()
        assertEquals("Ir para casa", step.args["titulo"])
        assertEquals("2026-10-15 17:00", step.args["inicio"])
    }
}

class LifeIntentTest {
    private val tools = setOf("agenda_consultar", "agenda_salvar", "agenda_apagar", "clima", "gmail_buscar", "gmail_ler", "sms_enviar", "notificacoes_ler")
    private fun ctx(gmail: Boolean = true) = Fixtures.ctx().let { c -> Ctx(c.now, true, tools, gmail, c.apps, "Joca Silva", null, true) }

    @Test fun briefingRunsAgendaThenWeatherThenMail() {
        val out = IntentEngine().handle("como está meu dia hoje", ctx())
        assertTrue(out is Run && out.intentId == "day.briefing", out.toString())
        val r = Fixtures.play(out) { s ->
            when (s.tool) {
                "agenda_consultar" -> Fixtures.ok(s, "Agenda para hoje:\n- 19:00–20:00 Futebol [id 5]")
                "clima" -> Fixtures.ok(s, """{"cidade":"Cidade Exemplo","agora":{"temp":30.6,"sensacao":34,"descricao":"parcialmente nublado"},"dias":[{"data":"2026-10-11","min":24,"max":33,"chuva":60,"descricao":"pancadas de chuva"}]}""")
                else -> Fixtures.ok(s, "- [id a1] 11/10 08:00 · Banco Exemplo <avisos@banco.example> · Sua fatura chegou — vence dia 20")
            }
        }
        assertTrue(r.text.startsWith("Bom dia, Joca!") || r.text.startsWith("Boa"), r.text)
        assertTrue("Futebol" in r.text && "31 graus" in r.text && "Banco Exemplo" in r.text && "60% de chance de chuva" in r.text, r.text)
        assertFalse("<" in r.text || "@" in r.text, r.text)
    }

    @Test fun briefingWithoutGmailSaysSoHonestly() {
        val r = Fixtures.play(IntentEngine().handle("me faz um resumo do dia", ctx(gmail = false))) { s ->
            if (s.tool == "agenda_consultar") Fixtures.ok(s, "Nenhum compromisso na agenda para hoje.") else StepResult(s, false, "sem internet")
        }
        assertTrue("Gmail não está conectado" in r.text, r.text)
    }

    @Test fun taskInstructionsBecomeBriefings() {
        assertEquals(Briefing.Parts(agenda = true, weather = true, mail = true, tomorrow = false), Briefing.wants("me acorde todo dia às 7h30 com o resumo do dia e dos e-mails"))
        assertTrue(Briefing.wants("gmail_buscar e depois gmail_ler os e-mails mais recentes para fazer um resumo")!!.mail)
        assertEquals(null, Briefing.wants("mande bom dia para a Maria no WhatsApp"))
    }

    @Test fun weatherAndRain() {
        val en = IntentEngine()
        for (s in listOf("como está o tempo", "vai chover amanhã", "qual a previsão do tempo para amanhã", "está fazendo calor", "como está o clima em Belém")) {
            val out = en.handle(s, ctx())
            assertTrue(out is Run && out.intentId == "weather.now", "$s → $out")
        }
        val out = en.handle("vai chover amanhã", ctx()) as Run
        assertEquals(2, out.plan.steps.first().args["dias"])
        val r = Fixtures.play(out) { s -> Fixtures.ok(s, """{"cidade":"X","agora":{"temp":28},"dias":[{"data":"a","min":22,"max":30,"chuva":10},{"data":"b","min":23,"max":31,"chuva":70,"descricao":"chuva forte"}]}""") }
        assertTrue("70% de chance de chuva" in r.text && "guarda-chuva" in r.text, r.text)
    }

    @Test fun mailAsksWhatToBring() {
        val en = IntentEngine()
        val c = ctx()
        val out = en.handle("me dá as informações do meu e-mail", c)
        assertTrue(out is Ask && out.intentId == "gmail.info", out.toString())
        val next = en.answer(out as Ask, "só os de hoje", c)
        assertTrue(next is Run, next.toString())
        assertTrue((next as Run).plan.steps.first().args["consulta"].toString().startsWith("in:inbox after:2026/10/11"), next.plan.steps.first().args.toString())
        val older = en.handle("quais e-mails chegaram semana passada", c)
        assertTrue(older is Run && older.intentId == "gmail.period", older.toString())
    }

    @Test fun smsAsksBeforeSending() {
        val out = IntentEngine().handle("manda um sms para a Maria dizendo que já saí", ctx())
        assertTrue(out is Ask && out.intentId == "sms.send", out.toString())
        assertTrue((out as Ask).question.contains("Que já saí") || out.question.contains("Já saí"), out.question)
    }
}

class MorningTaskTest {
    private val ctx = Fixtures.ctx(tools = Fixtures.tools + "tarefa_criar")

    @Test fun wakeMeWithSummaryBecomesATask() {
        val out = IntentEngine().handle("me acorde todos os dias às 7h30 da manhã me dando um resumo do que eu preciso fazer hoje e do meu e-mail", ctx)
        assertTrue(out is Run && out.intentId == "task.morning", out.toString())
        val args = (out as Run).plan.steps.first().args
        assertEquals(7, args["hora"]); assertEquals(30, args["minuto"]); assertEquals("todo_dia", args["repetir"]); assertEquals(true, args["falar"])
        assertTrue(args["pedido"].toString().contains("e-mails"), args.toString())
    }

    @Test fun plainWakeIsStillAnAlarm() {
        val out = IntentEngine().handle("me acorda às 6h", ctx)
        assertTrue(out is Run && out.intentId == "phone.alarm", out.toString())
    }
}
