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
    LaunchedEffect(animate, maxFps) {
        if (!animate) return@LaunchedEffect
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
        // 1. Portal dimensional sob os pés ou orbe dimensional flutuante
        drawDimensionalPortal(renderer, time, transition)
        // 2. Personagem em 3D emergindo/em pé
        if (transition > 0.005f) {
            if (art != null) drawArt(renderer, art, time, blink = animate, transition = transition)
            else drawCharacter(renderer, time, transition = transition)
        }
    }
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

private fun lerpFloat(a: Float, b: Float, f: Float): Float = a + (b - a) * f

/**
 * Portal Dimensional holográfico sob os pés dos personagens.
 *
 * - Quando ativo (transition = 1): base/pedestal dimensional 3D sob os pés com anéis holográficos.
 * - Quando recolhido (transition = 0): o personagem volta para a outra dimensão e apenas o círculo
 *   dimensional flutua suavemente na tela do celular com pulso cósmico e anéis rotativos.
 */
private fun DrawScope.drawDimensionalPortal(r: ComposeCharacterRenderer, t: Float, transition: Float) {
    val side = size.minDimension

    // Interpolação de posição:
    // transition = 0 -> portal centralizado na janela (size.height / 2f)
    // transition = 1 -> portal na base dos pés (size.height * 0.90f)
    val centerY = lerpFloat(size.height / 2f, size.height * 0.90f, transition)
    val centerX = size.width / 2f
    val center = Offset(centerX, centerY)

    // Geometria: de círculo flutuante (minimizado) para elipse plana 3D nos pés (expandido)
    val radiusX = lerpFloat(side * 0.32f, side * 0.42f, transition)
    val radiusY = lerpFloat(side * 0.32f, side * 0.13f, transition)
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
    val coreAlpha = lerpFloat(0.42f + 0.28f * pulse, 0.22f, transition)
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
                color = cyanHolo.copy(alpha = 0.85f),
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
                color = purpleCore.copy(alpha = 0.75f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = center - Offset(innerRadiusX, innerRadiusX),
                size = Size(innerRadiusX * 2f, innerRadiusX * 2f),
                style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round),
            )
        }
    }

    // 4. Quando minimizado (transition < 0.5f), desenha o núcleo / orbe quântico flutuante no centro
    if (transition < 0.5f) {
        val orbAlpha = (1f - transition * 2f).coerceIn(0f, 1f)
        val coreRadius = side * (0.07f + 0.018f * pulse)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, cyanHolo, Color.Transparent),
                center = center,
                radius = coreRadius * 1.6f,
            ),
            radius = coreRadius,
            center = center,
            alpha = orbAlpha,
        )
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
) {
    var wanted = ExpressionPicker.pick(r.state, r.emotion, r.mouthLevel)
    if (blink && wanted == Expression.NEUTRO && Expression.DORMINDO in art && isBlinking(t)) wanted = Expression.DORMINDO
    val image = ExpressionPicker.resolve(wanted, art) ?: return

    val side = size.minDimension
    if (transition >= 0.98f) {
        drawListeningRing(r, t, Offset(size.width / 2f, size.height / 2f), side * 0.42f)
    }
    val breath = 1f + 0.012f * breathing(r.state, t)
    val lift = bobbing(r.state, t) * side * transition
    val pivot = Offset(size.width / 2f, size.height * 0.90f)

    // O personagem submerge / emerge do portal dimensional sob os pés
    val sink = (1f - transition) * (side * 0.48f)
    val currentScale = breath * (0.15f + 0.85f * transition)
    val currentAlpha = (transition * 1.25f).coerceIn(0f, 1f)

    withTransform({
        translate(top = -lift + sink)
        scale(currentScale, currentScale, pivot)
    }) {
        drawImage(
            image,
            dstOffset = IntOffset(((size.width - side) / 2f).toInt(), ((size.height - side) / 2f).toInt()),
            dstSize = IntSize(side.toInt(), side.toInt()),
            alpha = currentAlpha,
            filterQuality = FilterQuality.Medium,
        )
    }
}

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
