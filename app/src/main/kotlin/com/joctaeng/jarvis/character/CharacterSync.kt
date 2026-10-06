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

    /** @param paused quando true (ex.: arrastando), o estado não é aplicado. */
    fun bind(
        scope: CoroutineScope,
        renderer: CharacterRenderer,
        speaking: StateFlow<Boolean>,
        paused: () -> Boolean = { false },
    ): Job = scope.launch {
        var mouth: Job? = null
        combine(OverlayBus.anim, OverlayBus.emotion, OverlayBus.listening, speaking) { _, emotion, _, isSpeaking ->
            emotion to isSpeaking
        }.collect { (emotion, isSpeaking) ->
            renderer.setEmotion(emotion, 0.8f)
            if (!paused()) renderer.play(currentState(isSpeaking))
            mouth?.cancel()
            renderer.setMouthOpen(0f)
            if (isSpeaking) {
                mouth = launch {
                    while (isActive) {
                        renderer.setMouthOpen(Random.nextFloat() * 0.8f + 0.2f)
                        delay(110)
                    }
                }
            }
        }
    }
}
