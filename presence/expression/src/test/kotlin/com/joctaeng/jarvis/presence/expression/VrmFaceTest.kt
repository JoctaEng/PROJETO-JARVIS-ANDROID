package com.joctaeng.jarvis.presence.expression

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VrmFaceTest {
    @Test fun visemesBecomeVrmMouthShapes() {
        assertEquals(mapOf("aa" to 1f), VrmFace.mouth(Viseme.D, 1f))
        assertEquals(mapOf("ou" to 1f), VrmFace.mouth(Viseme.F, Viseme.F.open))
        assertEquals(mapOf("oh" to 0.5f), VrmFace.mouth(Viseme.E, Viseme.E.open / 2))
        assertTrue(VrmFace.mouth(Viseme.A, 0f).isEmpty())
        assertTrue(VrmFace.mouth(Viseme.X, 0.5f).isEmpty())
    }

    @Test fun emotionsAndModes() {
        assertEquals("happy" to 0.75f, VrmFace.emotion(Emotion.HAPPY))
        assertEquals(null to 0f, VrmFace.emotion(Emotion.NEUTRAL))
        assertEquals("speaking", VrmFace.mode(AnimState.SPEAKING))
        assertEquals("idle", VrmFace.mode(AnimState.DRAGGED))
    }

    @Test fun scriptIsValidForThePage() {
        val js = VrmFace.script(AnimState.SPEAKING, Emotion.SURPRISED, Viseme.D, 0.5f, 0f, -0.25f)
        assertEquals("Avatar.setMode('speaking');Avatar.setMouth({aa:0.50});Avatar.setEmotion('surprised',0.85);Avatar.lookAt(0.00,-0.25);", js)
        // Fora da fala a boca fica fechada, mesmo com abertura antiga.
        assertTrue(VrmFace.script(AnimState.IDLE, Emotion.NEUTRAL, Viseme.D, 1f, 0f, 0f).contains("setMouth({})"))
    }
}
