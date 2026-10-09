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

class EventLogDailyTest {
    private val day = 86_400_000L

    @Test
    fun um_arquivo_por_dia_e_apaga_os_antigos() {
        val dir = kotlin.io.path.createTempDirectory("logs").toFile()
        var t = 1_000_000_000_000L
        val log = EventLog.daily(dir, keepDays = 3, clock = { t })
        repeat(5) { log.info("t", "dia $it"); t += day }
        assertEquals(3, log.stats().first)
        val all = log.readAll()
        assertTrue(!all.contains("dia 0") && all.contains("dia 2") && all.contains("dia 4"))
        assertTrue(all.indexOf("dia 2") < all.indexOf("dia 4"))
    }

    @Test
    fun rotaciona_dentro_do_dia_sem_perder_ordem() {
        val dir = kotlin.io.path.createTempDirectory("logs").toFile()
        val log = EventLog.daily(dir, maxBytes = 100, clock = { 1_000_000_000_000L })
        repeat(10) { log.info("t", "linha numero $it") }
        val all = log.readAll()
        assertTrue(all.indexOf("numero 8") < all.indexOf("numero 9"))
    }

    @Test
    fun ignora_arquivos_que_nao_sao_de_um_dia() {
        val dir = kotlin.io.path.createTempDirectory("logs").toFile()
        java.io.File(dir, "euno-eventos.log").writeText("velho\n")
        val log = EventLog.daily(dir, clock = { 1_000_000_000_000L })
        log.info("t", "novo")
        assertTrue(!log.readAll().contains("velho"))
    }
}
