package com.joctaeng.jarvis.character

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.joctaeng.jarvis.core.contracts.CharacterRenderer
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import kotlin.math.abs

/**
 * Character renderer using PNG images from drawable resources.
 * Replaces the placeholder geometric character once art is available.
 * Falls back to placeholder if images are not found.
 */
class ImageCharacterRenderer(
    private val context: Context,
    private val characterId: String,
) : CharacterRenderer {
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

    fun getExpression(): String = CharacterExpressions.getExpression(emotion, state)

    fun getDrawableResourceId(): Int? {
        val resourceName = CharacterExpressions.drawableResourceName(characterId, getExpression())
        return try {
            context.resources.getIdentifier(resourceName, "drawable", context.packageName)
                .takeIf { it != 0 }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Displays character image from drawable, with fallback to placeholder if not found.
 * Once art is uploaded to docs/arte/ and moved to app/src/main/res/drawable/,
 * this renderer will display the character PNG instead of the placeholder circle.
 */
@Composable
fun ImageCharacterView(
    renderer: ImageCharacterRenderer,
    placeholderRenderer: ComposeCharacterRenderer,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val resourceId = remember(renderer.emotion, renderer.state) {
        renderer.getDrawableResourceId()
    }

    if (resourceId != null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = resourceId),
                contentDescription = "${renderer.characterId} ${renderer.getExpression()}",
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        CharacterView(placeholderRenderer, modifier, animate = animate)
    }
}
