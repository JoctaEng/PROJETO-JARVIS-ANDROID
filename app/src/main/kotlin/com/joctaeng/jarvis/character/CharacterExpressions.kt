package com.joctaeng.jarvis.character

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion

/**
 * Maps emotion/state combinations to expression filenames.
 * Art files are named as: character_<name>_<expression>.png
 * Available expressions: referencia, neutro, feliz, pensativo, falando, ouvindo, surpreso, preocupado, dormindo
 */
object CharacterExpressions {
    fun getExpression(emotion: Emotion, state: AnimState): String = when {
        state == AnimState.SLEEPING -> "dormindo"
        state == AnimState.SPEAKING -> "falando"
        state == AnimState.LISTENING && emotion != Emotion.SURPRISED -> "ouvindo"
        emotion == Emotion.HAPPY || emotion == Emotion.CELEBRATING -> "feliz"
        emotion == Emotion.THINKING -> "pensativo"
        emotion == Emotion.SURPRISED -> "surpreso"
        emotion == Emotion.CONCERNED -> "preocupado"
        else -> "neutro"
    }

    fun drawableResourceName(characterId: String, expression: String): String =
        "character_${characterId}_$expression"
}
