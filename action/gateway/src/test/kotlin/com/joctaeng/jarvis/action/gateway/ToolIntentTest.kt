package com.joctaeng.jarvis.action.gateway

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolIntentTest {
    @Test fun actionRequestsTriggerTools() {
        assertTrue(ToolIntent.likely("abre a calculadora pra mim"))
        assertTrue(ToolIntent.likely("liga a lanterna"))
        assertTrue(ToolIntent.likely("Me mostra o mapa até a escola"))
    }

    @Test fun smallTalkDoesNotTriggerTools() {
        assertFalse(ToolIntent.likely("oi"))
        assertFalse(ToolIntent.likely("vc está demorando demais"))
        assertFalse(ToolIntent.likely("explique a derivada de x ao quadrado"))
    }
}
