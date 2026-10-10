package com.joctaeng.jarvis.system.resources

/**
 * Ritmo de gente ao agir na tela (pedido 65): esperar a tela parar de mudar antes de ler, rolar sem pressa e não
 * disparar gestos em rajada. Sem Android aqui, para testar.
 */
object HumanPace {
    /** Um gesto de rolar dura ~0,45 s (rápido demais vira "arremesso" e a lista voa). */
    const val SCROLL_GESTURE_MS = 450L

    /** Intervalo mínimo entre dois gestos seguidos (antes eram ~0,4 s, 7 rolagens em 4 s). */
    const val MIN_GAP_MS = 700L

    /** Toque com o dedo virtual: curto, como um toque de verdade. */
    const val TAP_MS = 60L

    /** Quanto ainda falta esperar antes do próximo gesto. */
    fun waitBeforeGesture(lastGestureAt: Long, now: Long): Long =
        if (lastGestureAt <= 0L) 0L else (MIN_GAP_MS - (now - lastGestureAt)).coerceAtLeast(0L)

    /** Trajeto do dedo para rolar/deslizar em [direction] numa tela [w]×[h]: x1, y1, x2, y2. */
    fun swipePath(direction: String, w: Float, h: Float): FloatArray = when (direction) {
        // "proximo": o dedo vai da direita para a esquerda (próxima página/semana).
        "proximo", "direita" -> floatArrayOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
        "anterior", "esquerda" -> floatArrayOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
        // "cima": ver o que está acima (o dedo desce).
        "cima" -> floatArrayOf(w * 0.5f, h * 0.38f, w * 0.5f, h * 0.70f)
        // "baixo": ver o que está abaixo (o dedo sobe), ~1/3 de tela por vez, para não pular itens.
        else -> floatArrayOf(w * 0.5f, h * 0.70f, w * 0.5f, h * 0.38f)
    }

    /** Ponto em porcentagem da tela (0–100, como o print descreve) → pixels, sempre dentro da tela. */
    fun pointFromPercent(xPct: Double, yPct: Double, w: Int, h: Int): Pair<Float, Float> {
        val x = (xPct.coerceIn(0.0, 100.0) / 100.0 * w).toFloat().coerceIn(1f, (w - 1).toFloat())
        val y = (yPct.coerceIn(0.0, 100.0) / 100.0 * h).toFloat().coerceIn(1f, (h - 1).toFloat())
        return x to y
    }
}

/**
 * A tela "assentou"? Recebe a assinatura do conteúdo (ex.: hash dos textos) de tempos em tempos e diz quando ela
 * ficou igual por [quietMs] (app carregado), respeitando um mínimo [minMs] e um máximo [maxMs] de espera.
 */
class ScreenSettle(private val quietMs: Long = 450L, private val minMs: Long = 250L, private val maxMs: Long = 3_500L) {
    private var startAt = -1L
    private var lastSig: Int? = null
    private var lastChangeAt = 0L

    /** true = pode ler/agir agora (assentou ou o tempo máximo acabou). */
    fun observe(signature: Int, now: Long): Boolean {
        if (startAt < 0) {
            startAt = now
            lastChangeAt = now
            lastSig = signature
            return false
        }
        if (signature != lastSig) {
            lastSig = signature
            lastChangeAt = now
        }
        val elapsed = now - startAt
        if (elapsed >= maxMs) return true
        return elapsed >= minMs && now - lastChangeAt >= quietMs && signature != EMPTY
    }

    /** Esperou até o máximo sem assentar? */
    fun timedOut(now: Long): Boolean = startAt >= 0 && now - startAt >= maxMs

    companion object {
        /** Assinatura de tela vazia (app ainda abrindo): nunca conta como assentada antes do máximo. */
        const val EMPTY = 0
    }
}
