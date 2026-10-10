package com.joctaeng.jarvis.mind.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrainSlotsTest {
    private val gemini = BrainSlot("gemini", "GEMINI", "https://g/v1", "gemini-flash")
    private val groq = BrainSlot("groq", "GROQ", "https://q/v1", "openai/gpt-oss-20b", enabled = false)

    @Test fun roundTrip() {
        assertEquals(listOf(gemini, groq), BrainSlots.decode(BrainSlots.encode(listOf(gemini, groq))))
        assertEquals(emptyList(), BrainSlots.decode(""))
    }

    @Test fun moveAndRemove() {
        val list = listOf(gemini, groq)
        assertEquals(listOf(groq, gemini), BrainSlots.move(list, "groq", -1))
        assertEquals(list, BrainSlots.move(list, "gemini", -1))
        assertEquals(listOf(groq), BrainSlots.remove(list, "gemini"))
    }

    @Test fun upsertAndNewId() {
        val list = listOf(gemini)
        assertEquals("groq", BrainSlots.newId(list, "GROQ"))
        assertEquals("gemini2", BrainSlots.newId(list, "GEMINI"))
        val changed = gemini.copy(model = "outro")
        assertEquals(listOf(changed), BrainSlots.upsert(list, changed))
        assertEquals(listOf(gemini, groq), BrainSlots.upsert(list, groq))
    }

    @Test fun cooldownOnlyFor429() {
        val c = ProviderCooldown()
        c.note("groq", "erro no servidor (500)", 0, null)
        assertFalse(c.resting("groq", 1))
        c.note("groq", "limite de uso atingido (429): try again in 21s", 0, 21_000)
        assertTrue(c.resting("groq", 20_000))
        assertFalse(c.resting("groq", 23_000))
    }
}
