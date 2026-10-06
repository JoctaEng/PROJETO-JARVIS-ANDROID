package com.joctaeng.jarvis.mind.persona

import com.joctaeng.jarvis.core.model.Emotion

/**
 * O cérebro começa a resposta com uma etiqueta como `[feliz]` (seção 6.3). O
 * parser retira a etiqueta do texto visível e entrega a emoção ao personagem
 * assim que ela chega — antes do resto da resposta.
 */
object EmotionTag {
    private val names = mapOf(
        "neutro" to Emotion.NEUTRAL,
        "feliz" to Emotion.HAPPY,
        "pensativo" to Emotion.THINKING,
        "surpreso" to Emotion.SURPRISED,
        "preocupado" to Emotion.CONCERNED,
        "sonolento" to Emotion.SLEEPY,
        "comemorando" to Emotion.CELEBRATING,
        "confuso" to Emotion.CONFUSED,
        "brincalhão" to Emotion.PLAYFUL,
        "brincalhao" to Emotion.PLAYFUL,
    )

    val INSTRUCTION: String =
        "Comece SEMPRE a resposta com uma etiqueta da sua expressão, entre colchetes, escolhida entre: " +
            "[neutro], [feliz], [pensativo], [surpreso], [preocupado], [comemorando], [confuso], [brincalhão]. " +
            "Depois da etiqueta, escreva a resposta normalmente."

    fun emotionFor(name: String): Emotion? = names[name.trim().lowercase()]
}

/** Separa a etiqueta de emoção do texto em streaming. */
class StreamingEmotionParser {
    private val pending = StringBuilder()
    private var decided = false

    var emotion: Emotion? = null
        private set

    /** Recebe um trecho do cérebro e devolve o texto que já pode ser mostrado. */
    fun feed(chunk: String): String {
        if (decided) return chunk
        pending.append(chunk)
        val trimmed = pending.trimStart()
        if (trimmed.isEmpty()) return ""
        if (trimmed[0] != '[') return release(trimmed.toString())
        val close = trimmed.indexOf(']')
        if (close < 0) {
            // Etiquetas são curtas; se passou do limite, não é etiqueta.
            return if (trimmed.length > MAX_TAG) release(trimmed.toString()) else ""
        }
        val found = EmotionTag.emotionFor(trimmed.substring(1, close))
        return if (found != null) {
            emotion = found
            release(trimmed.substring(close + 1).trimStart())
        } else {
            release(trimmed.toString())
        }
    }

    /** Fim da resposta: devolve o que sobrou sem decisão. */
    fun finish(): String = if (decided) "" else release(pending.toString())

    private fun release(text: String): String {
        decided = true
        pending.clear()
        return text
    }

    private companion object {
        const val MAX_TAG = 20
    }
}
