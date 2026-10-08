package com.joctaeng.jarvis.system.resources

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgendaTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = Instant.parse("2026-10-08T15:00:00Z") // 12:00 em São Paulo

    private fun at(isoLocal: String) = java.time.LocalDateTime.parse(isoLocal).atZone(zone).toInstant().toEpochMilli()

    @Test fun periodParsing() {
        assertEquals(AgendaPeriod.HOJE, AgendaPeriod.parse(null))
        assertEquals(AgendaPeriod.AMANHA, AgendaPeriod.parse("Amanhã"))
        assertEquals(AgendaPeriod.SEMANA, AgendaPeriod.parse("semana"))
    }

    @Test fun rangeCoversWholeLocalDays() {
        val (s, e) = AgendaFormatter.range(AgendaPeriod.HOJE, zone, now)
        assertEquals(at("2026-10-08T00:00:00"), s)
        assertEquals(at("2026-10-09T00:00:00"), e)
        val (s2, e2) = AgendaFormatter.range(AgendaPeriod.AMANHA, zone, now)
        assertEquals(at("2026-10-09T00:00:00"), s2)
        assertEquals(at("2026-10-10T00:00:00"), e2)
        assertEquals(at("2026-10-15T00:00:00"), AgendaFormatter.range(AgendaPeriod.SEMANA, zone, now).second)
    }

    @Test fun emptyAgendaIsStatedHonestly() {
        assertEquals("Nenhum compromisso na agenda para hoje.", AgendaFormatter.format(emptyList(), AgendaPeriod.HOJE, zone, now))
    }

    @Test fun listsEventsInTimeOrderWithLocationAndAllDayFirst() {
        val events = listOf(
            AgendaEvent("Aula de Cálculo", at("2026-10-08T14:00:00"), at("2026-10-08T15:30:00"), false, "Sala 3"),
            AgendaEvent("Reunião", at("2026-10-08T09:00:00"), at("2026-10-08T10:00:00"), false),
            AgendaEvent("Feriado", Instant.parse("2026-10-08T00:00:00Z").toEpochMilli(), Instant.parse("2026-10-09T00:00:00Z").toEpochMilli(), true),
        )
        val text = AgendaFormatter.format(events, AgendaPeriod.HOJE, zone, now)
        val lines = text.lines()
        assertEquals("Agenda para hoje:", lines[0])
        assertEquals("- dia inteiro Feriado", lines[1])
        assertEquals("- 09:00–10:00 Reunião", lines[2])
        assertEquals("- 14:00–15:30 Aula de Cálculo (local: Sala 3)", lines[3])
    }

    @Test fun weekGroupsByDay() {
        val events = listOf(
            AgendaEvent("A", at("2026-10-08T09:00:00"), at("2026-10-08T10:00:00"), false),
            AgendaEvent("B", at("2026-10-09T09:00:00"), at("2026-10-09T10:00:00"), false),
        )
        val text = AgendaFormatter.format(events, AgendaPeriod.SEMANA, zone, now)
        assertTrue("quinta-feira, 08/10:" in text && "sexta-feira, 09/10:" in text)
    }
}
