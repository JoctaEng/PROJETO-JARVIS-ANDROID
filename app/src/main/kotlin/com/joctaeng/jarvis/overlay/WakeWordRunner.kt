package com.joctaeng.jarvis.overlay

import android.content.Context
import android.os.PowerManager
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.mind.persona.WakeWord
import com.joctaeng.jarvis.voice.SpeechListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Fica ouvindo "Oi <nome>" enquanto o serviço do personagem roda (versão 1: reconhecimento de fala do Android em ciclos,
 * sem identificar a voz — qualquer pessoa que diga o nome acorda). Pausa quando a conversa está aberta, quando ele fala
 * ou com a tela apagada. Usar a thread principal (o reconhecedor exige).
 */
class WakeWordRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val app: JarvisApp,
    private val onWake: (rest: String) -> Unit,
) {
    private var job: Job? = null
    private var listener: SpeechListener? = null

    fun start() {
        if (job != null) return
        app.events.info("chamado", "escuta do chamado ligada (nome=${app.settings.wakeName})")
        job = scope.launch {
            var failures = 0
            while (isActive) {
                if (!canListen()) {
                    listener?.stop()
                    delay(1_000)
                    continue
                }
                val heard = listenOnce()
                if (heard == null) {
                    failures++
                    delay(if (failures < 3) 300L else 2_500L) // sem fala/erro: espera mais para não gastar bateria à toa
                    continue
                }
                failures = 0
                val match = WakeWord.match(heard, app.settings.wakeName)
                if (match != null) {
                    app.events.info("chamado", "chamado reconhecido (${heard.length} caracteres ouvidos, pedido junto=${match.rest.isNotEmpty()})")
                    onWake(match.rest)
                    delay(2_500) // dá tempo da conversa abrir antes de recomeçar
                } else {
                    delay(150)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        listener?.stop()
        listener = null
        app.events.info("chamado", "escuta do chamado desligada")
    }

    private fun canListen(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power.isInteractive && !OverlayBus.sessionActive.value && !OverlayBus.listening.value && !app.voice.speaking.value
    }

    /** Uma escuta curta; devolve o texto ouvido ou null se ninguém falou/erro. */
    private suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        val l = SpeechListener(context, events = null, muteBeep = { app.settings.muteMicBeep }).also { it.graceMs = 0L }
        listener = l
        l.start { event ->
            when (event) {
                is SpeechListener.Event.Final -> if (cont.isActive) cont.resume(event.text)
                is SpeechListener.Event.Failed -> if (cont.isActive) cont.resume(null)
                else -> Unit
            }
        }
        cont.invokeOnCancellation { l.stop() }
    }
}
