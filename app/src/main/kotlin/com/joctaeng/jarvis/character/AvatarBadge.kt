package com.joctaeng.jarvis.character

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.R
import com.joctaeng.jarvis.core.model.AnimState

/**
 * O personagem escolhido em qualquer tela (chat, tela inicial, sessão de toque): com o avatar 3D ligado, mostra o
 * rosto DO MESMO avatar 3D (miniatura), que balança enquanto fala; sem 3D, o personagem 2D. Antes, o chat mostrava
 * sempre o 2D, mesmo com um avatar 3D na tela (pedido 66).
 */
@Composable
fun AvatarBadge(renderer: ComposeCharacterRenderer, modifier: Modifier = Modifier, animate: Boolean = true) {
    val context = LocalContext.current
    val app = remember { JarvisApp.from(context) }
    val version by app.settings.version.collectAsState()
    if (!app.settings.avatar3d) {
        CharacterView(renderer, modifier, animate = animate)
        return
    }
    val custom = remember(version) {
        val f = Avatar3D.customThumb(context)
        if (Avatar3D.customFile(context).isFile && f.isFile) runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null
    }
    val painter: Painter = custom?.let { remember(it) { BitmapPainter(it) } } ?: painterResource(Avatar3D.thumbRes(app.settings.avatarModel))
    val talking = animate && (renderer.state == AnimState.SPEAKING || renderer.mouthLevel > 0.05f)
    val pulse = rememberInfiniteTransition(label = "avatar_badge")
    val bob by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(240), RepeatMode.Reverse), label = "bob")
    val breath by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "breath")
    Image(
        painter, contentDescription = app.settings.displayName,
        modifier = modifier.graphicsLayer {
            val k = if (talking) 1f + 0.035f * bob else if (animate) 1f + 0.012f * breath else 1f
            scaleX = k
            scaleY = k
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        },
    )
}
