package com.joctaeng.jarvis.character

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.joctaeng.jarvis.core.contracts.CharacterRenderer
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.mind.persona.CharacterProfile
import com.joctaeng.jarvis.presence.expression.Expression
import com.joctaeng.jarvis.presence.expression.ExpressionPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Personagem em Compose: usa a arte 2D do personagem quando existe ([CharacterArt])
 * e, sem arte, o boneco provisório colorido desenhado em código.
 */
class ComposeCharacterRenderer : CharacterRenderer {
    var emotion by mutableStateOf(Emotion.NEUTRAL)
        private set
    var intensity by mutableFloatStateOf(0.5f)
        private set
    var state by mutableStateOf(AnimState.SLEEPING)
        private set
    var look by mutableStateOf(Offset.Zero)
        private set
    var mouthLevel by mutableFloatStateOf(0f)
        private set

    /** Se o personagem está recolhido na outra dimensão (ficando apenas o portal dimensional flutuante). */
    var isMinimizedToPortal by mutableStateOf(false)
        private set

    fun dismissToDimension() {
        isMinimizedToPortal = true
    }

    fun emergeFromDimension() {
        isMinimizedToPortal = false
    }

    fun toggleDimension() {
        isMinimizedToPortal = !isMinimizedToPortal
    }

    /** Aproximou-se do usuário (toque, ouvindo, falando): cresce um pouco e olha para ele. */
    var engaged by mutableStateOf(false)
        private set

    fun setEngaged(value: Boolean) {
        engaged = value
        if (value) look = Offset.Zero
    }

    /** Cor do personagem escolhido: corpo do boneco provisório e anel de "ouvindo". */
    var bodyColor by mutableStateOf(BodyBase)
        private set
    var characterId by mutableStateOf("")
        private set

    fun applyProfile(profile: CharacterProfile) {
        bodyColor = Color(profile.color.toInt())
        characterId = profile.id
    }

    override fun setEmotion(emotion: Emotion, intensity: Float) {
        this.emotion = emotion
        this.intensity = intensity.coerceIn(0f, 1f)
    }

    override fun play(state: AnimState) {
        this.state = state
    }

    override fun lookAt(x: Float, y: Float) {
        look = Offset(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
    }

    override fun setMouthOpen(level: Float) {
        mouthLevel = level.coerceIn(0f, 1f)
    }
}

/**
 * @param maxFps teto de quadros por segundo da animação (meta da PoC 0.5: ≤ 30).
 * @param animate false congela a animação (0 fps) — usado quando minimizado.
 * @param onFrame chamado a cada quadro desenhado (medição de fps).
 */
@Composable
fun CharacterView(
    renderer: ComposeCharacterRenderer,
    modifier: Modifier = Modifier,
    maxFps: Int = 30,
    animate: Boolean = true,
    onFrame: (() -> Unit)? = null,
) {
    var time by remember { mutableFloatStateOf(0f) }
    val transition by animateFloatAsState(
        targetValue = if (renderer.isMinimizedToPortal) 0f else 1f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "portal_transition",
    )
    val engage by animateFloatAsState(
        targetValue = if (renderer.engaged || renderer.state in ENGAGED_STATES) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "engage",
    )
    val fade = remember { ArtFade() }
    // Recolhido e já assentado: 0 quadros por segundo (só o risquinho estático).
    val hiddenSettled = renderer.isMinimizedToPortal && transition < 0.005f
    LaunchedEffect(animate, maxFps, hiddenSettled) {
        if (!animate || hiddenSettled) return@LaunchedEffect
        val start = System.nanoTime()
        val frameMillis = (1000L / maxFps.coerceIn(1, 60))
        while (isActive) {
            time = (System.nanoTime() - start) / 1e9f
            delay(frameMillis)
        }
    }
    val resources = LocalContext.current.resources
    val art = remember(renderer.characterId) { CharacterArt.load(resources, renderer.characterId) }
    Canvas(modifier) {
        onFrame?.invoke()
        if (transition < 0.005f) {
            drawRisquinho()
            return@Canvas
        }
        // 1. Base dimensional sob os pés (some junto com o personagem)
        drawDimensionalPortal(renderer, time, transition)
        // 2. Personagem emergindo/em pé
        if (art != null) drawArt(renderer, art, time, blink = animate, transition = transition, engage = engage, fade = fade, aligned = CharacterArt.isAligned(renderer.characterId))
        else drawCharacter(renderer, time, transition = transition)
    }
}

private val ENGAGED_STATES = setOf(AnimState.LISTENING, AnimState.SPEAKING, AnimState.THINKING, AnimState.WAKING)

/** Memória do desenho para fundir (crossfade) a troca de expressão. */
private class ArtFade {
    var current: ImageBitmap? = null
    var previous: ImageBitmap? = null
    var changedAt = 0f
}

/** Estado recolhido: apenas um risquinho discreto, sem animação. */
private fun DrawScope.drawRisquinho() {
    val w = size.width * 0.72f
    val h = (size.height * 0.2f).coerceAtLeast(3f)
    val topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f)
    val radius = CornerRadius(h / 2f, h / 2f)
    drawRoundRect(Color(0xFF00E5FF).copy(alpha = 0.18f), topLeft - Offset(h, h), Size(w + 2 * h, h * 3f), CornerRadius(h * 1.5f, h * 1.5f))
    drawRoundRect(Color(0xFF00E5FF).copy(alpha = 0.7f), topLeft, Size(w, h), radius)
}

