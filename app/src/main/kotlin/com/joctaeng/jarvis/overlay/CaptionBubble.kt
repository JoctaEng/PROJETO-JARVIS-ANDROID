package com.joctaeng.jarvis.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.chat.ChatActivity
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.tools.ConfirmationCard
import com.joctaeng.jarvis.ui.JarvisTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Balão de legenda por cima de qualquer app (janela de sobreposição, como o personagem). Aparece quando a conversa
 * por voz está ligada, o Euno está agindo na tela ou há um pedido de confirmação, e a tela de conversa não está na frente.
 * Mostra o que ele está ouvindo/dizendo, a confirmação e os botões Parar / Abrir / Fechar.
 */
class CaptionBubble<T>(private val host: T, private val windowManager: WindowManager) where T : Context, T : LifecycleOwner, T : SavedStateRegistryOwner {
    private val app = JarvisApp.from(host)
    private var view: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    fun bind() {
        host.lifecycleScope.launch {
            combine(OverlayBus.voiceSession, OverlayBus.acting, app.toolbox.pending, OverlayBus.chatVisible) { voice, acting, pending, chat ->
                (voice || acting || pending != null) && !chat
            }.collect { show -> if (show) show() else hide() }
        }
        host.lifecycleScope.launch { OverlayBus.acting.collect { place(it) } }
    }

    private fun dp(v: Int) = (v * host.resources.displayMetrics.density).roundToInt()

    /** Agindo: no canto superior esquerdo, logo abaixo do personagem. Senão: no pé da tela, sem cobrir o teclado. */
    private fun place(acting: Boolean) {
        val top = runCatching {
            windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).top
        }.getOrDefault(0)
        if (acting) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = dp(6)
            params.y = top + dp(66)
        } else {
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = dp(96)
        }
        view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    private fun show() {
        if (view != null) return
        place(OverlayBus.acting.value)
        val v = ComposeView(host).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setContent { JarvisTheme { Bubble() } }
        }
        runCatching { windowManager.addView(v, params) }.onSuccess { view = v }
            .onFailure { app.events.warn("legenda", "não consegui mostrar o balão: ${it.message}") }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun openChat() {
        runCatching {
            host.startActivity(Intent(host, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @androidx.compose.runtime.Composable
    private fun Bubble() {
        val partial by app.voiceSession.partial.collectAsState()
        val status by app.voiceSession.status.collectAsState()
        val entries by app.conversation.entries.collectAsState()
        val pending by app.toolbox.pending.collectAsState()
        val busy by app.conversation.busy.collectAsState()
        val speaking by app.voice.speaking.collectAsState()
        val acting by OverlayBus.acting.collectAsState()
        val voiceOn by app.voiceSession.active.collectAsState()
        val last = entries.lastOrNull { !it.note }
        Card(Modifier.widthIn(max = if (acting) 260.dp else 340.dp), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val line = when {
                    partial.isNotEmpty() -> partial
                    status.isNotEmpty() -> status
                    acting -> "Agindo na tela… (pode falar)"
                    busy && !speaking -> "Pensando…"
                    voiceOn -> "Pode falar"
                    else -> ""
                }
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium, fontStyle = if (partial.isNotEmpty()) FontStyle.Italic else FontStyle.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (last != null && last.role == Role.ASSISTANT && last.text.isNotBlank()) {
                    Text(last.text, style = MaterialTheme.typography.bodySmall, maxLines = if (acting) 2 else 4, overflow = TextOverflow.Ellipsis)
                }
                pending?.let { ConfirmationCard(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy || speaking || acting) TextButton(onClick = { app.conversation.cancel() }) { Text("Parar") }
                    TextButton(onClick = ::openChat) { Text("Abrir") }
                    TextButton(onClick = {
                        app.voiceSession.end()
                        app.conversation.cancel()
                        hide()
                    }) { Text("Fechar") }
                }
            }
        }
    }
}
