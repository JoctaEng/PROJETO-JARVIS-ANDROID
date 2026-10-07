package com.joctaeng.jarvis.presence.expression

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpressionPickerTest {
    @Test
    fun speakingAlternatesBetweenOpenMouthAndEmotion() {
        assertEquals(Expression.FALANDO, ExpressionPicker.pick(AnimState.SPEAKING, Emotion.HAPPY, 0.8f))
        assertEquals(Expression.FELIZ, ExpressionPicker.pick(AnimState.SPEAKING, Emotion.HAPPY, 0.2f))
        assertEquals(Expression.NEUTRO, ExpressionPicker.pick(AnimState.SPEAKING, Emotion.NEUTRAL, 0f))
    }

    @Test
    fun stateWinsOverEmotion() {
        assertEquals(Expression.DORMINDO, ExpressionPicker.pick(AnimState.SLEEPING, Emotion.HAPPY))
        assertEquals(Expression.OUVINDO, ExpressionPicker.pick(AnimState.LISTENING, Emotion.SURPRISED))
        assertEquals(Expression.PENSATIVO, ExpressionPicker.pick(AnimState.THINKING, Emotion.HAPPY))
        assertEquals(Expression.PREOCUPADO, ExpressionPicker.pick(AnimState.ERROR, Emotion.HAPPY))
    }

    @Test
    fun idleFollowsEmotion() {
        assertEquals(Expression.SURPRESO, ExpressionPicker.pick(AnimState.IDLE, Emotion.SURPRISED))
        assertEquals(Expression.FELIZ, ExpressionPicker.pick(AnimState.IDLE, Emotion.PLAYFUL))
        assertEquals(Expression.NEUTRO, ExpressionPicker.pick(AnimState.IDLE, Emotion.NEUTRAL))
    }

    @Test
    fun missingArtFallsBack() {
        val art = mapOf(Expression.NEUTRO to "n", Expression.PENSATIVO to "p")
        assertEquals("n", ExpressionPicker.resolve(Expression.OUVINDO, art))
        assertEquals("p", ExpressionPicker.resolve(Expression.PREOCUPADO, art))
        assertEquals("p", ExpressionPicker.resolve(Expression.PENSATIVO, art))
        assertEquals("x", ExpressionPicker.resolve(Expression.FELIZ, mapOf(Expression.SURPRESO to "x")))
        assertNull(ExpressionPicker.resolve(Expression.FELIZ, emptyMap<Expression, String>()))
    }
}