private val BodyBase = Color(0xFF5B8DEF)
private val BodyError = Color(0xFFE57373)
private val Ink = Color(0xFF1E2340)
private val Cheek = Color(0x66FF8FA3)

private fun breathing(state: AnimState, t: Float): Float {
    val period = if (state == AnimState.SLEEPING) 4.5f else 3.2f
    return sin(2f * PI.toFloat() * t / period)
}

private fun bobbing(state: AnimState, t: Float): Float = when (state) {
    AnimState.LISTENING -> 0.03f * sin(2f * PI.toFloat() * t / 0.9f)
    AnimState.CELEBRATING, AnimState.WAKING -> 0.06f * abs(sin(2f * PI.toFloat() * t / 0.5f))
    else -> 0f
}

private fun DrawScope.drawListeningRing(r: ComposeCharacterRenderer, t: Float, center: Offset, radius: Float) {
    if (r.state != AnimState.LISTENING) return
    val pulse = (t % 1.2f) / 1.2f
    drawCircle(
        color = r.bodyColor.copy(alpha = 0.35f * (1f - pulse)),
        radius = radius * (1.02f + 0.18f * pulse),
        center = center,
        style = Stroke(width = radius * 0.06f),
    )
}

/** Base dimensional holográfica sob os pés; some por completo ao recolher (fica só o risquinho). */
private fun DrawScope.drawDimensionalPortal(r: ComposeCharacterRenderer, t: Float, transition: Float) {
    val side = size.minDimension

    val centerY = size.height * 0.90f
    val centerX = size.width / 2f
    val center = Offset(centerX, centerY)

    // Geometria: de círculo flutuante (minimizado) para elipse plana 3D nos pés (expandido)
    val radiusX = side * 0.42f
    val radiusY = side * 0.13f
    val scaleY = (radiusY / radiusX).coerceAtLeast(0.05f)

    val cyanHolo = Color(0xFF00E5FF)
    val purpleCore = Color(0xFF7C4DFF)
    val pulse = (sin(t * 3.2f) + 1f) / 2f

    // Sombra de contato nos pés quando o personagem está na dimensão física
    if (transition > 0.25f) {
        val shadowAlpha = 0.35f * transition
        drawOval(
            color = Color.Black.copy(alpha = shadowAlpha),
            topLeft = center - Offset(radiusX * 0.65f, radiusY * 0.5f),
            size = Size(radiusX * 1.3f, radiusY),
        )
    }

    // 1. Campo de energia e vórtice interno
    val coreAlpha = 0.22f * transition
    withTransform({
        scale(1f, scaleY, center)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    cyanHolo.copy(alpha = coreAlpha),
                    purpleCore.copy(alpha = coreAlpha * 0.7f),
                    Color.Transparent,
                ),
                center = center,
                radius = radiusX,
            ),
            radius = radiusX,
            center = center,
        )
    }

    // 2. Anel de contenção exterior giratório holográfico
    val strokeWidth = side * 0.024f
    val ringAngle = (t * 45f) % 360f

    withTransform({
        scale(1f, scaleY, center)
        rotate(ringAngle, center)
    }) {
        for (i in 0 until 4) {
            val startAngle = i * 90f + 10f
            drawArc(
                color = cyanHolo.copy(alpha = 0.85f * transition),
                startAngle = startAngle,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = center - Offset(radiusX, radiusX),
                size = Size(radiusX * 2f, radiusX * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }

    // 3. Anel de contenção interior em contra-rotação
    val innerRadiusX = radiusX * 0.68f
    withTransform({
        scale(1f, scaleY, center)
        rotate(-ringAngle * 1.3f, center)
    }) {
        for (i in 0 until 3) {
            val startAngle = i * 120f + 15f
            drawArc(
                color = purpleCore.copy(alpha = 0.75f * transition),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = center - Offset(innerRadiusX, innerRadiusX),
                size = Size(innerRadiusX * 2f, innerRadiusX * 2f),
                style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round),
            )
        }
    }
}

/** Piscada: troca rápida para a arte de olhos fechados enquanto está em repouso. */
private fun isBlinking(t: Float): Boolean = (t + 2f) % 4.2f < 0.14f

private fun DrawScope.drawArt(
    r: ComposeCharacterRenderer,
    art: Map<Expression, ImageBitmap>,
    t: Float,
    blink: Boolean,
    transition: Float,
    engage: Float,
    fade: ArtFade,
    aligned: Boolean,
) {
    var wanted = ExpressionPicker.pick(r.state, r.emotion, r.mouthLevel)
    if (blink && wanted == Expression.NEUTRO && Expression.DORMINDO in art && isBlinking(t)) wanted = Expression.DORMINDO
    val image = ExpressionPicker.resolve(wanted, art) ?: return

    // Troca de expressão com fusão curta (só para arte alinhada; em arte desalinhada fundiria "fantasmas").
    if (fade.changedAt > t) fade.changedAt = t
    if (image !== fade.current) {
        fade.previous = fade.current
        fade.current = image
        fade.changedAt = t
    }
    val f = ((t - fade.changedAt) / FADE_SECONDS).coerceIn(0f, 1f)

    val side = size.minDimension
    if (transition >= 0.98f) {
        drawListeningRing(r, t, Offset(size.width / 2f, size.height / 2f), side * 0.42f)
    }
    val breath = 1f + 0.012f * breathing(r.state, t)
    val lift = bobbing(r.state, t) * side * transition
    val pivot = Offset(size.width / 2f, size.height * 0.90f)

    // O personagem submerge / emerge da base dimensional sob os pés
    val sink = (1f - transition) * (side * 0.48f)
    // Em repouso fica um pouco menor; ao se engajar "chega mais perto" (cresce) e olha para o usuário.
    val closeness = 0.90f + 0.10f * engage
    val currentScale = breath * closeness * (0.15f + 0.85f * transition)
    val currentAlpha = (transition * 1.25f).coerceIn(0f, 1f)
    // Balanço lento, independente da boca, e leve deslocamento para onde olha (paralaxe).
    val sway = 0.8f * sin(2f * PI.toFloat() * t / 5.2f) * (1f - 0.6f * engage)
    val shiftX = r.look.x * side * 0.012f

    withTransform({
        translate(left = shiftX, top = -lift + sink)
        rotate(sway, pivot)
        scale(currentScale, currentScale, pivot)
    }) {
        val dst = IntOffset(((size.width - side) / 2f).toInt(), ((size.height - side) / 2f).toInt())
        val dstSize = IntSize(side.toInt(), side.toInt())
        val previous = fade.previous
        if (aligned && f < 1f && previous != null) {
            drawImage(previous, dstOffset = dst, dstSize = dstSize, alpha = currentAlpha, filterQuality = FilterQuality.Medium)
            drawImage(image, dstOffset = dst, dstSize = dstSize, alpha = currentAlpha * f, filterQuality = FilterQuality.Medium)
        } else {
            drawImage(image, dstOffset = dst, dstSize = dstSize, alpha = currentAlpha, filterQuality = FilterQuality.Medium)
        }
    }
}

private const val FADE_SECONDS = 0.12f

private fun DrawScope.drawCharacter(r: ComposeCharacterRenderer, t: Float, transition: Float) {
    val side = size.minDimension
    val pivot = Offset(size.width / 2f, size.height * 0.90f)
    val sink = (1f - transition) * (side * 0.48f)
    val currentScale = (0.15f + 0.85f * transition)

    withTransform({
        translate(top = sink)
        scale(currentScale, currentScale, pivot)
    }) {
        val state = r.state
        val sleeping = state == AnimState.SLEEPING
        val breath = 1f + 0.025f * breathing(state, t)
        val bob = bobbing(state, t)
        val radius = size.minDimension * 0.40f * breath
        val center = Offset(size.width / 2f, size.height / 2f - bob * size.minDimension)

        if (transition >= 0.98f) {
            drawListeningRing(r, t, center, radius)
        }

        val base = if (state == AnimState.ERROR) BodyError else r.bodyColor
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(lerp(base, Color.White, 0.45f), base),
                center = center + Offset(-radius * 0.3f, -radius * 0.35f),
                radius = radius * 1.4f,
            ),
            radius = radius,
            center = center,
            alpha = (transition * 1.25f).coerceIn(0f, 1f),
        )

        // Olhos.
        val eyeDx = radius * 0.36f
        val eyeY = center.y - radius * 0.12f
        val eyeW = radius * 0.30f
        val eyeH = radius * 0.36f
        val blinkPhase = t % 4.2f
        val blink = if (blinkPhase < 0.14f) abs(blinkPhase - 0.07f) / 0.07f else 1f
        val look = when (state) {
            AnimState.THINKING -> Offset(0.5f, -0.7f)
            else -> r.look
        }
        for (side in listOf(-1f, 1f)) {
            val eyeCenter = Offset(center.x + side * eyeDx, eyeY)
            if (sleeping) {
                drawArc(
                    color = Ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                    topLeft = eyeCenter - Offset(eyeW / 2f, eyeH / 4f), size = Size(eyeW, eyeH / 2f),
                    style = Stroke(width = radius * 0.05f, cap = StrokeCap.Round),
                )
                continue
            }
            val open = blink * if (r.emotion == Emotion.SURPRISED) 1.15f else 1f
            val h = (eyeH * open).coerceAtLeast(radius * 0.03f)
            drawOval(Color.White, topLeft = eyeCenter - Offset(eyeW / 2f, h / 2f), size = Size(eyeW, h))
            if (open > 0.3f) {
                val pupil = eyeW * 0.28f
                val pc = eyeCenter + Offset(look.x * eyeW * 0.22f, look.y * h * 0.22f + h * 0.05f)
                drawCircle(Ink, radius = pupil, center = pc)
                drawCircle(Color.White, radius = pupil * 0.35f, center = pc + Offset(-pupil * 0.35f, -pupil * 0.35f))
            }
        }

        // Bochechas.
        for (side in listOf(-1f, 1f)) {
            drawOval(
                Cheek,
                topLeft = Offset(center.x + side * radius * 0.58f - radius * 0.12f, center.y + radius * 0.12f),
                size = Size(radius * 0.24f, radius * 0.14f),
            )
        }

        drawMouth(r, state, center, radius)
    }
}

