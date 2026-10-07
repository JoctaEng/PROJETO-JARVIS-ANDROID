package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfKnowledgeTest {
    private val info = SelfInfo(
        versionName = "0.6.0", brainNames = listOf("Gemini"), voiceName = "Kokoro", autonomyLabel = "Operador",
        toolNames = listOf("abrir_app", "memorizar"), memoryCount = 3, privateMode = false,
    )

    @Test fun reportsRealState() {
        val s = SelfKnowledge.section(info)
        assertTrue("Euno 0.6.0" in s && "Gemini" in s && "abrir_app" in s && "Memórias guardadas: 3" in s)
        assertTrue("Ainda não existe" in s)
    }

    @Test fun compactOmitsRoadmapAndNoToolsIsHonest() {
        val s = SelfKnowledge.section(info.copy(toolNames = emptyList(), brainNames = emptyList()), compact = true)
        assertFalse("Ainda não existe" in s)
        assertTrue("nenhuma disponível" in s && "nenhum configurado" in s)
    }
}

class DismissCommandsTest {
    @Test fun recognizesGoodbyes() {
        listOf("tchau", "Tchau!", "Ei Euno, até logo", "pode ir", "valeu, tchau", "tchau, obrigado").forEach {
            assertTrue(DismissCommands.matches(it), it)
        }
    }

    @Test fun ignoresLongerSentences() {
        listOf("explique o tchau em japonês", "me diga tchau em inglês e francês por favor agora", "quanto é 2+2", "").forEach {
            assertFalse(DismissCommands.matches(it), it)
        }
    }
}
