package com.joctaeng.jarvis.overlay

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartilhado (mesmo processo) entre overlay, sessão de toque e painel. */
object OverlayBus {
    val running = MutableStateFlow(false)
    val sessionActive = MutableStateFlow(false)
    val dashboardVisible = MutableStateFlow(false)

    /** Estado que a conversa quer mostrar no personagem (pensando, ouvindo...). */
    val anim = MutableStateFlow(AnimState.IDLE)

    /** Expressão simulada vinda da resposta do cérebro. */
    val emotion = MutableStateFlow(Emotion.NEUTRAL)

    /** O usuário está falando (microfone aberto). */
    val listening = MutableStateFlow(false)

    /** Quadros desenhados no último segundo (PoC 0.5). */
    val fps = MutableStateFlow(0)

    /** Tempo entre o toque e o primeiro quadro já reagindo (meta < 100 ms). */
    val lastReactionMillis = MutableStateFlow<Long?>(null)
}
