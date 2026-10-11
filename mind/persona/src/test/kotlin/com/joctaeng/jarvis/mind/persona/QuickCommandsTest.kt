package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickCommandsTest {
    @Test fun openApp() {
        assertEquals(QuickCommand.OpenApp("Gmail"), QuickCommands.parse("Abra o Gmail"))
        assertEquals(QuickCommand.OpenApp("WhatsApp Business"), QuickCommands.parse("abre o WhatsApp Business"))
        assertEquals(QuickCommand.OpenApp("Agenda"), QuickCommands.parse("Euno, abre a agenda"))
        assertEquals(QuickCommand.OpenApp("Gmail"), QuickCommands.parse("Abra o Gmail."))
    }

    @Test fun compoundGoesToTheBrain() {
        assertNull(QuickCommands.parse("abre o WhatsApp e manda um oi para a Maria"))
        assertNull(QuickCommands.parse("abra o Gmail e procure a fatura"))
        assertNull(QuickCommands.parse("abra a agenda no dia 15"))
        assertNull(QuickCommands.parse("o que tem na minha agenda"))
    }

    @Test fun homeBackAndScroll() {
        assertEquals(QuickCommand.Home, QuickCommands.parse("volta para a tela inicial"))
        assertEquals(QuickCommand.Home, QuickCommands.parse("Sai do app"))
        assertEquals(QuickCommand.Back, QuickCommands.parse("voltar"))
        assertEquals(QuickCommand.Scroll("baixo"), QuickCommands.parse("rola para baixo"))
        assertEquals(QuickCommand.Scroll("baixo"), QuickCommands.parse("role mais"))
        assertEquals(QuickCommand.Scroll("cima"), QuickCommands.parse("sobe a tela"))
        assertEquals(QuickCommand.Scroll("proximo"), QuickCommands.parse("para direita"))
        assertEquals(QuickCommand.Scroll("anterior"), QuickCommands.parse("vai para a esquerda"))
        assertEquals(QuickCommand.Scroll("proximo"), QuickCommands.parse("passa para a próxima semana"))
    }

    @Test fun conversationIsNotACommand() {
        assertNull(QuickCommands.parse("tudo bem com você?"))
        assertNull(QuickCommands.parse("para"))
        assertNull(QuickCommands.parse("vai chover amanhã?"))
    }

    @Test fun vagueTargetGoesToTheBrain() {
        assertNull(QuickCommands.parse("abra um deles"))
        assertNull(QuickCommands.parse("abre a outra"))
        assertNull(QuickCommands.parse("abre essa"))
    }
}
