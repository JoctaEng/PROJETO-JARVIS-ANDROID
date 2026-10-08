package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationSummaryTest {
    @Test fun transcriptKeepsTheEndWhenTooLong() {
        val msgs = (1..200).map { "Usuário" to "mensagem número $it" }
        val t = ConversationSummary.transcript(msgs, maxChars = 300)
        assertTrue(t.startsWith("…") && "mensagem número 200" in t && t.length <= 301)
    }

    @Test fun fallbackListsLastUserMessagesWithinLimit() {
        val users = (1..20).map { "pedido $it" }
        val s = ConversationSummary.fallback(users, maxChars = 400)
        assertTrue("pedido 20" in s && "pedido 1\n" !in s && s.length <= 400)
    }

    @Test fun titleIsShort() {
        assertEquals("corrigir a prova de cálculo da — 08/10", ConversationSummary.title("corrigir a prova de cálculo da turma de sexta", "08/10"))
        assertEquals("Conversa de 08/10", ConversationSummary.title("   ", "08/10"))
    }
}
