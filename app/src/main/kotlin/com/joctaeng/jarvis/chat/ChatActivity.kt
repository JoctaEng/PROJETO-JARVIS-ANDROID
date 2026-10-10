package com.joctaeng.jarvis.chat

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch

/**
 * Conversa (seções 23 e 24 da especificação): abre por cima do app atual, na parte
 * de baixo da tela, sem virar um app de chatbot em tela cheia. Ao abrir pelo toque,
 * já começa a ouvir (modo voz); o teclado fica disponível para o modo chat.
 */
class ChatActivity : ComponentActivity() {
    private val app get() = JarvisApp.from(this)
    private val renderer = ComposeCharacterRenderer()
    private val session get() = app.voiceSession
    private var partial by mutableStateOf("")
    private var status by mutableStateOf("")

    /** Conversa por voz: mora na VoiceSession (continua ouvindo mesmo quando esta tela sai da frente). */
    private var voiceMode: Boolean
        get() = session.active.value
        set(on) { if (on) session.begin() else session.end() }

    /** Legenda: janela pequena no pé da tela, sem bloquear o app de trás. "Expandir" volta ao chat completo. */
    private var captionOnly by mutableStateOf(false)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening(beginSession = true) else status = "Sem permissão de microfone: use o teclado."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.chatVisible = true
        lifecycleScope.launch { session.partial.collect { partial = it } }
        lifecycleScope.launch { session.status.collect { status = it } }
        app.conversation.preloadLocalModel()
        OverlayBus.sessionActive.value = true
        CharacterSync.bind(lifecycleScope, renderer, app.voice.speaking, app.voice.lip.pose, { app.voice.lip.updatedAt }, app.voice.sentenceEmotion)
        lifecycleScope.launch { app.settings.version.collect { renderer.applyProfile(app.settings.character) } }
        val startVoice = app.settings.listenOnOpen && intent.getBooleanExtra(EXTRA_FROM_TAP, false)
        setCaption(app.settings.captionMode && intent.getBooleanExtra(EXTRA_FROM_TAP, false))

