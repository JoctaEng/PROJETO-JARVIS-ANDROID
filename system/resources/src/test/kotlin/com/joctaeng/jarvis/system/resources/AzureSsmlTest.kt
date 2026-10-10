package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AzureSsmlTest {
    @Test fun escapaCaracteresEspeciais() {
        val s = AzureSsml.build("Tom & Jerry <oi>", "pt-BR-FranciscaNeural")
        assertTrue(s.contains("Tom &amp; Jerry &lt;oi&gt;"))
        assertTrue(s.contains("xml:lang='pt-BR'"))
        assertTrue(s.contains("name='pt-BR-FranciscaNeural'"))
    }

    @Test fun velocidade() {
        assertTrue(AzureSsml.build("a", "pt-BR-X", 1.2f).contains("rate='+20%'"))
        assertTrue(AzureSsml.build("a", "pt-BR-X", 0.8f).contains("rate='-20%'"))
    }

    @Test fun mes() = assertEquals("2026-10", AzureSsml.monthKey(1_791_600_000_000L))
}
