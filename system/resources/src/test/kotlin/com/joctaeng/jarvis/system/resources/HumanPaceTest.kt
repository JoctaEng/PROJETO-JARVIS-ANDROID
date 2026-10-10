package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HumanPaceTest {
    @Test fun gesturesAreSpacedLikeAPerson() {
        assertEquals(0L, HumanPace.waitBeforeGesture(0L, 10_000L))
        assertEquals(300L, HumanPace.waitBeforeGesture(10_000L, 10_400L))
        assertEquals(0L, HumanPace.waitBeforeGesture(10_000L, 11_000L))
    }

    @Test fun swipeDirections() {
        val down = HumanPace.swipePath("baixo", 1000f, 2000f)
        assertTrue(down[1] > down[3], "para ver abaixo o dedo sobe")
        val up = HumanPace.swipePath("cima", 1000f, 2000f)
        assertTrue(up[1] < up[3])
        val next = HumanPace.swipePath("proximo", 1000f, 2000f)
        assertTrue(next[0] > next[2], "próximo: da direita para a esquerda")
        val prev = HumanPace.swipePath("anterior", 1000f, 2000f)
        assertTrue(prev[0] < prev[2])
    }

    @Test fun percentPointStaysOnScreen() {
        assertEquals(500f to 1000f, HumanPace.pointFromPercent(50.0, 50.0, 1000, 2000))
        assertEquals(999f to 1f, HumanPace.pointFromPercent(150.0, -3.0, 1000, 2000))
    }

    @Test fun settlesAfterQuietPeriod() {
        val s = ScreenSettle(quietMs = 450, minMs = 250, maxMs = 3_500)
        assertFalse(s.observe(1, 0))
        assertFalse(s.observe(2, 150)) // ainda mudando (app abrindo)
        assertFalse(s.observe(2, 400))
        assertTrue(s.observe(2, 650))
    }

    @Test fun emptyScreenWaitsUntilMax() {
        val s = ScreenSettle(quietMs = 450, minMs = 250, maxMs = 1_000)
        assertFalse(s.observe(ScreenSettle.EMPTY, 0))
        assertFalse(s.observe(ScreenSettle.EMPTY, 800))
        assertTrue(s.observe(ScreenSettle.EMPTY, 1_000))
        assertTrue(s.timedOut(1_000))
    }
}
