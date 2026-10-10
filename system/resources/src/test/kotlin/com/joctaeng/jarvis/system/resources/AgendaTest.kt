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

class AgendaRequestTest {
    private val zone = java.time.ZoneId.of("America/Sao_Paulo")
    // Sábado, 10/10/2026, 12:00 em Brasília.
    private val now = java.time.ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, zone).toInstant()
    private val today = java.time.LocalDate.of(2026, 10, 10)

    @Test fun specificDayFromManyFormats() {
        for (text in listOf("15/10", "2026-10-15", "15/10/2026", "dia 15", "15")) {
            assertEquals(java.time.LocalDate.of(2026, 10, 15), AgendaRequest.parseDate(text, today), text)
        }
    }

    @Test fun dayAlreadyPassedMeansNextMonth() {
        assertEquals(java.time.LocalDate.of(2026, 11, 5), AgendaRequest.parseDate("dia 5", today))
    }

    @Test fun dateInPeriodFieldIsNotSilentlyToday() {
        val w = AgendaRequest.resolve("15/10", null, null, zone, now).getOrThrow()
        assertEquals(java.time.LocalDate.of(2026, 10, 15), w.firstDay)
        assertTrue("15/10/2026" in w.label)
        // "17/10" tinha "7" e virava "semana"
        assertEquals(java.time.LocalDate.of(2026, 10, 17), AgendaRequest.resolve("17/10", null, null, zone, now).getOrThrow().firstDay)
    }

    @Test fun periodsAndErrors() {
        assertEquals(today, AgendaRequest.resolve("hoje", null, null, zone, now).getOrThrow().firstDay)
        assertEquals(today.plusDays(1), AgendaRequest.resolve("amanhã", null, null, zone, now).getOrThrow().firstDay)
        assertTrue(AgendaRequest.resolve("semana", null, null, zone, now).getOrThrow().multiDay)
        assertTrue(AgendaRequest.resolve("quando der", null, null, zone, now).isFailure)
        assertTrue(AgendaRequest.resolve(null, "ontem à tarde", null, zone, now).isFailure)
    }
}
