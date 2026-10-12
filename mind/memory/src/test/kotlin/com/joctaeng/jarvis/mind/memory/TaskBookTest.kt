package com.joctaeng.jarvis.mind.memory

import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TaskBookTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    // sábado, 10/10/2026, 20:00 em Brasília
    private val now = ZonedDateTime.of(2026, 10, 10, 20, 0, 0, 0, zone).toInstant()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant()
    private fun task(repeat: Repeat, h: Int = 7, m: Int = 0, days: Set<Int> = emptySet(), date: LocalDate? = null) =
        ScheduledTask("t", "Bom dia", "resumo do dia", h, m, repeat, days, date)

    @Test fun dailyLaterToday() = assertEquals(at(2026, 10, 10, 21, 30), TaskSchedule.next(task(Repeat.DAILY, 21, 30), now, zone))
    @Test fun dailyTomorrow() = assertEquals(at(2026, 10, 11, 7, 0), TaskSchedule.next(task(Repeat.DAILY), now, zone))
    @Test fun weekdaysSkipSunday() = assertEquals(at(2026, 10, 12, 7, 0), TaskSchedule.next(task(Repeat.WEEKDAYS), now, zone))
    @Test fun specificDays() = assertEquals(at(2026, 10, 14, 6, 0), TaskSchedule.next(task(Repeat.DAYS, 6, 0, setOf(3)), now, zone))
    @Test fun onceInFuture() = assertEquals(at(2026, 10, 15, 17, 0), TaskSchedule.next(task(Repeat.ONCE, 17, 0, date = LocalDate.of(2026, 10, 15)), now, zone))
    @Test fun oncePast() = assertNull(TaskSchedule.next(task(Repeat.ONCE, 7, 0, date = LocalDate.of(2026, 10, 9)), now, zone))
    @Test fun disabled() = assertNull(TaskSchedule.next(task(Repeat.DAILY).copy(enabled = false), now, zone))

    @Test fun describe() {
        assertEquals("dias úteis às 07:00", TaskSchedule.describe(task(Repeat.WEEKDAYS)))
        assertEquals("seg, qua às 06:00", TaskSchedule.describe(task(Repeat.DAYS, 6, 0, setOf(3, 1))))
        assertEquals(1, TaskSchedule.dayNumber("Segunda-feira"))
        assertEquals(6, TaskSchedule.dayNumber("sábado"))
    }

    @Test fun persistRunAndStopAll() {
        val dir = Files.createTempDirectory("tasks").toFile()
        val f = java.io.File(dir, "t.json")
        val b = TaskBook(f, clock = { 1000L })
        val once = b.add(task(Repeat.ONCE, 17, 0, date = LocalDate.of(2026, 10, 15)).copy(id = ""))
        b.add(task(Repeat.DAILY).copy(id = "diaria", name = "Notícias"))
        b.recordRun(once.id, true, "ok")
        val again = TaskBook(f)
        assertFalse(again.get(once.id)!!.enabled) // uma vez só: desliga depois de rodar
        assertEquals(1, again.history().size)
        assertEquals("diaria", again.find("noticias")?.id)
        again.disableAll()
        assertFalse(TaskBook(f).all().any { it.enabled })
    }

    @Test fun sameNameReplacesInsteadOfDuplicating() {
        val f = Files.createTempFile("tarefas", ".json").toFile().apply { delete() }
        val b = TaskBook(f, clock = { 1000L })
        b.add(task(Repeat.DAILY, 8, 0).copy(id = "a", name = "Resumo de e-mails"))
        b.add(task(Repeat.DAILY, 8, 0).copy(id = "b", name = "resumo de e-mails!", instruction = "gmail_buscar"))
        b.add(task(Repeat.DAILY, 8, 0).copy(id = "c", name = "Outra"))
        assertEquals(2, b.all().size)
        assertEquals("gmail_buscar", b.all().first { it.name.startsWith("resumo") || it.name.startsWith("Resumo") }.instruction)
    }
}
