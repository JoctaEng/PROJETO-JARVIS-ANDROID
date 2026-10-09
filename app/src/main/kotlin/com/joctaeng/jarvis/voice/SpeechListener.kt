package com.joctaeng.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.joctaeng.jarvis.mind.persona.UtteranceEnd
import com.joctaeng.jarvis.system.resources.EventLog

/**
 * Escuta uma fala por vez, com texto parcial enquanto o usuário fala. Usar na thread principal.
 *
 * Tolerância a pausas: o reconhecedor do Android encerra a fala cedo. Ao receber um resultado
 * final, o texto fica guardado por [graceMs] e a escuta recomeça; se a pessoa voltar a falar nesse
 * intervalo, as partes são juntadas numa só frase. Só depois da carência o resultado é entregue.
 *
 * Registro completo: cada início, "pronto para ouvir", começo/fim da fala, resultado e erro (com código e nome)
 * vai para o [events] com a [tag] (ex.: "escuta" na conversa, "chamado" no "Oi Joca").
 */
class SpeechListener(
    private val context: Context,
    private val events: EventLog? = null,
    /** Consultado a cada início de escuta: silenciar o "bip" de ativação do reconhecedor? */
    private val muteBeep: () -> Boolean = { false },
    private val tag: String = "escuta",
    /** Quem manda no microfone: conversa (2) > ouvir comandos enquanto fala (1) > "Oi Joca" (0). Só um reconhecedor ativo por vez. */
    private val priority: Int = 1,
) {
    /** Silêncio (ms) que se espera depois de uma frase reconhecida antes de entregá-la; 0 = entrega na hora (comandos). */
    @Volatile var graceMs: Long = 1100L

    /** Registrar também "pronto para ouvir" e começo/fim de fala (desligado no ouvinte do chamado, que roda em ciclo). */
    var verbose: Boolean = true

    /** Pôr no registro o texto de falas curtas (≤15 caracteres)? Desligado no "Oi Joca", que ouve o ambiente. */
    var logShortText: Boolean = true

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var accumulated = ""
    private var emitFinal: Runnable? = null
    private var sessionStartedAt = 0L
    private var pendingStart: Runnable? = null

    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Level(val rmsDb: Float) : Event
        data class Final(val text: String) : Event

        /**
         * [silent] = ninguém falou (não é falha real). [transient] = falha passageira do serviço (ocupado,
         * desconectado, cliente): vale tentar de novo sozinho. [code] = código do Android (0 se não houver).
         */
        data class Failed(val message: String, val silent: Boolean, val transient: Boolean = false, val code: Int = 0) : Event
    }

    val isListening: Boolean get() = recognizer != null

    private val audio = context.getSystemService(android.media.AudioManager::class.java)
    private var savedVolumes: Map<Int, Int>? = null
    private val unmute = Runnable { restoreVolumes() }

    /**
     * O reconhecedor toca um "bip" ao começar a ouvir (em geral nos fluxos de notificação/sistema). Abaixamos só esses
     * fluxos por ~1 s e voltamos ao valor anterior; música e voz do personagem não são tocadas.
     */
    private fun muteBeepBriefly() {
        if (!muteBeep()) return
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        if (nm == null || !nm.isNotificationPolicyAccessGranted) {
            if (!muteWarned) {
                muteWarned = true
                events?.warn(tag, "silenciar o bip precisa do acesso a Não perturbe (Ajustes → Conversa → Teste completo); sem ele o bip continua")
            }
            return
        }
        runCatching {
            if (savedVolumes == null) {
                val streams = listOf(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.STREAM_SYSTEM)
                savedVolumes = streams.associateWith { audio.getStreamVolume(it) }
                streams.forEach { audio.setStreamVolume(it, 0, 0) }
            }
            handler.removeCallbacks(unmute)
            handler.postDelayed(unmute, 1_100L)
        }.onFailure { events?.warn(tag, "não consegui silenciar o bip (${it.message})") }
    }

    private fun restoreVolumes() {
        handler.removeCallbacks(unmute)
        val saved = savedVolumes ?: return
        savedVolumes = null
        runCatching { saved.forEach { (stream, volume) -> audio.setStreamVolume(stream, volume, 0) } }
    }

    fun start(onEvent: (Event) -> Unit) {
        stop()
        accumulated = ""
        val current = active
        if (current != null && current !== this && current.isListening) {
            if (current.priority > priority) {
                // Um ouvinte mais importante está usando o microfone: este espera a vez.
                events?.info(tag, "microfone em uso por outro ouvinte (${current.tag}); não vou disputar")
                handler.post { onEvent(Event.Failed("Microfone em uso", silent = false, transient = true, code = SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) }
                return
            }
            events?.info(tag, "pedindo o microfone: soltando o ouvinte de ${current.tag}")
            current.stop()
        }
        active = this
        // O Android recusa (ocupado/desconectado) um reconhecedor criado logo depois de outro ser destruído.
        val wait = (RELEASE_GAP_MS - (SystemClock.elapsedRealtime() - lastReleaseAt)).coerceIn(0L, RELEASE_GAP_MS)
        if (wait == 0L) {
            startSession(onEvent)
        } else {
            val run = Runnable { pendingStart = null; startSession(onEvent) }
            pendingStart = run
            handler.postDelayed(run, wait)
        }
    }

    private fun join(a: String, b: String) = if (a.isBlank()) b else "$a $b"

    private fun deliverFinal(onEvent: (Event) -> Unit) {
        emitFinal?.let { handler.removeCallbacks(it) }
        emitFinal = null
        val text = accumulated
        accumulated = ""
        stopRecognizer()
        events?.info(tag, "fala reconhecida: ${text.length} caracteres" + if (logShortText && text.length <= 15) " «$text»" else "")
        if (text.isBlank()) onEvent(Event.Failed("Não entendi", silent = true)) else onEvent(Event.Final(text))
    }

    private fun startSession(onEvent: (Event) -> Unit) {
        val onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            events?.error(tag, "nenhum reconhecimento de voz instalado ou disponível")
            onEvent(Event.Failed("Nenhum reconhecimento de voz instalado", silent = false))
            return
        }
        val r = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            events?.error(tag, "não consegui criar o reconhecedor (${if (onDevice) "no aparelho" else "padrão"})", e)
            onEvent(Event.Failed("Reconhecedor indisponível", silent = false, transient = true))
            return
        }
        recognizer = r
        sessionStartedAt = SystemClock.elapsedRealtime()
        if (verbose) events?.info(tag, "início da escuta (reconhecedor ${if (onDevice) "no aparelho" else "padrão"}${if (accumulated.isNotEmpty()) ", continuação" else ""})")
        muteBeepBriefly()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (verbose) events?.info(tag, "pronto para ouvir em ${SystemClock.elapsedRealtime() - sessionStartedAt} ms")
            }
            override fun onBeginningOfSpeech() {
                if (verbose) events?.info(tag, "começou a falar")
            }
            override fun onRmsChanged(rmsdB: Float) = onEvent(Event.Level(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (verbose) events?.info(tag, "parou de falar")
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let {
                        // Voltou a falar dentro da carência: segura a entrega e mostra a frase inteira.
                        emitFinal?.let { r -> handler.removeCallbacks(r) }
                        emitFinal = null
                        onEvent(Event.Partial(join(accumulated, it)))
                    }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (onDevice) onDeviceFailures = 0
                if (!text.isNullOrBlank()) accumulated = join(accumulated, text)
                if (accumulated.isBlank()) {
                    release(r)
                    if (verbose) events?.info(tag, "resultado vazio")
                    onEvent(Event.Failed("Não entendi", silent = true))
                } else if (graceMs <= 0) {
                    release(r)
                    deliverFinal(onEvent)
                } else {
                    // Escuta de novo NO MESMO reconhecedor (recriar logo em seguida causava ERROR_SERVER_DISCONNECTED).
                    val again = runCatching {
                        sessionStartedAt = SystemClock.elapsedRealtime()
                        r.startListening(listenIntent())
                    }.isSuccess
                    if (again) {
                        if (verbose) events?.info(tag, "continuação (mesmo reconhecedor)")
                    } else {
                        release(r)
                        startSession(onEvent)
                    }
                    // Frase que parece inacabada ("...e", "...porque", vírgula) ganha mais tempo.
                    val wait = graceMs + if (UtteranceEnd.looksIncomplete(accumulated)) INCOMPLETE_EXTRA_MS else 0L
                    val run = Runnable { deliverFinal(onEvent) }
                    emitFinal = run
                    handler.postDelayed(run, wait)
                }
            }

            override fun onError(error: Int) {
                release(r)
                val after = SystemClock.elapsedRealtime() - sessionStartedAt
                if (accumulated.isNotBlank()) {
                    // Silêncio (ou falha) depois de uma fala válida = a pessoa terminou.
                    if (verbose || error !in SILENT) events?.info(tag, "fim da continuação: ${name(error)} (código $error) após $after ms; entregando o que foi ouvido")
                    deliverFinal(onEvent)
                    return
                }
                val silent = error in SILENT
                val transient = error in TRANSIENT
                if (silent) {
                    if (verbose) events?.info(tag, "sem fala: ${name(error)} (código $error) após $after ms")
                } else {
                    events?.error(tag, "falha no reconhecimento: ${name(error)} (código $error) após $after ms, reconhecedor ${if (onDevice) "no aparelho" else "padrão"}")
                }
                // O reconhecedor do aparelho que cai seguidas vezes (desconectado/servidor) é trocado pelo padrão nesta execução.
                if (onDevice && transient && ++onDeviceFailures >= 2) {
                    preferOnDevice = false
                    events?.warn(tag, "reconhecedor do aparelho falhou $onDeviceFailures vezes seguidas; usando o reconhecedor padrão a partir de agora")
                }
                onEvent(Event.Failed(describe(error), silent, transient, error))
            }
        })
        r.startListening(listenIntent())
    }

    private fun listenIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Sugestões de tolerância a pausas (nem todo reconhecedor respeita; a carência acima cobre).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)

    fun stop() {
        pendingStart?.let { handler.removeCallbacks(it) }
        pendingStart = null
        if (active === this) active = null
        restoreVolumes()
        emitFinal?.let { handler.removeCallbacks(it) }
        emitFinal = null
        accumulated = ""
        if (recognizer != null && verbose) events?.info(tag, "escuta interrompida pelo app")
        stopRecognizer()
    }

    private fun stopRecognizer() {
        recognizer?.let {
            it.cancel()
            release(it)
        }
    }

    private fun release(r: SpeechRecognizer) {
        if (recognizer === r) recognizer = null
        lastReleaseAt = SystemClock.elapsedRealtime()
        r.destroy()
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada"
        SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi"
        SpeechRecognizer.ERROR_AUDIO -> "Erro no microfone"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de microfone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecedor ocupado; tentando de novo"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "O serviço de voz desconectou; tentando de novo"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erro de rede no reconhecimento"
        else -> "Erro no reconhecimento: ${name(code)} ($code)"
    }

    companion object {
        const val INCOMPLETE_EXTRA_MS = 1_500L
        private const val RELEASE_GAP_MS = 350L

        /** O ouvinte que está com o microfone neste processo (só um por vez). */
        @Volatile private var active: SpeechListener? = null
        @Volatile private var lastReleaseAt = 0L
        @Volatile private var muteWarned = false
        private val SILENT = setOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH)
        private val TRANSIENT = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        )

        /** Vale para o processo todo: se o reconhecedor do aparelho cair em sequência, todos passam para o padrão. */
        @Volatile private var preferOnDevice = true
        @Volatile private var onDeviceFailures = 0

        /** Nome oficial do código de erro do Android (para o relatório). */
        fun name(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "desconhecido"
        }
    }
}
