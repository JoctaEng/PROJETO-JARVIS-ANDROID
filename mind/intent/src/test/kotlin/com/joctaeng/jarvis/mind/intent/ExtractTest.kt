package com.joctaeng.jarvis.mind.intent

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtractTest {
    // domingo, 11/10/2026
    private val today = LocalDate.of(2026, 10, 11)
    private fun ex(s: String) = Extract.of(s, today)

    @Test fun numbersByWord() {
        assertEquals("as 17 horas", PtText.join(PtText.tokenize("às dezessete horas")))
        assertEquals("dia 25", PtText.join(PtText.tokenize("dia vinte e cinco")))
        assertEquals("1 hora e meia", PtText.join(PtText.tokenize("uma hora e meia")))
        assertEquals("uma reuniao", PtText.join(PtText.tokenize("uma reunião")))
        assertEquals("1500 reais", PtText.join(PtText.tokenize("mil e quinhentos reais")))
        assertEquals("2000", PtText.join(PtText.tokenize("dois mil")))
    }

    @Test fun relativeDates() {
        assertEquals(today.plusDays(1), ex("o que tenho amanhã").date?.start)
        assertEquals(today.plusDays(2), ex("depois de amanhã").date?.start)
        assertEquals(today, ex("hoje").date?.start)
        assertEquals("o que tenho xdatax", ex("o que tenho amanhã").skeleton)
    }

    @Test fun dayNumbers() {
        assertEquals(LocalDate.of(2026, 10, 12), ex("o que temos para o dia 12").date?.start)
        assertEquals(LocalDate.of(2026, 11, 5), ex("dia 5").date?.start) // 5/10 já passou: próximo mês
        assertEquals(LocalDate.of(2026, 10, 15), ex("15/10").date?.start)
        assertEquals(LocalDate.of(2026, 12, 25), ex("25 de dezembro").date?.start)
        assertEquals(LocalDate.of(2026, 10, 15), ex("dia quinze de outubro").date?.start)
        assertEquals(LocalDate.of(2027, 1, 3), ex("3/1/2027").date?.start)
    }

    @Test fun weekdays() {
        assertEquals(LocalDate.of(2026, 10, 12), ex("segunda").date?.start)       // domingo → amanhã
        assertEquals(LocalDate.of(2026, 10, 16), ex("na sexta").date?.start)
        assertEquals(LocalDate.of(2026, 10, 18), ex("domingo").date?.start)       // hoje é domingo: o próximo
        assertEquals(LocalDate.of(2026, 10, 11), ex("neste domingo").date?.start)
        assertEquals(LocalDate.of(2026, 10, 9), ex("sexta passada").date?.start)
    }

    @Test fun ranges() {
        assertEquals("week", ex("o que tenho essa semana").date?.kind)
        assertEquals(LocalDate.of(2026, 10, 12), ex("semana que vem").date?.start)
        assertEquals(LocalDate.of(2026, 10, 17), ex("no fim de semana").date?.start)
        assertEquals("month", ex("neste mês").date?.kind)
    }

    @Test fun times() {
        assertEquals(TimeSlot(17, 0, false, false), ex("às 17h").time?.copy(ambiguous = false))
        assertEquals(17, ex("às 17:30").time?.hour)
        assertEquals(30, ex("às 17:30").time?.minute)
        assertEquals(21, ex("hoje 9 horas da noite").time?.hour)
        assertEquals(15, ex("às 3 da tarde").time?.hour)
        assertEquals(12, ex("ao meio dia").time?.hour)
        assertEquals(0, ex("meia noite").time?.hour)
        assertEquals(30, ex("às oito e meia").time?.minute)
        assertEquals(8, ex("às oito e meia").time?.hour)
        assertEquals(4, ex("quinze para as cinco").time?.hour)
        assertEquals(45, ex("quinze para as cinco").time?.minute)
        assertEquals(true, ex("às 5").time?.ambiguous)
        assertEquals(false, ex("às 17").time?.ambiguous)
    }

    @Test fun durations() {
        assertEquals(DurSlot(600, "daqui a"), ex("daqui a 10 minutos").dur)
        assertEquals(DurSlot(7200, "de"), ex("timer de duas horas").dur)
        assertEquals(DurSlot(5400, "em"), ex("em uma hora e meia").dur)
        assertEquals(DurSlot(1800, "em"), ex("em meia hora").dur)
        assertNull(ex("às 2 horas").dur)                     // hora do dia
        assertNotNull(ex("às 2 horas").time)
    }

    @Test fun money() {
        assertEquals(15230L, ex("gastei 152,30 reais").money?.cents)
        assertEquals(5000L, ex("gastei R$ 50 no mercado").money?.cents)
        assertEquals(250L, ex("dois reais e cinquenta centavos").money?.cents)
        assertEquals(120000L, ex("1.200 reais").money?.cents)
    }

    @Test fun partOfDay() {
        val e = ex("amanhã de manhã")
        assertEquals("manha", e.part)
        assertEquals("xdatax", e.skeleton)
    }

    @Test fun titleKeepsOriginalWords() {
        val e = ex("marca Reunião com a Coordenação amanhã às 15h")
        assertEquals("marca reuniao com a coordenacao xdatax xtimex", e.skeleton)
        assertEquals("Reunião", e.toks[1].orig)
    }

    @Test fun mixedFromRealConversation() {
        val e = ex("veja o que temos para dia 12 hoje debate hoje 9 horas da noite")
        assertEquals(LocalDate.of(2026, 10, 12), e.date?.start)
        assertEquals(21, e.time?.hour)
    }

    @Test fun recurrence() {
        assertEquals((1..7).toSet(), ex("todo dia às 7h").rec?.days)
        assertEquals((1..5).toSet(), ex("me acorda às 6 de segunda a sexta").rec?.days)
        assertEquals(setOf(6, 7), ex("nos fins de semana").rec?.days)
        assertEquals(setOf(2), ex("toda terça às 8h").rec?.days)
        assertEquals(setOf(1, 3), ex("às segundas e quartas").rec?.days)
        assertEquals("me acorda xtimex xrecx", ex("me acorda às 6 de segunda a sexta").skeleton)
        assertNull(ex("as segunda").rec)
        assertNull(ex("marca para segunda").rec)
        assertNotNull(ex("marca para segunda").date)
    }
}

