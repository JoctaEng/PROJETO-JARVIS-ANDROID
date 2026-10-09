package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowPickTest {
    private val own = "com.joctaeng.jarvis"

    @Test fun ignoraAJanelaDoEunoEmFoco() {
        val w = listOf(
            WindowInfo(own, isApp = true, layer = 3, active = true),
            WindowInfo("com.whatsapp.w4b", isApp = true, layer = 1),
        )
        assertEquals(1, WindowPick.pick(w, own))
    }

    @Test fun preferenciaPelaAtivaDeOutroApp() {
        val w = listOf(
            WindowInfo("a", isApp = true, layer = 5),
            WindowInfo("b", isApp = true, layer = 1, active = true),
        )
        assertEquals(1, WindowPick.pick(w, own))
    }

    @Test fun semAtivaVaiParaAMaisAFrente() {
        val w = listOf(
            WindowInfo("a", isApp = true, layer = 1),
            WindowInfo("b", isApp = true, layer = 4),
            WindowInfo("barra", isApp = false, layer = 9),
        )
        assertEquals(1, WindowPick.pick(w, own))
    }

    @Test fun soEunoNaTelaDevolveNulo() {
        assertNull(WindowPick.pick(listOf(WindowInfo(own, isApp = true, layer = 1, active = true)), own))
    }
}
