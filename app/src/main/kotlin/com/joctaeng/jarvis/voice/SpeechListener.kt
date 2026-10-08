package com.joctaeng.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.joctaeng.jarvis.mind.persona.UtteranceEnd
import com.joctaeng.jarvis.system.resources.EventLog

/**
 * Escuta uma fala por vez, com texto parcial enquanto o usuário fala.
 * Prefere o reconhecedor no aparelho (funcionou na PoC 0.4). Usar na thread principal.
 *
 * Tolerância a pausas: o reconhecedor do Android encerra a fala cedo (e os extras de silêncio
 * são só uma sugestão, que muitos reconhecedores ignoram). Por isso, ao receber um resultado
 * final, o texto fica guardado por [graceMs] e a escuta recomeça; se a pessoa voltar a falar nesse
 * intervalo, as partes são juntadas numa só frase. Só depois da carência o resultado é entregue.
 */
class SpeechListener(
    private val context: Context,
    private val events: EventLog? = null,
) {
    /** Silêncio (ms) que se espera depois de uma frase reconhecida antes de entregá-la; 0 = entrega na hora (comandos). */
    @Volatile var graceMs: Long = 1100L

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var accumulated = ""
    private var emitFinal: Runnable? = null

    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Level(val rmsDb: Float) : Event
        data class Final(val text: String) : Event

        /** [silent] = ninguém falou (não é falha real; encerra a conversa por voz). */
        data class Failed(val message: String, val silent: Boolean) : Event
    }

    val isListening: Boolean get() = recognizer != null

    fun start(onEvent: (Event) -> Unit) {
        stop()
        accumulated = ""
        startSession(onEvent)
    }

    private fun join(a: String, b: String) = if (a.isBlank()) b else "$a $b"

    private fun deliverFinal(onEvent: (Event) -> Unit) {
        emitFinal?.let { handler.removeCallbacks(it) }
        emitFinal = null
        val text = accumulated
        accumulated = ""
        stopRecognizer()
        events?.info("escuta", "fala reconhecida: ${text.length} caracteres" + if (text.length <= 15) " «$text»" else "")
        if (text.isBlank()) onEvent(Event.Failed("Não entendi", silent = true)) else onEvent(Event.Final(text))
    }

    private fun startSession(onEvent: (Event) -> Unit) {
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            events?.error("escuta", "nenhum reconhecimento de voz instalado ou disponível")
            onEvent(Event.Failed("Nenhum reconhecimento de voz instalado", silent = false))
            return
        }
        val r = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onEvent(Event.Level(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
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
                release(r)
                if (!text.isNullOrBlank()) accumulated = join(accumulated, text)
                if (accumulated.isBlank()) {
                    onEvent(Event.Failed("Não entendi", silent = true))
                } else if (graceMs <= 0) {
                    deliverFinal(onEvent)
                } else {
                    // Escuta de novo e só entrega se a pessoa ficar em silêncio pela carência.
                    startSession(onEvent)
                    // Frase que parece inacabada ("...e", "...porque", vírgula) ganha mais tempo.
                    val wait = graceMs + if (UtteranceEnd.looksIncomplete(accumulated)) INCOMPLETE_EXTRA_MS else 0L
                    val run = Runnable { deliverFinal(onEvent) }
                    emitFinal = run
                    handler.postDelayed(run, wait)
                }
            }

            override fun onError(error: Int) {
                release(r)
                if (accumulated.isNotBlank()) {
                    // Silêncio depois de uma fala válida = a pessoa terminou.
                    deliverFinal(onEvent)
                    return
                }
                val silent = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                if (silent) events?.info("escuta", "sem fala: ${describe(error)}") else events?.error("escuta", "falha no reconhecimento: ${describe(error)} (código $error)")
                onEvent(Event.Failed(describe(error), silent))
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Sugestões de tolerância a pausas (nem todo reconhecedor respeita; a carência acima cobre).
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L),
        )
    }

    fun stop() {
        emitFinal?.let { handler.removeCallbacks(it) }
        emitFinal = null
        accumulated = ""
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
        r.destroy()
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada"
        SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi"
        SpeechRecognizer.ERROR_AUDIO -> "Erro no microfone"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de microfone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecedor ocupado; tente de novo"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erro de rede no reconhecimento"
        else -> "Erro no reconhecimento ($code)"
    }

    private companion object {
        const val INCOMPLETE_EXTRA_MS = 1_500L
    }
}
