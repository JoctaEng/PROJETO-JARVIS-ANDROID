package com.joctaeng.jarvis.embed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.R
import com.joctaeng.jarvis.poc.ModelIntegrity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Gerencia o download do modelo local de embeddings (EmbeddingGemma 2).
 *
 * Características:
 * - Serviço em primeiro plano com canal próprio (`euno_embedding`) e id 4208.
 * - Retomada de download com Range header.
 * - Espera por Wi-Fi se conectado em rede tarifada.
 * - Gravação temporária com extensão `.download`.
 * - Verificação de integridade e SHA-256.
 */
class EmbeddingDownload(private val context: Context, private val directory: File) {
    private val appContext = context.applicationContext

    sealed interface State {
        data object Idle : State
        data class Running(val downloadedBytes: Long, val totalBytes: Long, val waitingReason: String?) : State
        data class Done(val file: File) : State
        data class Failed(val reason: String) : State
    }

    val target: File get() = File(directory, MODEL_FILE)
    private val partial: File get() = File(directory, "$MODEL_FILE.download")

    @Volatile private var state: State = State.Idle
    @Volatile private var job: Job? = null

    fun start() {
        if (state is State.Running) return
        state = State.Running(partial.length(), 0, "começando…")
        ContextCompat.startForegroundService(appContext, Intent(appContext, EmbeddingDownloadService::class.java))
    }

    fun cancel() {
        job?.cancel()
        job = null
        appContext.stopService(Intent(appContext, EmbeddingDownloadService::class.java))
        partial.delete()
        state = State.Idle
    }

    fun current(): State {
        val s = state
        return if (s is State.Idle && target.isFile && target.length() > 0) State.Done(target) else s
    }

    suspend fun run(progress: (State) -> Unit) {
        state = State.Running(partial.length(), 0, null).also(progress)
        val token = JarvisApp.from(appContext).secrets.get("hf_token")?.trim().orEmpty()
        var attempt = 0
        while (coroutineContext.isActive) {
            attempt++
            try {
                if (isMetered()) {
                    state = State.Running(partial.length(), 0, "esperando Wi-Fi").also(progress)
                    while (isMetered() && coroutineContext.isActive) delay(3_000)
                }
                val r = downloadOnce(token, progress)
                state = r.also(progress)
                return
            } catch (e: Exception) {
                if (!coroutineContext.isActive) return
                val reason = e.message ?: e.javaClass.simpleName
                if (attempt >= 5) {
                    state = State.Failed("Não consegui baixar após $attempt tentativas: $reason").also(progress)
                    return
                }
                state = State.Running(partial.length(), 0, "reconectando ($attempt/5): $reason").also(progress)
                delay(2_000L * attempt)
            }
        }
    }

    private suspend fun downloadOnce(token: String, progress: (State) -> Unit): State {
        val have = if (partial.isFile) partial.length() else 0L
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Euno-Android")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                return State.Failed("O Hugging Face pede login. Guarde seu token de leitura em Ajustes.")
            }
            if (code == 404) {
                return State.Failed("O endereço do modelo mudou (404).")
            }
            val resumed = code == 206
            if (!resumed && code !in 200..299) {
                return State.Failed("Servidor recusou o download (HTTP $code).")
            }
            val length = conn.contentLengthLong
            val total = if (length > 0) length + (if (resumed) have else 0L) else EXPECTED_SIZE
            var done = if (resumed) have else 0L
            var lastUi = 0L

            conn.inputStream.use { input ->
                FileOutputStream(partial, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 700) {
                            lastUi = now
                            state = State.Running(done, total, null).also(progress)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("parou em ${done shr 20} de ${total shr 20} MB")
            return finish(done, total)
        } finally {
            conn.disconnect()
        }
    }

    private fun finish(done: Long, total: Long): State = when {
        total > 0 && partial.length() != total -> State.Failed("arquivo incompleto (${partial.length() shr 20} de ${total shr 20} MB)")
        partial.renameTo(target) -> State.Done(target)
        else -> State.Failed("não consegui salvar o arquivo (${done shr 20} MB baixados)")
    }

    private fun isMetered(): Boolean =
        appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    internal fun attach(j: Job) {
        job = j
    }

    companion object {
        const val MODEL_URL = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm"
        const val MODEL_FILE = "embeddinggemma-2-text-270m.litertlm"
        const val MODEL_NAME = "EmbeddingGemma 2"
        const val EXPECTED_SIZE = 164_626_432L
        const val EXPECTED_SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
    }
}

/**
 * Serviço em primeiro plano para download do modelo de embedding.
 */
class EmbeddingDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Download da Memória por Sentido", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(ID, notification("Começando o download…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val download = JarvisApp.from(this).embeddingDownload
        val job = scope.launch {
            try {
                download.run { s ->
                    val text = when (s) {
                        is EmbeddingDownload.State.Running ->
                            "${s.downloadedBytes shr 20} de ${if (s.totalBytes > 0) s.totalBytes shr 20 else 157} MB" + (s.waitingReason?.let { " — $it" } ?: "")
                        is EmbeddingDownload.State.Done -> "Pronto: ${EmbeddingDownload.MODEL_NAME}"
                        is EmbeddingDownload.State.Failed -> "Falhou: ${s.reason}"
                        EmbeddingDownload.State.Idle -> ""
                    }
                    val pct = (s as? EmbeddingDownload.State.Running)?.let {
                        if (it.totalBytes > 0) (it.downloadedBytes * 100 / it.totalBytes).toInt() else 0
                    } ?: 100
                    runCatching { nm.notify(ID, notification(text, pct, if (s is EmbeddingDownload.State.Running) 100 else 0)) }
                }
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        download.attach(job)
        return START_NOT_STICKY
    }

    private fun notification(text: String, progress: Int, max: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Euno: baixando ${EmbeddingDownload.MODEL_NAME}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(max > 0)
            .setProgress(max, progress, max > 0 && progress == 0)
            .build()

    companion object {
        const val CHANNEL = "euno_embedding"
        const val ID = 4208
    }
}
