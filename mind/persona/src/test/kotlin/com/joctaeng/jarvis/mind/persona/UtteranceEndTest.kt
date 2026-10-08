package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtteranceEndTest {
    @Test fun unfinishedSentences() {
        listOf("eu queria saber sobre", "então eu fui lá e", "me explica porque,", "quero saber se", "a aula de matemática é", "e depois…")
            .forEach { assertTrue(UtteranceEnd.looksIncomplete(it), it) }
    }

    @Test fun finishedSentences() {
        listOf("como está meu dia", "abre a calculadora", "obrigado", "quanto é dois mais dois", "liga a lanterna", "")
            .forEach { assertFalse(UtteranceEnd.looksIncomplete(it), it) }
    }
}
