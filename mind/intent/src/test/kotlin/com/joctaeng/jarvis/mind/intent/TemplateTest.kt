package com.joctaeng.jarvis.mind.intent

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateTest {
    private val today = LocalDate.of(2026, 10, 11)
    private fun ex(s: String) = Extract.of(s, today)

    @Test fun wordsAndAlternatives() {
        val t = Template("(abre|abra|abrir) o gmail")
        assertNotNull(t.match(ex("Abra o Gmail")))
        assertNull(t.match(ex("abra o whatsapp")))
    }

    @Test fun optionalAndPrefix() {
        val t = Template("(por favor)? abr~ a agenda")
        assertNotNull(t.match(ex("abre a agenda")))
        assertNotNull(t.match(ex("por favor abrindo a agenda")))
        assertNull(t.match(ex("fecha a agenda")))
    }

    @Test fun captureKeepsOriginalWords() {
        val t = Template("(marca|agenda) {titulo} <D> <T>")
        val caps = t.match(ex("marca Reunião com a Coordenação amanhã às 15h"))
        assertEquals("Reunião com a Coordenação", caps?.get("titulo"))
    }

    @Test fun optionalCapture() {
        val t = Template("abre a conversa (com|da|do) {contato}")
        assertEquals("Maria", t.match(ex("abre a conversa com Maria"))?.get("contato"))
        val t2 = Template("o que tenho {algo?} <D>")
        assertNotNull(t2.match(ex("o que tenho amanhã")))
    }

    @Test fun placeholdersAreNotInCaptures() {
        val t = Template("lembra {texto}")
        assertEquals("de ligar", t.match(ex("lembra de ligar amanhã"))?.get("texto"))
    }

    @Test fun anythingAndSpecificity() {
        val a = Template("... agenda ...")
        assertNotNull(a.match(ex("quero ver a agenda de hoje")))
        assertTrue(Template("o que tenho <D>").specificity > Template("... <D>").specificity)
    }

    @Test fun expandAndCount() {
        val t = Template("(o que|quais) (eu )?(tenho|tem) <D>")
        assertEquals(2L * 2 * 2 * 1, t.count(mapOf("xdatax" to listOf("amanhã"))) - 0L + 0L * 0 - 0L + 0L)
        val phrases = t.expand(mapOf("xdatax" to listOf("amanhã")), 20)
        assertTrue(phrases.size >= 4)
        phrases.forEach { assertNotNull(t.match(ex(it)), it) }
    }
}
