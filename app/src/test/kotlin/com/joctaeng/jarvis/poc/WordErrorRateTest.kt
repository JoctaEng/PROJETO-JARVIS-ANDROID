package com.joctaeng.jarvis.poc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordErrorRateTest {
    @Test fun identicalIgnoringCaseAndPunctuation() {
        assertEquals(0.0, WordErrorRate.compute("Ative o modo privado", "ative o modo privado."))
    }

    @Test fun oneSubstitutionInFourWords() {
        assertEquals(0.25, WordErrorRate.compute("ative o modo privado", "ative o modo pirata"))
    }

    @Test fun deletionsAndInsertionsCount() {
        assertEquals(0.5, WordErrorRate.compute("abra o aplicativo agora", "abra aplicativo"))
        assertEquals(0.5, WordErrorRate.compute("bom dia", "bom dia Joca"))
    }

    @Test fun accentsMatter() {
        assertEquals(0.5, WordErrorRate.compute("física amanhã", "fisica amanhã"))
    }

    @Test fun twentyPhrasesWithoutDigits() {
        assertEquals(20, SpeechTestPhrases.size)
        assertTrue(SpeechTestPhrases.none { phrase -> phrase.any(Char::isDigit) })
    }
}
