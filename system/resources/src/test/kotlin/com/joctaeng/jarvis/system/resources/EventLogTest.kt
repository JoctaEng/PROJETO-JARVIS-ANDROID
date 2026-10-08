package com.joctaeng.jarvis.system.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventLogTest {
    private fun tmp(): File = File.createTempFile("euno", ".log").also { it.delete(); it.deleteOnExit() }

    @Test fun writesLevelTagAndMessage() {
        val f = tmp()
        val log = EventLog(f, clock = { 0L })
        log.warn("voz", "Gemini falhou\nlinha 2")
        val text = f.readText()
        assertTrue(" W voz: Gemini falhou linha 2" in text)
        assertEquals(1, text.lines().count { it.isNotBlank() })
    }

    @Test fun errorKeepsShortStackTrace() {
        val f = tmp()
        EventLog(f).error("conversa", "falhou", IllegalStateException("boom"))
        val text = f.readText()
        assertTrue("IllegalStateException: boom" in text)
        assertTrue(text.lines().size < 14)
    }

    @Test fun rotatesWhenTooBigAndTailSeesBoth() {
        val f = tmp()
        val log = EventLog(f, maxBytes = 200)
        repeat(30) { log.info("t", "evento número $it com algum texto") }
        assertTrue(File(f.path + ".1").exists())
        val tail = log.tail()
        assertTrue("evento número 29" in tail)
        f.delete(); File(f.path + ".1").delete()
    }

    @Test fun tailIsCappedAndNeverThrows() {
        val f = tmp()
        val log = EventLog(f)
        repeat(50) { log.info("t", "x".repeat(100)) }
        assertTrue(log.tail(500).length < 600)
        EventLog(File("/proc/nao/existe/arquivo.log")).error("t", "não deve lançar")
    }

    @Test fun clearRemovesEverything() {
        val f = tmp()
        val log = EventLog(f)
        log.info("t", "a")
        log.clear()
        assertFalse(f.exists())
    }
}

class ErrorReportTest {
    @Test fun containsHeaderAndSections() {
        val r = ErrorReport.build(listOf("Versão" to "0.6.2"), listOf("Eventos" to "linha A", "Vazio" to ""))
        assertTrue("- Versão: 0.6.2" in r && "## Eventos" in r && "linha A" in r && "(vazio)" in r)
    }

    @Test fun truncatesKeepingTheEnd() {
        val body = (1..3000).joinToString("\n") { "linha $it" }
        val r = ErrorReport.build(emptyList(), listOf("Eventos" to body), maxChars = 5_000)
        assertTrue("linha 3000" in r)
        assertFalse("linha 1\n" in r)
        assertTrue(r.length < 6_000)
    }
}