        // "Tchau": a sessão de voz encerra sozinha; aqui só fecha a janela (o personagem se recolhe).
        lifecycleScope.launch { OverlayBus.dismissRequests.collect { finish() } }
        setContent { JarvisTheme { ChatSheet() } }
        val wakeText = intent.getStringExtra(EXTRA_WAKE_TEXT).orEmpty()
        if (wakeText.isNotBlank()) {
            session.begin()
            session.stopListening() // responde primeiro; volta a ouvir ao fim da resposta
            app.conversation.send(wakeText, speak = true)
        } else if (startVoice && !session.active.value) {
            startListening(beginSession = true)
        }
    }

    override fun onStart() {
        super.onStart()
        session.chatVisible = true
    }

    override fun onStop() {
        // Com a conversa por voz ligada, continua ouvindo mesmo com outro app na frente (antes parava aqui).
        session.chatVisible = false
        if (!session.active.value) session.stopListening()
        super.onStop()
    }

    /** Fechar a conversa pelo botão encerra também a conversa por voz. */
    private fun closeChat() {
        session.end()
        finish()
    }

    override fun onDestroy() {
        OverlayBus.sessionActive.value = false
        if (!session.active.value) OverlayBus.listening.value = false
        super.onDestroy()
    }

    private fun startListening(beginSession: Boolean = false) {
        if (!session.micGranted()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (beginSession) session.begin() else session.startListening()
    }

    private fun stopListening() = session.stopListening()

    private fun setCaption(on: Boolean) {
        captionOnly = on
        val w = window
        if (on) {
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(android.view.Gravity.BOTTOM)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } else {
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private var wasFullBeforeActing = false

    /** "Agindo na tela": a janela do chat vira um chip pequeno no canto superior esquerdo, sem cobrir o app lido. */
    private fun applyActing(on: Boolean) {
        val w = window
        if (on) {
            wasFullBeforeActing = !captionOnly
            captionOnly = true
            w.setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } else {
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setCaption(!wasFullBeforeActing)
        }
    }

    @Composable
    private fun ChatSheet() {
        val acting by OverlayBus.acting.collectAsState()
        androidx.compose.runtime.LaunchedEffect(acting) { applyActing(acting) }
        if (acting) ActingChip() else if (captionOnly) CaptionBar() else FullChat()
    }

    /** Legenda mínima enquanto o Euno age na tela: uma linha só, no canto superior esquerdo. */
    @Composable
    private fun ActingChip() {
        val pendingAction by app.toolbox.pending.collectAsState()
        Column(Modifier.padding(start = 64.dp, top = 6.dp, end = 8.dp).statusBarsPadding()) {
            Card(shape = RoundedCornerShape(14.dp)) {
                Text("Agindo na tela…", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
            pendingAction?.let { ConfirmationCard(it, Modifier.padding(top = 6.dp).widthIn(max = 320.dp)) }
        }
    }

    @Composable
    private fun CaptionBar() {
        val entries by app.conversation.entries.collectAsState()
        val busy by app.conversation.busy.collectAsState()
        val listening by OverlayBus.listening.collectAsState()
        val speaking by app.voice.speaking.collectAsState()
        val last = entries.lastOrNull { !it.note }
        val pendingAction by app.toolbox.pending.collectAsState()
        val line = when {
            listening -> "Ouvindo…"
            busy && !speaking -> "Pensando…"
            speaking -> "Falando…"
            status.isNotEmpty() -> status
            else -> ""
        }
        Card(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(app.settings.displayName, line).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (busy || speaking) TextButton(onClick = { app.conversation.cancel() }) { Text("Parar") }
                    TextButton(onClick = { setCaption(false) }) { Text("Expandir") }
                    TextButton(onClick = ::closeChat) { Text("Fechar") }
                }
                pendingAction?.let { ConfirmationCard(it) }
                if (partial.isNotEmpty()) {
                    Text(partial, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (last != null) {
                    Text(
                        (if (last.role == Role.USER) "Você: " else "") + last.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun FullChat() {
        val entries by app.conversation.entries.collectAsState()
        val busy by app.conversation.busy.collectAsState()
        val listening by OverlayBus.listening.collectAsState()
        val speaking by app.voice.speaking.collectAsState()
        val pendingAction by app.toolbox.pending.collectAsState()
        var input by remember { mutableStateOf("") }
        var showNew by remember { mutableStateOf(false) }
        var summarizing by remember { mutableStateOf(false) }
        var loadSummaries by remember { mutableStateOf(false) }
        // Com o teclado aberto o cartão ocupa o espaço que sobra (antes ficava uma barra fina só com o campo de texto).
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
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
                Modifier.fillMaxWidth().then(if (keyboardOpen) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.62f))
                    .padding(8.dp).then(if (keyboardOpen) Modifier.statusBarsPadding() else Modifier).navigationBarsPadding().imePadding()
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
                        TextButton(onClick = { showNew = true }, enabled = !summarizing) { Text("Nova") }
                        TextButton(onClick = {
                            startActivity(Intent(this@ChatActivity, SettingsActivity::class.java))
                        }) { Text("Ajustes") }
                        TextButton(onClick = ::closeChat) { Text("Fechar") }
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
                    if (!keyboardOpen) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Conversa por voz", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            val voiceOn by session.active.collectAsState()
                            Switch(checked = voiceOn, onCheckedChange = { voiceMode = it })
                            if (busy || speaking) {
                                TextButton(onClick = { app.conversation.cancel() }) { Text("Parar") }
                            }
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
                            }) { Text(if (busy) "Enviar (fila)" else "Enviar") }
                        } else {
                            FilledTonalButton(onClick = {
                                app.events.info("escuta", "botão ${if (listening) "Parar" else "Falar"} tocado")
                                if (listening) stopListening() else startListening()
                            }, enabled = !busy) {
                                Text(if (listening) "Parar" else "Falar")
                            }
                        }
                    }
                }
            }
        }

        if (showNew) {
            val hasSummaries = app.summaries.all().any { it.useInNewChats }
            AlertDialog(
                onDismissRequest = { if (!summarizing) showNew = false },
                title = { Text("Nova conversa") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (summarizing) "Resumindo a conversa…"
                            else "Posso guardar um resumo desta conversa (em Ajustes → Resumos de conversa) para você usar depois.",
                        )
                        if (!summarizing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = loadSummaries, onCheckedChange = { loadSummaries = it })
                                Text(
                                    if (hasSummaries) "Começar já com os resumos marcados" else "Começar com resumos (nenhum marcado ainda)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !summarizing, onClick = {
                        if (!app.conversation.hasConversation()) {
                            app.conversation.newConversation(loadSummaries)
                            showNew = false
                            return@TextButton
                        }
                        summarizing = true
                        lifecycleScope.launch {
                            app.conversation.summarizeCurrent()
                            app.conversation.newConversation(loadSummaries)
                            summarizing = false
                            showNew = false
                            status = "Resumo salvo"
                        }
                    }) { Text("Resumir e começar") }
                },
                dismissButton = {
                    Row {
                        TextButton(enabled = !summarizing, onClick = {
                            app.conversation.newConversation(loadSummaries)
                            showNew = false
                        }) { Text("Só começar") }
                        TextButton(enabled = !summarizing, onClick = { showNew = false }) { Text("Cancelar") }
                    }
                },
            )
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
            if (entry.queued) {
                Text("na fila — respondo junto, quando terminar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        /** Pedido dito junto com o chamado ("Oi Joca, que horas são"): é enviado direto. */
        const val EXTRA_WAKE_TEXT = "wake_text"
    }
}
