package com.joctaeng.jarvis.mind.persona

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCleanupTest {
    @Test
    fun unwrapsPhoneNumbersButKeepsFormulas() {
        assertEquals("o número 91912345678 está salvo", TextCleanup.unwrapPlainNumbers("o número \$91912345678\$ está salvo"))
        assertEquals("(91) 91234-5678", TextCleanup.unwrapPlainNumbers("\$(91) 91234-5678\$"))
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

class HistoryTrimTest {
    @Test
    fun keepsNewestWithinBudget() {
        val msgs = listOf("a".repeat(2000), "b".repeat(1500), "c".repeat(1000), "d".repeat(100))
        assertEquals(listOf("c".repeat(1000), "d".repeat(100)), HistoryTrim.keepRecent(msgs, { it.length }, 2_000, 8))
        assertEquals(listOf("d".repeat(100)), HistoryTrim.keepRecent(msgs, { it.length }, 2_000, 1))
        assertEquals(listOf("x".repeat(9000)), HistoryTrim.keepRecent(listOf("y", "x".repeat(9000)), { it.length }, 2_000, 8))
    }
}
