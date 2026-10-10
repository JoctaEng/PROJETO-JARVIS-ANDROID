package com.joctaeng.jarvis.overlay

import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/** Estado compartilhado (mesmo processo) entre overlay, sessão de toque e painel. */
object OverlayBus {
    val running = MutableStateFlow(false)
    val sessionActive = MutableStateFlow(false)
    val dashboardVisible = MutableStateFlow(false)

    /** Estado que a conversa quer mostrar no personagem (pensando, ouvindo...). */
    val anim = MutableStateFlow(AnimState.IDLE)

    /** Expressão simulada vinda da resposta do cérebro. */
    val emotion = MutableStateFlow(Emotion.NEUTRAL)

    /**
     * "Agindo na tela": o Euno está lendo/tocando em outro app. Ele se encolhe para o canto superior esquerdo, a legenda
     * fica mínima e a resposta sai por áudio, para não ficar por cima do que precisa ler.
     */
    val acting = MutableStateFlow(false)

    /** Conversa por voz ligada (VoiceSession), mesmo sem a tela de conversa aberta. */
    val voiceSession = MutableStateFlow(false)

    /** A tela de conversa está na frente (então o balão de legenda não precisa aparecer). */
    val chatVisible = MutableStateFlow(false)

    /** O usuário está falando (microfone aberto). */
    val listening = MutableStateFlow(false)

    /** Quadros desenhados no último segundo (PoC 0.5). */
    val fps = MutableStateFlow(0)

    /** Tempo entre o toque e o primeiro quadro já reagindo (meta < 100 ms). */
    val lastReactionMillis = MutableStateFlow<Long?>(null)

    private val _dismissRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Pedido para o personagem se recolher (ex.: "tchau"). */
    val dismissRequests: SharedFlow<Unit> = _dismissRequests

    fun requestDismiss() {
        _dismissRequests.tryEmit(Unit)
    }

    private val _stopListeningRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Pedido para parar de ouvir ("para de ouvir", "encerrar"), sem recolher o personagem. */
    val stopListeningRequests: SharedFlow<Unit> = _stopListeningRequests

    fun requestStopListening() {
        _stopListeningRequests.tryEmit(Unit)
    }
}
