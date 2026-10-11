package com.joctaeng.jarvis.mind.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeIntentTest {
    private val e = Fixtures.engine()
    private val ctx = Fixtures.ctx()
    private fun say(s: String): String = (e.handle(s, ctx) as? Say)?.text ?: error("sem resposta direta para: $s")

    @Test fun hour() {
        assertEquals("São 9h08.", say("que horas são"))
        assertEquals("São 9h08.", say("Guardião, me diz as horas por favor"))
    }

    @Test fun dateToday() {
        assertEquals("Hoje é domingo, 11 de outubro de 2026.", say("Que dia é hoje"))
        assertEquals("Hoje é dia 11 de outubro.", say("que dia do mês é hoje"))
    }

    @Test fun weekdayOfADate() {
        assertEquals("Amanhã será segunda-feira.", say("que dia da semana é amanhã"))
        assertTrue(say("dia 15 cai em que dia da semana").contains("quinta-feira"))
    }

    @Test fun holidayFixesTheMistakeOfTheTest() {
        // 11/10/2026: o Euno disse "hoje é feriado"; o feriado é amanhã (12/10).
        assertTrue(say("hoje é feriado").startsWith("Hoje não é feriado nacional"))
        assertTrue(say("amanhã é feriado").contains("Nossa Senhora Aparecida"))
        assertTrue(say("quando é o próximo feriado").contains("Nossa Senhora Aparecida"))
    }

    @Test fun holidaysFromEaster() {
        assertEquals(java.time.LocalDate.of(2026, 4, 5), BrHolidays.easter(2026))
        assertEquals("Sexta-feira Santa", BrHolidays.nameOn(java.time.LocalDate.of(2026, 4, 3)))
        assertEquals("Consciência Negra", BrHolidays.nameOn(java.time.LocalDate.of(2026, 11, 20)))
    }

    @Test fun countdown() {
        assertTrue(say("quantos dias faltam para o natal").startsWith("Faltam 75 dias"))
        assertTrue(say("quantos dias faltam para dia 25 de dezembro").contains("75"))
    }

    @Test fun calculator() {
        assertEquals("Dá 180.", say("quanto é 12 vezes 15"))
        assertEquals("Dá 12.", say("quanto é 15 por cento de 80"))
        assertEquals("Dá 12.", say("raiz quadrada de 144"))
        assertEquals("Dá 1.024.", say("2 elevado a 10"))
        assertEquals("Dá 3,5.", say("quanto é 7 dividido por 2"))
        assertEquals("Dá 8,5.", say("quanto é 5 mais 3,5"))
    }

    @Test fun notACalculation() {
        assertEquals(NoMatch, e.handle("me dá mais informações sobre isso", ctx))
        assertEquals(NoMatch, e.handle("oi tudo bem", ctx))
    }
}
