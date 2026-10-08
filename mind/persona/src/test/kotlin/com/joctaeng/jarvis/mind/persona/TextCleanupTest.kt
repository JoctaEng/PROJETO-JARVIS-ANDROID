package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCleanupTest {
    @Test
    fun unwrapsPhoneNumbersButKeepsFormulas() {
        assertEquals("o número 98987177598 está salvo", TextCleanup.unwrapPlainNumbers("o número \$98987177598\$ está salvo"))
        assertEquals("(98) 98717-7598", TextCleanup.unwrapPlainNumbers("\$(98) 98717-7598\$"))
        assertEquals("às 18:35", TextCleanup.unwrapPlainNumbers("às \$18:35\$"))
        assertEquals("\$x^2 + 1\$", TextCleanup.unwrapPlainNumbers("\$x^2 + 1\$"))
        assertEquals("\$\$\\Delta = 4\$\$", TextCleanup.unwrapPlainNumbers("\$\$\\Delta = 4\$\$"))
        assertEquals("\$2x = 6\$", TextCleanup.unwrapPlainNumbers("\$2x = 6\$"))
    }

    @Test
    fun stripsEmotionTagsAnywhere() {
        assertEquals("Vou abrir. Puxa, não achei.", TextCleanup.stripEmotionTags("Vou abrir. [confuso] Puxa, não achei."))
        assertEquals("ok [nota]", TextCleanup.stripEmotionTags("[Pensativo] ok [nota]"))
    }
}
