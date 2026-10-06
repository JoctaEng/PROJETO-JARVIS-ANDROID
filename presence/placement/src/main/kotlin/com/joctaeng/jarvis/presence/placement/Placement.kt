package com.joctaeng.jarvis.presence.placement

import kotlin.math.roundToInt

data class Point(val x: Int, val y: Int)
data class Size(val width: Int, val height: Int)

/** Posição independente de resolução/rotação: 0..1 em cada eixo. */
data class NormalizedPosition(val x: Float, val y: Float)

/** Margens que o personagem não deve invadir (barra de status, navegação etc.). */
data class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/**
 * Posicionamento do MVP 1 (seção 6.4): encaixe magnético nas bordas laterais,
 * limites da tela e posição lembrada por app.
 */
object Placement {

    /** Mantém a janela inteira dentro da área útil. */
    fun clamp(pos: Point, window: Size, screen: Size, insets: Insets = Insets()): Point {
        val maxX = (screen.width - insets.right - window.width).coerceAtLeast(insets.left)
        val maxY = (screen.height - insets.bottom - window.height).coerceAtLeast(insets.top)
        return Point(pos.x.coerceIn(insets.left, maxX), pos.y.coerceIn(insets.top, maxY))
    }

    /** Ao soltar o personagem, ele "gruda" na borda lateral mais próxima. */
    fun snapToEdge(pos: Point, window: Size, screen: Size, insets: Insets = Insets()): Point {
        val clamped = clamp(pos, window, screen, insets)
        val centerX = clamped.x + window.width / 2
        val x = if (centerX < screen.width / 2) insets.left else screen.width - insets.right - window.width
        return clamp(Point(x, clamped.y), window, screen, insets)
    }

    fun normalize(pos: Point, window: Size, screen: Size): NormalizedPosition {
        val freeW = (screen.width - window.width).coerceAtLeast(1)
        val freeH = (screen.height - window.height).coerceAtLeast(1)
        return NormalizedPosition(pos.x.toFloat() / freeW, pos.y.toFloat() / freeH)
    }

    fun denormalize(pos: NormalizedPosition, window: Size, screen: Size): Point {
        val freeW = (screen.width - window.width).coerceAtLeast(0)
        val freeH = (screen.height - window.height).coerceAtLeast(0)
        return Point((pos.x.coerceIn(0f, 1f) * freeW).roundToInt(), (pos.y.coerceIn(0f, 1f) * freeH).roundToInt())
    }
}

/** Lembra onde o usuário prefere o personagem em cada app em primeiro plano. */
class PositionMemory(private val default: NormalizedPosition = NormalizedPosition(1f, 0.6f)) {
    private val byApp = mutableMapOf<String, NormalizedPosition>()

    fun remember(packageName: String, position: NormalizedPosition) {
        byApp[packageName] = position
    }

    fun recall(packageName: String?): NormalizedPosition = packageName?.let { byApp[it] } ?: default

    fun snapshot(): Map<String, NormalizedPosition> = byApp.toMap()
}

/** Apps em que o personagem se minimiza sozinho (vídeo, jogos) — configurável. */
class AutoMinimizeRule(private val packages: Set<String>) {
    fun shouldMinimize(foregroundPackage: String?): Boolean = foregroundPackage != null && foregroundPackage in packages
}
