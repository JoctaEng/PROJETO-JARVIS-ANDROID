package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorReportBudgetTest {
    @Test
    fun smallSectionsStayWholeAndBigOneGetsTheRest() {
        assertEquals(listOf(100, 50, 850), ErrorReport.share(listOf(100, 50, 5_000), 1_000))
        assertEquals(listOf(400, 400), ErrorReport.share(listOf(900, 700), 800))
    }

    @Test
    fun eventsAreNotCutWhenThereIsRoom() {
        val events = "x".repeat(30_000)
        val text = ErrorReport.build(listOf("v" to "1"), listOf("Eventos" to events, "A" to "", "B" to "", "C" to "", "D" to ""), 40_000)
        assertTrue(text.contains(events))
    }

    @Test
    fun summaryGroupsEveryProblemWithCounts() {
        val log = """
            2026-10-08 18:39:25.179 E escuta: falha no reconhecimento: Erro (11) (código 11)
            2026-10-08 18:40:01.000 I conversa: turno ok
            2026-10-08 18:40:25.179 E escuta: falha no reconhecimento: Erro (11) (código 11)
            2026-10-08 18:41:00.000 W voz: frase de 31 caracteres sem áudio natural (0 ms)
            2026-10-08 18:42:00.000 C queda: NullPointerException
        """.trimIndent()
        val s = ErrorReport.problemSummary(log)
        assertTrue(s.startsWith("Total: 4 ocorrências em 3 tipos."), s)
        assertTrue(s.contains("ERRO [escuta] ×2 (18:39:25 → 18:40:25)"), s)
        assertTrue(s.lines()[1].startsWith("QUEDA"), s)
    }
}
