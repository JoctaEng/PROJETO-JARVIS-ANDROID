package com.joctaeng.jarvis.presence.placement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementTest {
    // Tela do Redmi Note 13 Pro+: 1220 x 2712 px.
    private val screen = Size(1220, 2712)
    private val window = Size(200, 200)
    private val insets = Insets(top = 100, bottom = 120)

    @Test fun snapsToNearestSideEdge() {
        assertEquals(Point(0, 500), Placement.snapToEdge(Point(300, 500), window, screen, insets))
        assertEquals(Point(1020, 500), Placement.snapToEdge(Point(800, 500), window, screen, insets))
    }

    @Test fun neverLeavesUsableArea() {
        assertEquals(Point(1020, 100), Placement.clamp(Point(5_000, -50), window, screen, insets))
        assertEquals(Point(0, 2392), Placement.clamp(Point(-10, 9_999), window, screen, insets))
    }

    @Test fun normalizedPositionSurvivesRotation() {
        val portrait = Placement.normalize(Point(1020, 1256), window, screen)
        val landscape = Size(2712, 1220)
        assertEquals(Point(2512, 510), Placement.denormalize(portrait, window, landscape))
    }

    @Test fun remembersPositionPerApp() {
        val memory = PositionMemory(default = NormalizedPosition(1f, 0.5f))
        memory.remember("com.whatsapp", NormalizedPosition(0f, 0.2f))
        assertEquals(NormalizedPosition(0f, 0.2f), memory.recall("com.whatsapp"))
        assertEquals(NormalizedPosition(1f, 0.5f), memory.recall("com.android.chrome"))
        assertEquals(NormalizedPosition(1f, 0.5f), memory.recall(null))
    }

    @Test fun autoMinimizeOnlyForListedApps() {
        val rule = AutoMinimizeRule(setOf("com.google.android.youtube"))
        assertTrue(rule.shouldMinimize("com.google.android.youtube"))
        assertFalse(rule.shouldMinimize("com.whatsapp"))
        assertFalse(rule.shouldMinimize(null))
    }
}
