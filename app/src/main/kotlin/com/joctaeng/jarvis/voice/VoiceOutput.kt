package com.joctaeng.jarvis.voice

import com.joctaeng.jarvis.mind.persona.SpokenText
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.joctaeng.jarvis.settings.CloudPreset
import com.joctaeng.jarvis.settings.VoiceEngine
import com.joctaeng.jarvis.mind.persona.Gender
import android.os.SystemClock
import com.joctaeng.jarvis.system.resources.EventLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
class VoiceOutput(
    context: Context,
    private val settings: AppSettings,
    private val events: EventLog? = null,
    private val geminiKey: () -> String? = { null },
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayDeque<String>()
    private val counter = AtomicInteger()
    @Volatile private var geminiSkipUntil = settings.geminiTtsSkipUntil
    @Volatile private var lastClipEndAt = 0L
    private val androidWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

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

    /** Um pedido de voz: uma ou mais frases juntas ([count] diz quantas, para a fila contar certo). */
    private class Chunk(val text: String, val offeredAt: Long, val count: Int, val pending: Deferred<Spoken>)

    /** Resultado de uma frase: o áudio (null = não deu, cai para a voz do Android), o motor e o tempo gasto. */
    private class Spoken(val clip: GeminiSpeech.Clip?, val engine: String, val synthMs: Long)

    /** Gera uma frase em áudio. */
    private fun interface Synth {
        suspend fun clip(text: String): Spoken
    }

    /** Ordem: Gemini (online) → Kokoro (offline, se instalado) → voz do Android. */
    private fun naturalVoice(): Synth? {
        val engine = settings.voiceEngine
        if (engine == VoiceEngine.ANDROID) return null
        val gemini = if (engine != VoiceEngine.KOKORO && settings.cloudPreset == CloudPreset.GEMINI && online()) {
            geminiKey()?.takeIf { it.isNotBlank() }?.let { GeminiSpeech(it, settings.geminiTtsModel.ifBlank { GeminiSpeech.DEFAULT_MODEL }) }
        } else null
        val offline = if (engine != VoiceEngine.GEMINI && kokoro.installed && (engine == VoiceEngine.KOKORO || !settings.kokoroTooSlow)) kokoro else null
        if (gemini == null && offline == null) return null
        return Synth { text ->
            val t0 = SystemClock.elapsedRealtime()
            var clip: GeminiSpeech.Clip? = null
            var used = "nenhum"
            // Gemini com disjuntor: depois de uma falha ele descansa por 2 min, para não atrasar cada frase.
            if (gemini != null && System.currentTimeMillis() >= geminiSkipUntil) {
                val used0 = countGeminiRequest()
                val r = runCatching { gemini.synthesize(text, naturalVoiceName()) }
                clip = r.getOrNull()
                if (clip != null) {
                    used = "gemini/" + (clip?.via ?: "")
                } else {
                    val error = r.exceptionOrNull()
                    val quota = error?.message?.contains("HTTP 429") == true
                    val rest = if (quota) quotaCooldownMs(error?.message.orEmpty()) else GEMINI_COOLDOWN_MS
                    geminiSkipUntil = System.currentTimeMillis() + rest
                    if (quota) settings.geminiTtsSkipUntil = geminiSkipUntil
                    events?.warn(
                        "voz",
                        if (quota) "COTA do Gemini esgotada (pedido nº $used0 hoje): vou usar a voz do Android por ${rest / 60_000} min"
                        else "Gemini falhou após ${SystemClock.elapsedRealtime() - t0} ms; descansando 2 min e usando o motor seguinte",
                        error,
                    )
                }
            }
            if (clip == null && offline != null) {
                val r = runCatching { offline.synthesize(text, kokoroSpeaker(), settings.ttsRate) }
                clip = r.getOrNull()
                if (clip != null) {
                    used = "kokoro"
                    noteKokoroSpeed(clip, SystemClock.elapsedRealtime() - t0)
                } else {
                    events?.error("voz", "Kokoro falhou após ${SystemClock.elapsedRealtime() - t0} ms", r.exceptionOrNull())
                }
            }
            Spoken(clip, used, SystemClock.elapsedRealtime() - t0)
        }
    }

    /** Conta pedidos do dia à voz do Gemini (a conta gratuita tem limite diário) e avisa perto do limite. */
    private fun countGeminiRequest(): Int {
        val today = java.time.LocalDate.now().toString()
        if (settings.geminiTtsDay != today) {
            settings.geminiTtsDay = today
            settings.geminiTtsCount = 0
        }
        val n = settings.geminiTtsCount + 1
        settings.geminiTtsCount = n
        if (n == 80) events?.warn("voz", "Voz do Gemini: 80 pedidos hoje; o limite da conta gratuita costuma ser 100 por dia")
        return n
    }

    /** "retry in 5h48m11s" na resposta de cota → quanto esperar (entre 5 min e 6 h); sem aviso, 1 hora. */
    private fun quotaCooldownMs(message: String): Long {
        val m = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").find(message)
        val ms = m?.let {
            val h = it.groupValues[1].toLongOrNull() ?: 0
            val min = it.groupValues[2].toLongOrNull() ?: 0
            val sec = it.groupValues[3].toLongOrNull() ?: 0
            ((h * 60 + min) * 60 + sec) * 1000
        } ?: 0L
        return (if (ms > 0) ms else 3_600_000L).coerceIn(300_000L, 21_600_000L)
    }

    private var kokoroWarm = false
    private var kokoroSlowStreak = 0

    /**
     * Kokoro mais lento que o tempo real faz pausas entre frases. A 1ª frase de cada sessão inclui o carregamento e não conta;
     * duas frases seguidas acima de 1,5× o tempo real marcam o Kokoro como lento e o modo Automático passa a usar a voz do Android.
     */
    private fun noteKokoroSpeed(clip: GeminiSpeech.Clip, synthMs: Long) {
        val audioMs = (clip.pcm.size * 1000L / (clip.sampleRate * 2L)).coerceAtLeast(1)
        if (!kokoroWarm) {
            kokoroWarm = true
            return
        }
        if (synthMs * 10 > audioMs * 15) kokoroSlowStreak++ else kokoroSlowStreak = 0
        if (kokoroSlowStreak >= 2 && settings.voiceEngine == VoiceEngine.AUTO && !settings.kokoroTooSlow) {
            settings.kokoroTooSlow = true
            events?.warn("voz", "Kokoro lento neste aparelho (${synthMs * 100 / audioMs}% do tempo do áudio); o Automático passa a usar a voz do Android. Para voltar a usá-lo, escolha Kokoro em Meu Euno → Voz.")
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
        private val sentences = Channel<Pair<String, Long>>(Channel.UNLIMITED)
        private val clips = Channel<Chunk>(3)
        private val jobs = listOf(
            scope.launch {
                var first = true
                while (true) {
                    val (head, offeredAt) = sentences.receiveCatching().getOrNull() ?: break
                    var joined = head
                    var count = 1
                    // A 1ª frase sai sozinha (começa a falar logo); as seguintes que já esperam na fila viram um só pedido:
                    // menos pedidos à voz (a cota diária do Gemini é pequena) e fala mais contínua.
                    if (!first) {
                        while (joined.length < MERGE_MAX_CHARS) {
                            val next = sentences.tryReceive().getOrNull() ?: break
                            joined += " " + next.first
                            count++
                        }
                    }
                    first = false
                    val text = joined
                    clips.send(Chunk(text, offeredAt, count, scope.async { speech.clip(text) }))
                }
            },
            scope.launch(Dispatchers.IO) {
                for (chunk in clips) {
                    val text = chunk.text
                    val offeredAt = chunk.offeredAt
                    val spoken = chunk.pending.await()
                    if (stopped) break
                    val clip = spoken.clip
                    if (clip == null) {
                        // Falhou (rede, cota): esta frase sai na voz do Android, NA ORDEM, sem sobrepor a seguinte.
                        events?.warn("voz", "frase de ${text.length} caracteres sem áudio natural (${spoken.synthMs} ms); usando a voz do Android")
                        speakWithAndroidInOrder(text)
                        repeat(chunk.count) { finishedOne() }
                        lastClipEndAt = SystemClock.elapsedRealtime()
                        continue
                    }
                    val start = SystemClock.elapsedRealtime()
                    val gap = if (lastClipEndAt == 0L) 0L else start - lastClipEndAt
                    _speaking.value = true
                    PcmPlayer.play(clip) { stopped }
                    lastClipEndAt = SystemClock.elapsedRealtime()
                    val audioMs = clip.pcm.size * 1000L / (clip.sampleRate * 2L)
                    val note = "frase ${text.length} car.; motor=${spoken.engine}; síntese=${spoken.synthMs} ms; áudio=$audioMs ms; " +
                        "esperou=${start - offeredAt} ms; pausa antes=$gap ms"
                    if (gap in PAUSE_WARN_MS..PAUSE_IGNORE_MS) events?.warn("voz", "PAUSA entre frases: $note") else events?.info("voz", note)
                    repeat(chunk.count) { finishedOne() }
                }
            },
        )

        fun offer(text: String) {
            sentences.trySend(text to SystemClock.elapsedRealtime())
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
        androidWaiters.values.forEach { it.complete(Unit) }
        androidWaiters.clear()
        lastClipEndAt = 0L
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
            events?.error("voz", "motor de voz do Android não iniciou (status $status, motor ${activeEngine ?: "padrão"})")
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

            override fun onDone(utteranceId: String?) = ended(utteranceId)

            @Deprecated("Exigido pela API.")
            override fun onError(utteranceId: String?) = ended(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                events?.error("voz", "voz do Android falhou (código $errorCode)")
                ended(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)
        })
        ready = true
        while (waiting.isNotEmpty()) enqueue(waiting.removeFirst())
    }

    /** Fim de uma fala do Android: se alguém está esperando por ela (fila ordenada), avisa; senão conta como frase concluída. */
    private fun ended(utteranceId: String?) {
        val waiter = utteranceId?.let { androidWaiters.remove(it) }
        if (waiter != null) waiter.complete(Unit) else finishedOne()
    }

    /** Fala uma frase com a voz do Android e só retorna quando ela terminar (mantém a ordem com os áudios naturais). */
    private suspend fun speakWithAndroidInOrder(text: String) {
        val id = "jarvis-fb-${counter.getAndIncrement()}"
        val done = CompletableDeferred<Unit>()
        androidWaiters[id] = done
        val posted = CompletableDeferred<Boolean>()
        main.post {
            val engine = tts
            posted.complete(ready && engine != null && engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS)
        }
        if (!posted.await()) {
            androidWaiters.remove(id)
            events?.error("voz", "voz do Android indisponível; frase de ${text.length} caracteres não foi falada")
            return
        }
        _speaking.value = true
        // Frase longa precisa de mais tempo: ~14 caracteres por segundo, no mínimo 20 s.
        val limit = maxOf(ANDROID_TTS_TIMEOUT_MS, text.length * 70L + 10_000L)
        if (withTimeoutOrNull(limit) { done.await() } == null) {
            androidWaiters.remove(id)
            events?.error("voz", "voz do Android não terminou em ${limit / 1000} s (frase de ${text.length} caracteres)")
        }
    }

    private fun finishedOne() {
        val left = _queued.updateAndGetCompat { (it - 1).coerceAtLeast(0) }
        if (left == 0) _speaking.value = false
    }

    private fun enqueue(text: String) {
        val engine = tts ?: return
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, "jarvis-${counter.getAndIncrement()}")
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
        private const val GEMINI_COOLDOWN_MS = 120_000L
        private const val PAUSE_WARN_MS = 1_200L
        private const val PAUSE_IGNORE_MS = 15_000L
        private const val ANDROID_TTS_TIMEOUT_MS = 20_000L
        private const val MERGE_MAX_CHARS = 280
    }
}
