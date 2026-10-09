package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCommandsTest {
    @Test fun goodbyesDismiss() {
        listOf(
            "tchau", "Tchau!", "Tchau, Euno.", "tchau tchau", "Ei Euno, até logo", "até mais tarde", "obrigado, tchau", "tchau e obrigado",
            "pode ir", "pode se esconder", "Euno pode descansar", "é só isso", "valeu, tchau",
        ).forEach { assertEquals(VoiceCommand.DISMISS, VoiceCommands.parse(it), it) }
    }

    @Test fun greetingsAreNotGoodbyes() {
        listOf("E aí tudo bem", "e ai, tudo bem?", "tudo bem", "oi, tudo bem", "e tudo bem com você")
            .forEach { assertNull(VoiceCommands.parse(it), it) }
    }

    @Test fun stopListening() {
        listOf("encerrar", "Pra encerrar", "para de ouvir", "pode encerrar", "Euno, encerra", "desliga o microfone")
            .forEach { assertEquals(VoiceCommand.STOP_LISTENING, VoiceCommands.parse(it), it) }
    }

    @Test fun interruptions() {
        listOf("pera aí", "Espera!", "calma", "um momento", "chega", "silêncio", "para", "Euno, espera aí", "só um momento")
            .forEach { assertEquals(VoiceCommand.STOP, VoiceCommands.parse(it), it) }
    }

    @Test fun normalSentencesAreNotCommands() {
        listOf(
            "como se diz tchau em japonês", "explique o tchau em japonês", "pode ir ao google e pesquisar", "quanto é 2+2",
            "fecha o app do WhatsApp", "me diga tchau em inglês e francês por favor agora", "para onde vamos amanhã", "",
        ).forEach { assertNull(VoiceCommands.parse(it), it) }
    }
}
