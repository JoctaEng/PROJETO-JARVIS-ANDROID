package com.joctaeng.jarvis.mind.memory

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryStoreTest {
    private fun tmp(): File = File.createTempFile("summaries", ".json").also { it.delete(); it.deleteOnExit() }

    @Test fun addsUpdatesRemovesAndPersists() {
        val f = tmp()
        var now = 1_000L
        val store = SummaryStore(f) { now++ }
        val a = store.add("Prova de Cálculo", "Corrigir a prova até sexta.", useInNewChats = true)
        val b = store.add("Viagem", "Passagens para São Luís.")
        assertEquals(listOf(b.id, a.id), store.all().map { it.id }) // mais novo primeiro
        assertTrue(store.update(b.id, "Viagem a SLZ", "Passagens compradas.", useInNewChats = true))

        val reloaded = SummaryStore(f)
        assertEquals("Viagem a SLZ", reloaded.all().first().title)
        assertTrue(reloaded.all().all { it.useInNewChats })
        assertTrue(reloaded.remove(a.id))
        assertFalse(reloaded.remove("não existe"))
        assertEquals(1, SummaryStore(f).all().size)
    }

    @Test fun activeContextUsesOnlyMarkedAndRespectsLimit() {
        var now = 0L
        val store = SummaryStore(tmp()) { now++ }
        store.add("A", "x".repeat(100), useInNewChats = true)
        store.add("B", "não marcado")
        store.add("C", "y".repeat(100), useInNewChats = true)
        val ctx = store.activeContext(150)
        assertTrue(ctx.startsWith("- C:"))
        assertFalse("não marcado" in ctx)
        assertFalse("- A:" in ctx) // não cabe no limite
        assertTrue(ctx.length <= 150)
        assertEquals("", SummaryStore(tmp()).activeContext(500))
    }
}
