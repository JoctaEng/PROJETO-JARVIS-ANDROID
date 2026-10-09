package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenTextTest {
    private val nodes = listOf(
        UiNode("Mensagens"),
        UiNode("Pesquisar", clickable = true),
        UiNode("Nova conversa", clickable = true),
        UiNode("", description = "Mais opções", clickable = true),
        UiNode("minha senha 123", password = true),
        UiNode("Mensagem", editable = true),
        UiNode("Enviar", clickable = true),
    )

    @Test
    fun rendersWithoutPasswordsOrRepeats() {
        val text = ScreenText.render(nodes + UiNode("Enviar", clickable = true))
        assertTrue("- Pesquisar [toca]" in text)
        assertTrue("- Mais opções [toca]" in text)
        assertTrue("- Mensagem [campo de texto]" in text)
        assertTrue("[campo de senha: não lido]" in text)
        assertFalse("123" in text)
        assertEquals(1, Regex("- Enviar").findAll(text).count())
    }

    @Test
    fun cutsLongScreens() {
        val many = (1..500).map { UiNode("Item numero $it", clickable = true) }
        val text = ScreenText.render(many, maxChars = 300)
        assertTrue(text.length < 400)
        assertTrue("tela cortada" in text)
    }

    @Test
    fun findsBestElementIgnoringAccentsAndPreferringClickable() {
        assertEquals(2, ScreenText.bestMatch(nodes, "nova conversa"))
        assertEquals(1, ScreenText.bestMatch(nodes, "pesquisar"))
        assertEquals(3, ScreenText.bestMatch(nodes, "mais opcoes"))
        assertEquals(6, ScreenText.bestMatch(nodes, "enviar"))
        assertNull(ScreenText.bestMatch(nodes, "senha"))
        assertNull(ScreenText.bestMatch(nodes, "xyz"))
    }

    @Test
    fun flagsSensitiveButtons() {
        assertTrue(ScreenText.isSensitive("Enviar"))
        assertTrue(ScreenText.isSensitive("Pagar com Pix"))
        assertTrue(ScreenText.isSensitive("Excluir conversa"))
        assertTrue(ScreenText.isSensitive("Desinstalar"))
        assertFalse(ScreenText.isSensitive("Aceitar"))
        assertFalse(ScreenText.isSensitive("Sair"))
        assertFalse(ScreenText.isSensitive("Pesquisar"))
        assertFalse(ScreenText.isSensitive("Nova conversa"))
    }
}
