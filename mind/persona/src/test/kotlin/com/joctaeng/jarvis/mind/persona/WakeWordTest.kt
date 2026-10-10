package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WakeWordTest {
    @Test
    fun greetingPlusName() {
        assertEquals("", WakeWord.match("oi Joca", "Joca")?.rest)
        assertEquals("", WakeWord.match("Oi, Joca!", "Joca")?.rest)
        assertEquals("que horas são", WakeWord.match("ei joca, que horas são", "Joca")?.rest)
    }

    @Test
    fun toleratesRecognizerVariations() {
        assertNotNull(WakeWord.match("oi joka", "Joca"))
        assertNotNull(WakeWord.match("olá jóca", "Joca"))
        assertNotNull(WakeWord.match("e aí joca tudo bem", "Joca"))
    }

    @Test
    fun ignoresOtherSpeech() {
        assertNull(WakeWord.match("vamos fazer uma prova", "Joca"))
        assertNull(WakeWord.match("hoje o joca chegou tarde", "Joca"))
        assertNull(WakeWord.match("oi", "Joca"))
        assertNull(WakeWord.match("oi jonas", "Joca"))
    }
}

class WakeWordAnyNameTest {
    @Test fun answersToJocaOrTheAvatarName() {
        val names = listOf("Joca", "Guardião")
        assertEquals("que horas são", WakeWord.matchAny("Oi Joca que horas são", names)?.rest)
        assertEquals("abre a agenda", WakeWord.matchAny("oi guardiao abre a agenda", names)?.rest)
        assertEquals(null, WakeWord.matchAny("oi Luna tudo bem", names))
    }

    @Test fun compoundNameUsesFirstWord() {
        assertEquals("", WakeWord.matchAny("Olá Victoria", listOf("Joca", "Victoria Rubin"))?.rest)
    }

    @Test fun avatarPersonasKnowTheirNames() {
        assertEquals("Victoria", AvatarPersonas.byId("victoria")?.defaultName)
        assertEquals("Guardião", AvatarPersonas.byId("guardiao")?.defaultName)
        assertEquals(Gender.MALE, AvatarPersonas.byId("fumiriya")?.gender)
        assertEquals(null, AvatarPersonas.byId("nao-existe"))
    }
}
