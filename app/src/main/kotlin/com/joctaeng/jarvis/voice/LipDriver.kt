package com.joctaeng.jarvis.voice

import android.os.SystemClock
import com.joctaeng.jarvis.presence.expression.LipTimeline
import com.joctaeng.jarvis.presence.expression.MouthPose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sincronia labial: segue a posição real do áudio que está tocando (~30 vezes por segundo) e publica a boca
 * ([MouthPose]) de cada instante. O personagem só desenha o que chega aqui.
 */
class LipDriver(private val scope: CoroutineScope) {
    private val _pose = MutableStateFlow(MouthPose.REST)
    val pose: StateFlow<MouthPose> = _pose.asStateFlow()

    /** Última atualização (relógio do aparelho); sem atualização recente o personagem volta à boca automática. */
    @Volatile var updatedAt = 0L
        private set

    private var job: Job? = null

    /** @param positionMs posição atual do áudio em ms (null = terminou). */
    @Synchronized
    fun start(timeline: LipTimeline, positionMs: () -> Long?) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val p = positionMs() ?: break
                _pose.value = timeline.at(p)
                updatedAt = SystemClock.elapsedRealtime()
                if (p > timeline.durationMs + 250) break
                delay(33)
            }
            _pose.value = MouthPose.REST
        }
    }

    /** Sem áudio próprio (voz do Android): a boca segue o relógio a partir de agora. */
    fun startNow(timeline: LipTimeline) {
        val t0 = SystemClock.elapsedRealtime()
        start(timeline) { SystemClock.elapsedRealtime() - t0 }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _pose.value = MouthPose.REST
    }
}
