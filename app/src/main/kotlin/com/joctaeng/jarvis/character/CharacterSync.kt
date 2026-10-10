package com.joctaeng.jarvis.character

import com.joctaeng.jarvis.core.contracts.CharacterRenderer
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.overlay.OverlayBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import android.os.SystemClock
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.presence.expression.MouthPose
import kotlinx.coroutines.flow.flowOf

/**
 * Liga um renderer ao estado da conversa: falando > ouvindo > estado pedido pela
 * conversa; expressão vinda da resposta; boca mexendo enquanto a voz fala.
 */
object CharacterSync {

    fun currentState(speaking: Boolean): AnimState = when {
        speaking -> AnimState.SPEAKING
        OverlayBus.listening.value -> AnimState.LISTENING
        else -> OverlayBus.anim.value
    }

    /**
     * @param paused quando true (ex.: arrastando), o estado não é aplicado.
     * @param lip boca sincronizada com o áudio; sem atualização recente (ex.: motor que não informa as palavras),
     * volta à boca automática.
     * @param sentenceEmotion emoção da frase falada agora (a expressão acompanha o que ele está dizendo).
     */
    fun bind(
        scope: CoroutineScope,
        renderer: CharacterRenderer,
        speaking: StateFlow<Boolean>,
        lip: StateFlow<MouthPose>? = null,
        lipUpdatedAt: () -> Long = { 0L },
        sentenceEmotion: StateFlow<Emotion?>? = null,
        paused: () -> Boolean = { false },
    ): Job = scope.launch {
        var mouth: Job? = null
        combine(OverlayBus.anim, OverlayBus.emotion, OverlayBus.listening, speaking, sentenceEmotion ?: flowOf(null)) { _, emotion, _, isSpeaking, sentence ->
            Triple(emotion, isSpeaking, sentence)
        }.collect { (emotion, isSpeaking, sentence) ->
            renderer.setEmotion(if (isSpeaking && sentence != null) sentence else emotion, 0.8f)
            if (!paused()) renderer.play(currentState(isSpeaking))
            mouth?.cancel()
            renderer.setMouthOpen(0f)
            if (isSpeaking) {
                mouth = launch {
                    while (isActive) {
                        val fresh = lip != null && SystemClock.elapsedRealtime() - lipUpdatedAt() < 300
                        if (fresh) {
                            val p = lip!!.value
                            if (renderer is ComposeCharacterRenderer) renderer.setMouthShape(p.open, p.width, p.viseme) else renderer.setMouthOpen(p.open)
                            delay(33)
                        } else {
                            renderer.setMouthOpen(Random.nextFloat() * 0.8f + 0.2f)
                            delay(110)
                        }
                    }
                }
            }
        }
    }
}
