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
import com.joctaeng.jarvis.mind.persona.VoiceCommands
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
    private lateinit var bargeListener: SpeechListener
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bargeOn get() = app.settings.bargeIn
    private var partial by mutableStateOf("")
    private var status by mutableStateOf("")
    private var voiceMode by mutableStateOf(false)

    /** Legenda: janela pequena no pé da tela, sem bloquear o app de trás. "Expandir" volta ao chat completo. */
    private var captionOnly by mutableStateOf(false)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status = "Sem permissão de microfone: use o teclado."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listener = SpeechListener(this, events = app.events, muteBeep = { app.settings.muteMicBeep }, priority = 2)
        bargeListener = SpeechListener(this, events = app.events, muteBeep = { app.settings.muteMicBeep }, priority = 1).also { it.graceMs = 0L }
        app.conversation.preloadLocalModel()
        OverlayBus.sessionActive.value = true
        CharacterSync.bind(lifecycleScope, renderer, app.voice.speaking)
        lifecycleScope.launch { app.settings.version.collect { renderer.applyProfile(app.settings.character) } }
        voiceMode = app.settings.listenOnOpen && intent.getBooleanExtra(EXTRA_FROM_TAP, false)
        setCaption(app.settings.captionMode && intent.getBooleanExtra(EXTRA_FROM_TAP, false))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.conversation.turnFinished.collect {
                    if (voiceMode && app.settings.continuousVoice) startListening()
                }
            }
        }
        // Ouvir comandos enquanto ele fala ("pera aí", "tchau"...): liga com a opção marcada em Ajustes → Conversa.
        lifecycleScope.launch {
            app.voice.speaking.collect { speaking ->
                if (speaking && bargeOn) startBarge() else stopBarge()
                app.events.info("escuta", "falando=$speaking; ouvir comandos ao falar=${if (bargeOn) "ligado" else "desligado"}")
            }
        }
        // "Tchau": encerra a conversa por voz e fecha a janela; o personagem se recolhe.
        lifecycleScope.launch {
            OverlayBus.dismissRequests.collect {
                voiceMode = false
                stopListening()
                finish()
            }
        }
        // "Para de ouvir" / "encerrar": a conversa por voz acaba, o personagem fica.
        lifecycleScope.launch {
            OverlayBus.stopListeningRequests.collect {
                voiceMode = false
                stopListening()
            }
        }
        setContent { JarvisTheme { ChatSheet() } }
        val wakeText = intent.getStringExtra(EXTRA_WAKE_TEXT).orEmpty()
        if (wakeText.isNotBlank()) {
            voiceMode = true
            app.conversation.send(wakeText, speak = true)
        } else if (voiceMode) {
            startListening()
        }
    }

    override fun onStop() {
        stopListening()
        stopBarge()
        super.onStop()
    }

    override fun onDestroy() {
        OverlayBus.sessionActive.value = false
        OverlayBus.listening.value = false
        super.onDestroy()
    }

    /** Escuta curta enquanto ele fala: só comandos valem (sem fone, o eco da própria voz dele não vira mensagem). */
    private fun startBarge() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        fun again(delay: Long) {
            if (app.voice.speaking.value && bargeOn) mainHandler.postDelayed({ startBarge() }, delay)
        }
        app.events.info("escuta", "ouvindo comandos enquanto ele fala")
        status = "Ouvindo comandos…"
        bargeListener.start { event ->
            when (event) {
                is SpeechListener.Event.Final -> {
                    app.events.info("escuta", "ouvido durante a fala: ${event.text.length} caracteres")
                    handleBarge(event.text)
                    again(250)
                }
                is SpeechListener.Event.Failed -> {
                    app.events.info("escuta", "escuta de comandos falhou: ${event.message}")
                    again(700)
                }
                else -> Unit
            }
        }
    }

    private fun stopBarge() {
        if (status == "Ouvindo comandos…") status = ""
        mainHandler.removeCallbacksAndMessages(null)
        bargeListener.stop()
    }

    private fun handleBarge(text: String) {
        val isCommand = VoiceCommands.parse(text) != null
        if (!isCommand) {
            if (!headsetConnected()) return // alto-falante: o que ele ouviu pode ser a própria voz dele
            val spoken = flat(app.conversation.entries.value.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty())
            val heard = flat(text)
            if (heard.length >= 4 && spoken.contains(heard)) return // eco
        }
        app.events.info("escuta", "fala durante a resposta: ${if (isCommand) "comando" else "mensagem"} (${text.length} caracteres)")
        app.conversation.send(text, speak = app.settings.speakReplies)
    }

    private fun flat(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    private fun headsetConnected(): Boolean {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return false
        val types = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    private var listenRetries = 0

    private fun startListening(fromRetry: Boolean = false) {
        if (!fromRetry) listenRetries = 0
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        listener.graceMs = app.settings.listenPatienceMs.toLong()
        app.voice.stop()
        partial = ""
        status = "Ouvindo…"
        OverlayBus.listening.value = true
        listener.start { event ->
            when (event) {
                is SpeechListener.Event.Partial -> partial = event.text
                is SpeechListener.Event.Level -> Unit
                is SpeechListener.Event.Final -> {
                    listenRetries = 0
                    OverlayBus.listening.value = false
                    partial = ""
                    status = ""
                    app.conversation.send(event.text, speak = app.settings.speakReplies)
                }
                is SpeechListener.Event.Failed -> {
                    OverlayBus.listening.value = false
                    partial = ""
                    if (event.transient && voiceMode && listenRetries < MAX_LISTEN_RETRIES) {
                        // Falha passageira do serviço de voz (ocupado, desconectado): tenta de novo sozinho.
                        listenRetries++
                        status = "Reconectando a escuta…"
                        app.events.warn("escuta", "tentando de novo sozinho (${listenRetries}ª vez) após ${SpeechListener.name(event.code)}")
                        mainHandler.postDelayed({ if (voiceMode && !app.voice.speaking.value) startListening(fromRetry = true) }, 600L * listenRetries)
                        return@start
                    }
                    if (event.transient) app.events.error("escuta", "desisti após $listenRetries tentativas automáticas; é preciso tocar em Falar")
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

    @Composable
    private fun ChatSheet() {
        if (captionOnly) CaptionBar() else FullChat()
    }

    @Composable
    private fun CaptionBar() {
        val entries by app.conversation.entries.collectAsState()
        val busy by app.conversation.busy.collectAsState()
        val listening by OverlayBus.listening.collectAsState()
        val speaking by app.voice.speaking.collectAsState()
        val last = entries.lastOrNull { !it.note }
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
                    TextButton(onClick = ::finish) { Text("Fechar") }
                }
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
                    if (!keyboardOpen) {
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
        private const val MAX_LISTEN_RETRIES = 3
        /** Pedido dito junto com o chamado ("Oi Joca, que horas são"): é enviado direto. */
        const val EXTRA_WAKE_TEXT = "wake_text"
    }
}
