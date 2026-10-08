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

    @Test fun stopListening() {
        listOf("encerrar", "Pra encerrar", "para de ouvir", "chega", "pode encerrar", "Euno, encerra", "desliga o microfone", "silêncio")
            .forEach { assertEquals(VoiceCommand.STOP_LISTENING, VoiceCommands.parse(it), it) }
    }

    @Test fun normalSentencesAreNotCommands() {
        listOf(
            "como se diz tchau em japonês", "explique o tchau em japonês", "pode ir ao google e pesquisar", "quanto é 2+2",
            "fecha o app do WhatsApp", "me diga tchau em inglês e francês por favor agora", "para onde vamos amanhã", "",
        ).forEach { assertNull(VoiceCommands.parse(it), it) }
    }
}
