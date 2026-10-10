package com.joctaeng.jarvis.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.mind.persona.VoiceCommands
import com.joctaeng.jarvis.overlay.OverlayBus
import com.joctaeng.jarvis.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Conversa por voz que NÃO depende da tela de conversa (antes a escuta morava na ChatActivity e parava quando o Euno
 * abria outro app: ele falava mas não ouvia mais). Vive no app inteiro, enquanto o personagem está na tela.
 * Continua ouvindo enquanto ele lê/age em outro app, e ouve comandos ("pera aí", "para") enquanto ele fala.
 */
class VoiceSession(private val app: JarvisApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val listener = SpeechListener(app, events = app.events, muteBeep = { app.settings.muteMicBeep }, priority = 2)
    private val barge = SpeechListener(app, events = app.events, muteBeep = { app.settings.muteMicBeep }, priority = 1).also { it.graceMs = 0L }
    private var retries = 0

    private val _active = MutableStateFlow(false)

    /** Conversa por voz ligada (continua ouvindo depois de cada resposta). */
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val partial = MutableStateFlow("")
    val status = MutableStateFlow("")

    /** A tela de conversa está visível (ouvir comandos enquanto fala vale também sem a conversa por voz ligada). */
    var chatVisible: Boolean
        get() = OverlayBus.chatVisible.value
        set(v) { OverlayBus.chatVisible.value = v }

    init {
        scope.launch {
            app.conversation.turnFinished.collect { if (_active.value && app.settings.continuousVoice) startListening() }
        }
        scope.launch {
            app.voice.speaking.collect { speaking ->
                if (speaking && app.settings.bargeIn && (_active.value || chatVisible)) startBarge() else stopBarge()
            }
        }
        scope.launch { OverlayBus.dismissRequests.collect { end() } }
        scope.launch { OverlayBus.stopListeningRequests.collect { end() } }
    }

    fun micGranted(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Liga a conversa por voz e começa a ouvir. Pede ao serviço do personagem o direito de usar o microfone em segundo plano. */
    fun begin() {
        _active.value = true
        OverlayBus.voiceSession.value = true
        if (OverlayBus.running.value) runCatching { OverlayService.start(app) }
        startListening()
    }

    /** Desliga a conversa por voz (fechar, "tchau", "para de ouvir", chave desligada). */
    fun end() {
        _active.value = false
        OverlayBus.voiceSession.value = false
        stopListening()
        stopBarge()
    }

    fun startListening(fromRetry: Boolean = false) {
        if (!micGranted()) {
            status.value = "Sem permissão de microfone: use o teclado."
            return
        }
        if (!fromRetry) retries = 0
        listener.graceMs = app.settings.listenPatienceMs.toLong()
        app.voice.stop()
        partial.value = ""
        status.value = "Ouvindo…"
        OverlayBus.listening.value = true
        listener.start { event ->
            when (event) {
                is SpeechListener.Event.Partial -> partial.value = event.text
                is SpeechListener.Event.Level -> Unit
                is SpeechListener.Event.Final -> {
                    retries = 0
                    OverlayBus.listening.value = false
                    partial.value = ""
                    status.value = ""
                    app.conversation.send(event.text, speak = app.settings.speakReplies || !chatVisible)
                }
                is SpeechListener.Event.Failed -> {
                    OverlayBus.listening.value = false
                    partial.value = ""
                    if (event.transient && _active.value && retries < MAX_RETRIES) {
                        // Falha passageira do serviço de voz (ocupado, desconectado): tenta de novo sozinho.
                        retries++
                        status.value = "Reconectando a escuta…"
                        app.events.warn("escuta", "tentando de novo sozinho (${retries}ª vez) após ${SpeechListener.name(event.code)}")
                        handler.postDelayed({ if (_active.value && !app.voice.speaking.value) startListening(fromRetry = true) }, 600L * retries)
                        return@start
                    }
                    if (event.transient) app.events.error("escuta", "desisti após $retries tentativas automáticas; é preciso tocar em Falar")
                    status.value = if (event.silent) "" else event.message
                    if (event.silent) {
                        _active.value = false
                        OverlayBus.voiceSession.value = false
                    }
                }
            }
        }
    }

    fun stopListening() {
        listener.stop()
        OverlayBus.listening.value = false
        partial.value = ""
        if (status.value == "Ouvindo…") status.value = ""
    }

    /** Escuta curta enquanto ele fala: só comandos valem (sem fone, o eco da própria voz dele não vira mensagem). */
    private fun startBarge() {
        if (!micGranted()) return
        fun again(delay: Long) {
            if (app.voice.speaking.value && app.settings.bargeIn) handler.postDelayed({ startBarge() }, delay)
        }
        app.events.info("escuta", "ouvindo comandos enquanto ele fala")
        status.value = "Ouvindo comandos…"
        barge.start { event ->
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
        if (status.value == "Ouvindo comandos…") status.value = ""
        handler.removeCallbacksAndMessages(null)
        barge.stop()
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
        app.conversation.send(text, speak = app.settings.speakReplies || !chatVisible)
    }

    private fun flat(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    private fun headsetConnected(): Boolean {
        val audio = app.getSystemService(android.media.AudioManager::class.java) ?: return false
        val types = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
