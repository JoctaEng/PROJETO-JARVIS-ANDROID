package com.joctaeng.jarvis.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.tools.ConfirmationCard
import com.joctaeng.jarvis.character.CharacterSync
import com.joctaeng.jarvis.character.CharacterView
import com.joctaeng.jarvis.character.ComposeCharacterRenderer
import com.joctaeng.jarvis.conversation.ChatEntry
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.overlay.OverlayBus
import com.joctaeng.jarvis.ui.JarvisTheme
import com.joctaeng.jarvis.ui.SettingsActivity
import com.joctaeng.jarvis.voice.SpeechListener
import kotlinx.coroutines.launch

/**
 * Conversa (seções 23 e 24 da especificação): abre por cima do app atual, na parte
 * de baixo da tela, sem virar um app de chatbot em tela cheia. Ao abrir pelo toque,
 * já começa a ouvir (modo voz); o teclado fica disponível para o modo chat.
 */
class ChatActivity : ComponentActivity() {
    private val app get() = JarvisApp.from(this)
    private val renderer = ComposeCharacterRenderer()
    private lateinit var listener: SpeechListener
    private var partial by mutableStateOf("")
    private var status by mutableStateOf("")
    private var voiceMode by mutableStateOf(false)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status = "Sem permissão de microfone: use o teclado."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listener = SpeechListener(this)
        OverlayBus.sessionActive.value = true
        CharacterSync.bind(lifecycleScope, renderer, app.voice.speaking)
        lifecycleScope.launch { app.settings.version.collect { renderer.applyProfile(app.settings.character) } }
        voiceMode = app.settings.listenOnOpen && intent.getBooleanExtra(EXTRA_FROM_TAP, false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.conversation.turnFinished.collect {
                    if (voiceMode && app.settings.continuousVoice) startListening()
                }
            }
        }
        setContent { JarvisTheme { ChatSheet() } }
        if (voiceMode) startListening()
    }

    override fun onStop() {
        stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        OverlayBus.sessionActive.value = false
        OverlayBus.listening.value = false
        super.onDestroy()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        app.voice.stop()
        partial = ""
        status = "Ouvindo…"
        OverlayBus.listening.value = true
        listener.start { event ->
            when (event) {
                is SpeechListener.Event.Partial -> partial = event.text
                is SpeechListener.Event.Level -> Unit
                is SpeechListener.Event.Final -> {
                    OverlayBus.listening.value = false
                    partial = ""
                    status = ""
                    app.conversation.send(event.text, speak = app.settings.speakReplies)
                }
                is SpeechListener.Event.Failed -> {
                    OverlayBus.listening.value = false
                    partial = ""
                    status = if (event.silent) "" else event.message
                    if (event.silent) voiceMode = false
                }
            }
        }
    }

    private fun stopListening() {
        listener.stop()
        OverlayBus.listening.value = false
        partial = ""
        if (status == "Ouvindo…") status = ""
    }

    @Composable
    private fun ChatSheet() {
        val entries by app.conversation.entries.collectAsState()
        val busy by app.conversation.busy.collectAsState()
        val listening by OverlayBus.listening.collectAsState()
        val speaking by app.voice.speaking.collectAsState()
        val pendingAction by app.toolbox.pending.collectAsState()
        var input by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        LaunchedEffect(entries.size, entries.lastOrNull()?.text?.length) {
            if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
        }

        // Toque fora do cartão fecha a conversa.
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Card(
                Modifier.fillMaxWidth().fillMaxHeight(0.62f).padding(8.dp).navigationBarsPadding().imePadding()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CharacterView(renderer, Modifier.size(52.dp))
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.settings.displayName, style = MaterialTheme.typography.titleMedium)
                            val line = when {
                                listening -> "Ouvindo…"
                                busy && !speaking -> "Pensando…"
                                speaking -> "Falando…"
                                status.isNotEmpty() -> status
                                else -> entries.lastOrNull { it.brain != null }?.brain ?: "Pronto"
                            }
                            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            startActivity(Intent(this@ChatActivity, SettingsActivity::class.java))
                        }) { Text("Ajustes") }
                        TextButton(onClick = ::finish) { Text("Fechar") }
                    }

                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    "Fale comigo ou escreva abaixo. Diga \"lembre que…\" para eu memorizar algo, ou \"esqueça isto\".",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(entries, key = { it.id }) { Bubble(it) }
                        if (partial.isNotEmpty()) {
                            item { Text(partial, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }

                    pendingAction?.let { ConfirmationCard(it) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Conversa por voz", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Switch(checked = voiceMode, onCheckedChange = {
                            voiceMode = it
                            if (it) startListening() else stopListening()
                        })
                        if (busy || speaking) {
                            TextButton(onClick = { app.conversation.cancel() }) { Text("Parar") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                if (listening) stopListening()
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Escreva aqui") },
                            maxLines = 4,
                        )
                        if (input.isNotBlank()) {
                            Button(onClick = {
                                app.conversation.send(input, speak = false)
                                input = ""
                            }, enabled = !busy) { Text("Enviar") }
                        } else {
                            FilledTonalButton(onClick = { if (listening) stopListening() else startListening() }, enabled = !busy) {
                                Text(if (listening) "Parar" else "Falar")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Bubble(entry: ChatEntry) {
        if (entry.note) {
            Text(
                entry.text, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            return
        }
        val mine = entry.role == Role.USER
        val bubble = Modifier.widthIn(max = 300.dp)
            .background(
                if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
        val textColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!mine && needsFormatting(entry.text)) {
                Box(bubble) {
                    FormattedText(entry.text, textColor, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
                }
            } else {
                Text(text = entry.text.ifEmpty { "…" }, modifier = bubble, color = textColor)
            }
            if (!mine && !entry.streaming && entry.text.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.brain?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { copyToClipboard(entry.text) }) { Text("Copiar", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Resposta do Euno", text))
    }

    companion object {
        const val EXTRA_FROM_TAP = "from_tap"
    }
}
