package com.joctaeng.jarvis.session

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

data class MicResult(val ok: Boolean, val peak: Int, val error: String? = null) {
    /** O Android entrega silêncio absoluto quando bloqueia o microfone de apps em segundo plano. */
    val silenced: Boolean get() = ok && peak == 0
}

data class CameraResult(val ok: Boolean, val resolution: String? = null, val lens: String? = null, val error: String? = null)

/** Abre o microfone por ~1 s e mede o pico de amplitude. */
object MicProbe {
    private const val SAMPLE_RATE = 16_000

    @SuppressLint("MissingPermission") // Chamado só depois de RECORD_AUDIO concedida.
    suspend fun run(durationMillis: Long = 1_000): MicResult = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext MicResult(false, 0, "getMinBufferSize=$minBuffer")
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, SAMPLE_RATE),
            )
        } catch (e: Exception) {
            return@withContext MicResult(false, 0, e.message)
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext MicResult(false, 0, "AudioRecord não inicializou")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext MicResult(false, 0, "Gravação recusada pelo sistema")
            }
            val buffer = ShortArray(1_600)
            var peak = 0
            var totalRead = 0
            val end = System.currentTimeMillis() + durationMillis
            while (System.currentTimeMillis() < end) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) return@withContext MicResult(false, peak, "read=$n")
                for (i in 0 until n) peak = max(peak, abs(buffer[i].toInt()))
                totalRead += n
            }
            MicResult(totalRead > 0, peak)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

/** Liga a câmera frontal (ou traseira, se não houver), espera um quadro e desliga. */
object CameraProbe {
    suspend fun run(activity: ComponentActivity, timeoutMillis: Long = 4_000): CameraResult {
        val provider = try {
            awaitProvider(activity)
        } catch (e: Exception) {
            return CameraResult(false, error = e.message)
        }
        val front = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val executor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val firstFrame = CompletableDeferred<String>()
        analysis.setAnalyzer(executor) { image ->
            firstFrame.complete("${image.width}x${image.height}")
            image.close()
        }
        return try {
            provider.bindToLifecycle(activity, selector, analysis)
            val resolution = withTimeoutOrNull(timeoutMillis) { firstFrame.await() }
            if (resolution != null) {
                CameraResult(true, resolution, if (front) "frontal" else "traseira")
            } else {
                CameraResult(false, lens = if (front) "frontal" else "traseira", error = "Nenhum quadro em ${timeoutMillis} ms")
            }
        } catch (e: Exception) {
            CameraResult(false, error = e.message)
        } finally {
            provider.unbind(analysis)
            executor.shutdown()
        }
    }

    private suspend fun awaitProvider(activity: ComponentActivity): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(activity),
            )
        }
}
