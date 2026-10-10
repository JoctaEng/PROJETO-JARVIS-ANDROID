package com.joctaeng.jarvis.overlay

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.mind.persona.WakeWord
import com.joctaeng.jarvis.voice.SpeechListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Fica ouvindo "Oi <nome>" enquanto o serviço do personagem roda (versão 1: reconhecimento de fala do Android em ciclos,
 * sem identificar a voz — qualquer pessoa que diga o nome acorda). Solta o microfone NA HORA em que a conversa abre,
 * ele fala ou alguém toca nele (antes, a escuta em andamento continuava e disputava o microfone com a conversa,
 * o que provocava falhas como ERROR_SERVER_DISCONNECTED). Pausa com a tela apagada. Usar a thread principal.
 */
class WakeWordRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val app: JarvisApp,
    private val onWake: (rest: String) -> Unit,
) {
    private var job: Job? = null
    private var listener: SpeechListener? = null
    @Volatile private var pausedUntil = 0L
    private var cycles = 0
    private var errors = 0

    private val busy = combine(OverlayBus.sessionActive, OverlayBus.listening, app.voice.speaking, OverlayBus.voiceSession) { a, b, c, d -> a || b || c || d }

    fun start() {
        if (job != null) return
        app.events.info("chamado", "escuta do chamado ligada (nomes=${app.settings.wakeNames.joinToString("/")})")
        job = scope.launch {
            var failures = 0
            while (isActive) {
                if (!canListen()) {
                    delay(700)
                    continue
                }
                cycles++
                val heard = listenUnlessBusy()
                when {
                    heard == null -> {
                        failures++
                        delay(if (failures < 3) 300L else 2_500L) // sem fala/erro: espera mais para não gastar bateria à toa
                    }
                    else -> {
                        failures = 0
                        val match = WakeWord.matchAny(heard, app.settings.wakeNames)
                        if (match != null) {
                            app.events.info("chamado", "chamado reconhecido (${heard.length} caracteres ouvidos, pedido junto=${match.rest.isNotEmpty()}, ciclo $cycles)")
                            pauseNow(4_000)
                            onWake(match.rest)
                        } else {
                            delay(150)
                        }
                    }
                }
                if (cycles % 100 == 0) app.events.info("chamado", "$cycles ciclos de escuta, $errors falhas até agora")
            }
        }
    }

    /** Solta o microfone já (ex.: toque no personagem) e não volta a ouvir por [ms]. */
    fun pauseNow(ms: Long = 3_000) {
        pausedUntil = SystemClock.elapsedRealtime() + ms
        listener?.stop()
    }

    fun stop() {
        job?.cancel()
        job = null
        listener?.stop()
        listener = null
        app.events.info("chamado", "escuta do chamado desligada ($cycles ciclos, $errors falhas)")
    }

    private fun canListen(): Boolean {
        if (SystemClock.elapsedRealtime() < pausedUntil) return false
        val power = context.getSystemService(PowerManager::class.java)
        return power.isInteractive && !OverlayBus.sessionActive.value && !OverlayBus.voiceSession.value && !OverlayBus.listening.value && !app.voice.speaking.value
    }

    /** Uma escuta curta, cancelada assim que a conversa precisar do microfone. Texto ouvido, ou null. */
    private suspend fun listenUnlessBusy(): String? = coroutineScope {
        val listen = async { listenOnce() }
        val watch = launch {
            busy.first { it }
            app.events.info("chamado", "conversa/voz em uso: soltando o microfone")
            listen.cancel()
        }
        try {
            // Se outro ouvinte tomou o microfone, esta escuta nunca responde: 20 s e recomeça.
            kotlinx.coroutines.withTimeoutOrNull(20_000) { listen.await() }.also { if (it == null) listen.cancel() }
        } catch (e: CancellationException) {
            if (!isActive) throw e
            null
        } finally {
            watch.cancel()
        }
    }

    private suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        val l = SpeechListener(context, events = app.events, muteBeep = { app.settings.muteMicBeep }, tag = "chamado", priority = 0).also {
            it.graceMs = 0L
            it.verbose = false
            it.logShortText = false
        }
        listener = l
        l.start { event ->
            when (event) {
                is SpeechListener.Event.Final -> if (cont.isActive) cont.resume(event.text)
                is SpeechListener.Event.Failed -> {
                    if (!event.silent) errors++
                    if (cont.isActive) cont.resume(null)
                }
                else -> Unit
            }
        }
        cont.invokeOnCancellation { l.stop() }
    }
}
