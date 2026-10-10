package com.joctaeng.jarvis.presence.expression

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import java.util.Locale

/**
 * Tradução do estado do personagem para o avatar 3D (formato VRM): bocas do Rhubarb → expressões de fala do VRM
 * (aa, ih, ou, ee, oh), emoções → expressões do VRM (happy, sad, surprised, angry, relaxed) e estado → modo do avatar.
 * Gera o comando JavaScript que a página do avatar entende (window.Avatar).
 */
object VrmFace {
    /** Pesos de fala do VRM para uma boca. Lábios fechados (A) e repouso (X) = boca fechada. */
    fun mouth(viseme: Viseme, open: Float): Map<String, Float> {
        val base = if (viseme.open <= 0f) 0f else (open / viseme.open).coerceIn(0f, 1f)
        if (base <= 0.02f) return emptyMap()
        return when (viseme) {
            Viseme.D -> mapOf("aa" to base)
            Viseme.C -> mapOf("ee" to base * 0.9f, "aa" to base * 0.25f)
            Viseme.B -> mapOf("ih" to base * 0.75f)
            Viseme.E -> mapOf("oh" to base)
            Viseme.F -> mapOf("ou" to base)
            Viseme.H -> mapOf("aa" to base * 0.55f, "ee" to base * 0.3f)
            Viseme.G -> mapOf("ih" to base * 0.4f)
            Viseme.A, Viseme.X -> emptyMap()
        }
    }

    /** Expressão do VRM e intensidade para uma emoção (null = rosto neutro). */
    fun emotion(e: Emotion): Pair<String?, Float> = when (e) {
        Emotion.HAPPY -> "happy" to 0.75f
        Emotion.CELEBRATING -> "happy" to 1f
        Emotion.PLAYFUL -> "happy" to 0.55f
        Emotion.SURPRISED -> "surprised" to 0.85f
        Emotion.CONCERNED -> "sad" to 0.6f
        Emotion.CONFUSED -> "sad" to 0.3f
        Emotion.THINKING -> "relaxed" to 0.3f
        Emotion.SLEEPY -> "relaxed" to 0.8f
        Emotion.NEUTRAL -> null to 0f
    }

    fun mode(state: AnimState): String = when (state) {
        AnimState.SPEAKING -> "speaking"
        AnimState.LISTENING -> "listening"
        AnimState.THINKING, AnimState.CONFUSED -> "thinking"
        AnimState.SLEEPING -> "sleeping"
        else -> "idle"
    }

    /** Comando para a página do avatar (sem espaços extras; números com ponto). */
    fun script(state: AnimState, emotion: Emotion, viseme: Viseme, open: Float, lookX: Float, lookY: Float): String {
        val m = if (state == AnimState.SPEAKING) mouth(viseme, open) else emptyMap()
        val mouthJs = m.entries.joinToString(",", "{", "}") { "${it.key}:${num(it.value)}" }
        val (name, weight) = emotion(emotion)
        val emo = if (name == null) "null" else "'$name'"
        return "Avatar.setMode('${mode(state)}');Avatar.setMouth($mouthJs);Avatar.setEmotion($emo,${num(weight)});" +
            "Avatar.lookAt(${num(lookX)},${num(lookY)});"
    }

    private fun num(v: Float) = String.format(Locale.US, "%.2f", v)
}
