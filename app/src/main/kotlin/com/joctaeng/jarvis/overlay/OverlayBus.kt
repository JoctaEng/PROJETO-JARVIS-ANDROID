package com.joctaeng.jarvis.overlay

import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartilhado (mesmo processo) entre overlay, sessão de toque e painel. */
object OverlayBus {
    val running = MutableStateFlow(false)
    val sessionActive = MutableStateFlow(false)
    val dashboardVisible = MutableStateFlow(false)

    /** Quadros desenhados no último segundo (PoC 0.5). */
    val fps = MutableStateFlow(0)

    /** Tempo entre o toque e o primeiro quadro já reagindo (meta < 100 ms). */
    val lastReactionMillis = MutableStateFlow<Long?>(null)
}
