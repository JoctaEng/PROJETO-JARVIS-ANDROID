package com.joctaeng.jarvis.presence.expression

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion

/** Expressões desenhadas para cada personagem (arquivos em docs/arte/<personagem>/). */
enum class Expression {
    NEUTRO, FELIZ, PENSATIVO, FALANDO, OUVINDO, SURPRESO, PREOCUPADO, DORMINDO;

    /** Substituta quando o personagem ainda não tem esta arte; NEUTRO é a base de todas. */
    val fallback: Expression?
        get() = when (this) {
            NEUTRO -> null
            PREOCUPADO -> PENSATIVO
            else -> NEUTRO
        }
}

object ExpressionPicker {
    /** Boca acima deste nível mostra a arte "falando"; abaixo, a expressão de base. */
    const val MOUTH_OPEN_THRESHOLD = 0.45f

    fun pick(state: AnimState, emotion: Emotion, mouthLevel: Float = 0f): Expression = when (state) {
        AnimState.SLEEPING -> Expression.DORMINDO
        AnimState.SPEAKING -> if (mouthLevel > MOUTH_OPEN_THRESHOLD) Expression.FALANDO else fromEmotion(emotion)
        AnimState.LISTENING -> Expression.OUVINDO
        AnimState.THINKING, AnimState.CONFUSED -> Expression.PENSATIVO
        AnimState.HAPPY, AnimState.CELEBRATING, AnimState.WAKING -> Expression.FELIZ
        AnimState.SURPRISED -> Expression.SURPRESO
        AnimState.CONCERNED, AnimState.ERROR -> Expression.PREOCUPADO
        AnimState.IDLE, AnimState.DRAGGED, AnimState.SHY -> fromEmotion(emotion)
    }

    fun fromEmotion(emotion: Emotion): Expression = when (emotion) {
        Emotion.HAPPY, Emotion.CELEBRATING, Emotion.PLAYFUL -> Expression.FELIZ
        Emotion.THINKING, Emotion.CONFUSED -> Expression.PENSATIVO
        Emotion.SURPRISED -> Expression.SURPRESO
        Emotion.CONCERNED -> Expression.PREOCUPADO
        Emotion.SLEEPY -> Expression.DORMINDO
        Emotion.NEUTRAL -> Expression.NEUTRO
    }

    /** Primeira expressão disponível seguindo a cadeia de substitutas; null se não houver arte nenhuma. */
    fun <T> resolve(wanted: Expression, available: Map<Expression, T>): T? {
        var e: Expression? = wanted
        while (e != null) {
            available[e]?.let { return it }
            e = e.fallback
        }
        return available.values.firstOrNull()
    }
}
