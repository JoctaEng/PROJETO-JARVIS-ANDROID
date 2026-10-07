package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpokenTextTest {
    @Test fun bhaskara() {
        assertEquals(
            "x igual a menos b mais ou menos raiz quadrada de delta sobre 2 a",
            SpokenMath.speak("x = \\frac{-b \\pm \\sqrt{\\Delta}}{2a}"),
        )
    }

    @Test fun discriminant() {
        assertEquals("delta igual a b ao quadrado menos 4 a c", SpokenMath.speak("\\Delta = b^2 - 4ac"))
    }

    @Test fun powersRootsAndFunctions() {
        assertEquals("a x ao quadrado mais b x mais c igual a 0", SpokenMath.speak("ax^2 + bx + c = 0"))
        assertEquals("x elevado a n mais 1", SpokenMath.speak("x^{n+1}"))
        assertEquals("raiz cúbica de 27 igual a 3", SpokenMath.speak("\\sqrt[3]{27} = 3"))
        assertEquals("seno ao quadrado de x mais cosseno ao quadrado de x igual a 1", SpokenMath.speak("\\sin^2 x + \\cos^2 x = 1"))
        assertEquals("x 1 igual a 3", SpokenMath.speak("x_1 = 3"))
        assertEquals("um meio", SpokenMath.speak("\\frac{1}{2}"))
        assertEquals("área igual a pi r ao quadrado", SpokenMath.speak("\\text{área} = \\pi r^2"))
        assertEquals("abre menos b", SpokenMath.speak("\\text{abre} \\left( -b \\right)"))
    }

    @Test fun markdownAndInlineMathBecomeSpeech() {
        val text = "## Fórmula de Bhaskara\n\n**Primeiro** calcule o discriminante: \$\\Delta = b^2 - 4ac\$.\n- Se \$\\Delta > 0\$, há duas raízes."
        val spoken = SpokenText.forSpeech(text)
        assertEquals(
            "Fórmula de Bhaskara. Primeiro calcule o discriminante: delta igual a b ao quadrado menos 4 a c. Se delta maior que 0, há duas raízes.",
            spoken,
        )
    }

    @Test fun displayAndParenthesisMath() {
        assertEquals("A fórmula é x igual a 2 a.", SpokenText.forSpeech("A fórmula é \\(x = 2a\\)."))
        assertTrue("raiz quadrada de 9" in SpokenText.forSpeech("Veja:\n\$\$\\sqrt{9} = 3\$\$\nPronto."))
    }

    @Test fun moneyIsNotMath() {
        val spoken = SpokenText.forSpeech("Custa R$ 10,00 e o outro R$20 no total.")
        assertEquals("Custa R$ 10,00 e o outro R$20 no total.", spoken)
    }

    @Test fun codeAndTablesAreNotReadSymbolBySymbol() {
        val spoken = SpokenText.forSpeech("Tabela:\n| a | b |\n|---|---|\n| 1 | 2 |\n```kotlin\nval x = 1\n```")
        assertFalse("|" in spoken)
        assertFalse("val x" in spoken)
        assertTrue("o código está na tela" in spoken)
    }
}