private fun DrawScope.drawMouth(r: ComposeCharacterRenderer, state: AnimState, center: Offset, radius: Float) {
    val mouthCenter = Offset(center.x, center.y + radius * 0.38f)
    val stroke = Stroke(width = radius * 0.06f, cap = StrokeCap.Round)
    val w = radius * 0.44f
    when {
        state == AnimState.SLEEPING -> drawLine(
            Ink, mouthCenter - Offset(w * 0.2f, 0f), mouthCenter + Offset(w * 0.2f, 0f),
            strokeWidth = stroke.width, cap = StrokeCap.Round,
        )
        state == AnimState.SPEAKING -> {
            val h = radius * (0.06f + 0.22f * r.mouthLevel)
            drawOval(Ink, topLeft = mouthCenter - Offset(w * 0.3f, h / 2f), size = Size(w * 0.6f, h))
        }
        r.emotion == Emotion.SURPRISED || state == AnimState.SURPRISED ->
            drawCircle(Ink, radius = radius * 0.09f, center = mouthCenter)
        r.emotion == Emotion.CONCERNED || state == AnimState.CONCERNED || state == AnimState.ERROR -> drawArc(
            Ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = mouthCenter - Offset(w / 2f, -radius * 0.02f), size = Size(w, radius * 0.25f), style = stroke,
        )
        r.emotion == Emotion.HAPPY || r.emotion == Emotion.CELEBRATING ||
            state == AnimState.HAPPY || state == AnimState.CELEBRATING || state == AnimState.WAKING -> drawArc(
            Ink, startAngle = 0f, sweepAngle = 180f, useCenter = true,
            topLeft = mouthCenter - Offset(w / 2f, radius * 0.12f), size = Size(w, radius * 0.28f),
        )
        else -> drawArc(
            Ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = mouthCenter - Offset(w * 0.35f, radius * 0.12f), size = Size(w * 0.7f, radius * 0.2f), style = stroke,
        )
    }
}
