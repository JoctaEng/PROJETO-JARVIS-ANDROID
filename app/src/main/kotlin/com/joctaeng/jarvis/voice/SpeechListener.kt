package com.joctaeng.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Escuta uma fala por vez, com texto parcial enquanto o usuário fala.
 * Prefere o reconhecedor no aparelho (funcionou na PoC 0.4). Usar na thread principal.
 */
class SpeechListener(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

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
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
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
                    ?.takeIf { it.isNotBlank() }?.let { onEvent(Event.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                release(r)
                if (text.isNullOrBlank()) onEvent(Event.Failed("Não entendi", silent = true))
                else onEvent(Event.Final(text))
            }

            override fun onError(error: Int) {
                release(r)
                val silent = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                onEvent(Event.Failed(describe(error), silent))
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        )
    }

    fun stop() {
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
}
