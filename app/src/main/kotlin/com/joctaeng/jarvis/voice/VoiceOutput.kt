package com.joctaeng.jarvis.voice

import com.joctaeng.jarvis.mind.persona.SpokenText
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.joctaeng.jarvis.settings.CloudPreset
import com.joctaeng.jarvis.settings.VoiceEngine
import com.joctaeng.jarvis.mind.persona.Gender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.joctaeng.jarvis.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.Locale

data class EngineOption(val packageName: String, val label: String)

data class VoiceOption(val name: String, val label: String, val needsNetwork: Boolean, val quality: Int)

/**
 * Voz do personagem. Fala frase a frase (fila), prefere o motor de voz do Google
 * quando instalado — no HyperOS o padrão costuma ser um motor mais robótico — e
 * escolhe a voz pt-BR de maior qualidade, salvo escolha do usuário.
 */
class VoiceOutput(context: Context, private val settings: AppSettings, private val geminiKey: () -> String? = { null }) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayDeque<String>()
    private var counter = 0

    private val _queued = MutableStateFlow(0)

    /** Frases na fila ainda não terminadas. */
    val queued: StateFlow<Int> = _queued.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    var activeEngine: String? = null
        private set

    // Voz natural do Gemini: fila própria, sintetiza a próxima frase enquanto a atual toca.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cloud: CloudPipeline? = null

    val kokoro = KokoroVoice(appContext)

    /** Gera uma frase em áudio; null = não deu (cai para a próxima voz). */
    private fun interface Synth {
        suspend fun clip(text: String): GeminiSpeech.Clip?
    }

    /** Ordem: Gemini (online) → Kokoro (offline, se instalado) → voz do Android. */
    private fun naturalVoice(): Synth? {
        val engine = settings.voiceEngine
        if (engine == VoiceEngine.ANDROID) return null
        val gemini = if (engine != VoiceEngine.KOKORO && settings.cloudPreset == CloudPreset.GEMINI && online()) {
            geminiKey()?.takeIf { it.isNotBlank() }?.let { GeminiSpeech(it, settings.geminiTtsModel.ifBlank { GeminiSpeech.DEFAULT_MODEL }) }
        } else null
        val offline = if (engine != VoiceEngine.GEMINI && kokoro.installed) kokoro else null
        if (gemini == null && offline == null) return null
        return Synth { text ->
            gemini?.let { g -> runCatching { g.synthesize(text, naturalVoiceName()) }.getOrNull() }
                ?: offline?.let { k -> runCatching { k.synthesize(text, kokoroSpeaker(), settings.ttsRate) }.getOrNull() }
        }
    }

    private fun kokoroSpeaker(): Int = settings.kokoroSpeaker.takeIf { it >= 0 }
        ?: KokoroVoice.defaultSpeakerFor(settings.character.id, settings.character.gender == Gender.FEMALE)

    private fun online(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun naturalVoiceName(): String = settings.geminiVoice.ifBlank { GeminiSpeech.defaultVoiceFor(settings.character.id) }

    /** Fila do Gemini: produtor sintetiza (até 2 frases adiante), consumidor toca em ordem. */
    private inner class CloudPipeline(private val speech: Synth) {
        @Volatile var stopped = false
        private val sentences = Channel<String>(Channel.UNLIMITED)
        private val clips = Channel<Pair<String, Deferred<GeminiSpeech.Clip?>>>(2)
        private val jobs = listOf(
            scope.launch {
                for (text in sentences) {
                    clips.send(text to scope.async { speech.clip(text) })
                }
            },
            scope.launch(Dispatchers.IO) {
                for ((text, pending) in clips) {
                    val clip = pending.await()
                    if (stopped) break
                    if (clip == null) {
                        // Falhou (rede, cota): esta frase sai na voz do Android, sem perder a fala.
                        main.post { if (ready) enqueue(text) else finishedOne() }
                        continue
                    }
                    _speaking.value = true
                    PcmPlayer.play(clip) { stopped }
                    finishedOne()
                }
            },
        )

        fun offer(text: String) {
            sentences.trySend(text)
        }

        fun stop() {
            stopped = true
            sentences.close()
            clips.cancel()
            jobs.forEach { it.cancel() }
        }
    }

    fun start() {
        if (tts != null) return
        val engine = settings.ttsEngine.ifBlank { null } ?: preferredEngine()
        activeEngine = engine
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext, { status -> main.post { onReady(created, status) } }, engine)
        tts = created
    }

    /** Recria o motor (ao mudar motor ou voz nas configurações). */
    fun restart() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
        start()
    }

    fun speak(text: String) {
        val clean = clean(text)
        if (clean.isBlank()) return
        _queued.update { it + 1 }
        naturalVoice()?.let { speech ->
            val pipeline = cloud?.takeIf { !it.stopped } ?: CloudPipeline(speech).also { cloud = it }
            pipeline.offer(clean)
            return
        }
        if (!ready) {
            waiting += clean
            return
        }
        enqueue(clean)
    }

    fun stop() {
        cloud?.stop()
        cloud = null
        waiting.clear()
        tts?.stop()
        _queued.value = 0
        _speaking.value = false
    }

    suspend fun awaitIdle() {
        queued.first { it == 0 }
    }

    fun engines(): List<EngineOption> =
        appContext.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { EngineOption(it.serviceInfo.packageName, it.loadLabel(appContext.packageManager).toString()) }
            .distinctBy { it.packageName }

    fun portugueseVoices(): List<VoiceOption> =
        tts?.voices.orEmpty()
            .filter { it.locale.language == "pt" }
            .sortedWith(compareBy<Voice>({ it.locale.country != "BR" }, { it.isNetworkConnectionRequired }, { -it.quality }))
            .map {
                VoiceOption(
                    name = it.name,
                    label = buildString {
                        append(it.name)
                        append(" · ").append(it.locale.toLanguageTag())
                        append(" · qualidade ").append(qualityLabel(it.quality))
                        if (it.isNetworkConnectionRequired) append(" · online")
                    },
                    needsNetwork = it.isNetworkConnectionRequired,
                    quality = it.quality,
                )
            }

    private fun onReady(engine: TextToSpeech?, status: Int) {
        if (engine == null || engine !== tts) return
        if (status != TextToSpeech.SUCCESS) {
            _queued.value = 0
            return
        }
        engine.language = Locale.forLanguageTag("pt-BR")
        val chosen = engine.voices.orEmpty().firstOrNull { it.name == settings.ttsVoice }
            ?: bestVoice(engine.voices.orEmpty())
        chosen?.let { engine.voice = it }
        engine.setSpeechRate(settings.ttsRate)
        engine.setPitch(settings.ttsPitch)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) = finishedOne()

            @Deprecated("Exigido pela API.")
            override fun onError(utteranceId: String?) = finishedOne()

            override fun onError(utteranceId: String?, errorCode: Int) = finishedOne()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finishedOne()
        })
        ready = true
        while (waiting.isNotEmpty()) enqueue(waiting.removeFirst())
    }

    private fun finishedOne() {
        val left = _queued.updateAndGetCompat { (it - 1).coerceAtLeast(0) }
        if (left == 0) _speaking.value = false
    }

    private fun enqueue(text: String) {
        val engine = tts ?: return
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, "jarvis-${counter++}")
        if (result != TextToSpeech.SUCCESS) finishedOne()
    }

    /** Offline e pt-BR primeiro; entre elas, a de maior qualidade. */
    private fun bestVoice(voices: Set<Voice>): Voice? =
        voices.filter { it.locale.language == "pt" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ it.locale.country != "BR" }, { it.isNetworkConnectionRequired }, { -it.quality }))
            .firstOrNull()

    private fun preferredEngine(): String? = engines().firstOrNull { it.packageName == GOOGLE_TTS }?.packageName

    /** Markdown sai e fórmula vira fala ("b ao quadrado menos 4 a c"), em vez de "asterisco" e "barra frac". */
    private fun clean(text: String): String = SpokenText.forSpeech(text)

    private fun qualityLabel(q: Int) = when {
        q >= Voice.QUALITY_VERY_HIGH -> "muito alta"
        q >= Voice.QUALITY_HIGH -> "alta"
        q >= Voice.QUALITY_NORMAL -> "normal"
        else -> "baixa"
    }

    private inline fun MutableStateFlow<Int>.updateAndGetCompat(f: (Int) -> Int): Int {
        while (true) {
            val prev = value
            val next = f(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
    }
}
