package com.joctaeng.jarvis.poc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

data class SttResult(
    val text: String?,
    val error: String?,
    val onDevice: Boolean,
    /** Do fim da fala até o resultado — a latência que o usuário sente. */
    val latencyAfterSpeechMillis: Long?,
)

/**
 * PoC 0.4 (parte STT) — reconhecimento de fala do próprio Android, preferindo o
 * reconhecedor no aparelho (offline) quando disponível. É a linha de base para
 * comparar com o sherpa-onnx (ver docs/ADR/0005-voz-na-fase-0.md).
 * Deve ser usado na thread principal.
 */
class SttProbe(private val context: Context) {

    fun listen(preferOnDevice: Boolean = true, onResult: (SttResult) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context) &&
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            onResult(SttResult(null, "Nenhum serviço de reconhecimento de voz instalado", false, null))
            return
        }
        val onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        var endOfSpeechAt = 0L
        var finished = false
        fun finish(result: SttResult) {
            if (finished) return
            finished = true
            recognizer.destroy()
            onResult(result)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                endOfSpeechAt = SystemClock.elapsedRealtime()
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                finish(SttResult(null, errorName(error), onDevice, null))
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                val latency = if (endOfSpeechAt > 0) SystemClock.elapsedRealtime() - endOfSpeechAt else null
                finish(SttResult(text, if (text == null) "sem resultado" else null, onDevice, latency))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        recognizer.startListening(intent)
    }

    private fun errorName(code: Int): String = when (code) {
        1 -> "tempo de rede esgotado"
        2 -> "erro de rede"
        3 -> "erro de áudio"
        4 -> "erro no servidor"
        5 -> "erro no cliente"
        6 -> "nenhuma fala detectada"
        7 -> "fala não reconhecida"
        8 -> "reconhecedor ocupado"
        9 -> "sem permissão de microfone"
        10 -> "excesso de pedidos"
        11 -> "servidor desconectado"
        12 -> "idioma pt-BR não suportado"
        13 -> "pacote de idioma pt-BR não baixado"
        else -> "erro $code"
    }
}

data class TtsResult(
    val initMillis: Long,
    val languageStatus: String,
    val offlineVoices: Int,
    val engine: String?,
    val startLatencyMillis: Long?,
    val error: String?,
)

/** PoC 0.4 (parte TTS) — voz do sistema em pt-BR: tempo de início e vozes offline. */
class TtsProbe(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun speak(text: String, onResult: (TtsResult) -> Unit) {
        val createdAt = SystemClock.elapsedRealtime()
        var tts: TextToSpeech? = null
        // O callback de init pode chegar antes da atribuição; por isso é repassado à thread principal.
        tts = TextToSpeech(appContext) { status ->
            main.post { onInit(checkNotNull(tts), status, createdAt, text, onResult) }
        }
    }

    private fun onInit(engine: TextToSpeech, status: Int, createdAt: Long, text: String, onResult: (TtsResult) -> Unit) {
        val initMillis = SystemClock.elapsedRealtime() - createdAt
        if (status != TextToSpeech.SUCCESS) {
            engine.shutdown()
            onResult(TtsResult(initMillis, "-", 0, null, null, "motor de voz não iniciou ($status)"))
            return
        }
        val lang = when (engine.setLanguage(Locale.forLanguageTag("pt-BR"))) {
            TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "disponível"
            TextToSpeech.LANG_MISSING_DATA -> "faltam dados"
            else -> "não suportado"
        }
        val offline = engine.voices.orEmpty().count { it.locale.language == "pt" && !it.isNetworkConnectionRequired }
        val engineName = engine.defaultEngine
        var speakAt = 0L
        var startLatency: Long? = null
        var finished = false
        fun finish(error: String?) {
            if (finished) return
            finished = true
            engine.shutdown()
            onResult(TtsResult(initMillis, lang, offline, engineName, startLatency, error))
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                startLatency = SystemClock.elapsedRealtime() - speakAt
            }

            override fun onDone(utteranceId: String?) {
                main.post { finish(null) }
            }

            @Deprecated("Exigido pela API; a versão com código de erro é a usada.")
            override fun onError(utteranceId: String?) {
                main.post { finish("erro") }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { finish("erro $errorCode") }
            }
        })
        speakAt = SystemClock.elapsedRealtime()
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-poc") != TextToSpeech.SUCCESS) {
            finish("speak() recusado")
        }
    }
}
