package com.joctaeng.jarvis.poc

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
 * Baixa o modelo recomendado da IA do celular. Desde a 0.28.1 (teste da 0.28.0: o gerenciador de downloads do
 * Android ficava parado em "0 de 2600 MB" sem dizer o motivo) o próprio Euno baixa, num serviço em primeiro plano:
 * continua com o app fechado, retoma de onde parou se a rede cair, espera o Wi-Fi e mostra o erro exato do servidor
 * (ex.: 401/403 = o Hugging Face pede login → token). O arquivo chega como `.download` e só ganha o nome final no fim.
 * Recomendado: Gemma 4 E2B no LiteRT-LM (placa de vídeo), pedido 78.
 */
class ModelDownload(context: Context, private val store: ModelStore) {
    private val appContext = context.applicationContext

    sealed interface State {
        data object Idle : State
        data class Running(val downloadedBytes: Long, val totalBytes: Long, val waitingReason: String?) : State
        data class Done(val file: File) : State
        data class Failed(val reason: String) : State
    }

    val target: File get() = File(store.directory, RECOMMENDED_FILE)
    private val partial: File get() = File(store.directory, "$RECOMMENDED_FILE.download")

    @Volatile private var state: State = State.Idle
    @Volatile private var job: Job? = null

    init {
        // O pedido antigo, preso no gerenciador de downloads do Android (0.28.0), é cancelado para não ficar ocupando a fila.
        runCatching {
            val prefs = appContext.getSharedPreferences("model_download", Context.MODE_PRIVATE)
            val old = prefs.getLong("id", -1)
            if (old >= 0) {
                appContext.getSystemService(android.app.DownloadManager::class.java)?.remove(old)
                prefs.edit().remove("id").apply()
            }
        }
    }

    fun start() {
        if (state is State.Running) return
        state = State.Running(partial.length(), 0, "começando…")
        ContextCompat.startForegroundService(appContext, Intent(appContext, ModelDownloadService::class.java))
    }

    fun cancel() {
        job?.cancel()
        job = null
        appContext.stopService(Intent(appContext, ModelDownloadService::class.java))
        partial.delete()
        state = State.Idle
    }

    fun current(): State {
        val s = state
        return if (s !is State.Running && target.isFile) State.Done(target) else s
    }

    /** Roda dentro do serviço. [progress] atualiza a notificação. */
    internal suspend fun run(progress: (State) -> Unit) {
        val events = JarvisApp.from(appContext).events
        var attempts = 0
        while (coroutineContext.isActive) {
            // Só no Wi-Fi (o modelo tem 2,6 GB): espera em vez de falhar.
            if (isMetered()) {
                state = State.Running(partial.length(), 0, "aguardando Wi-Fi").also(progress)
                delay(5_000)
                continue
            }
            val result = runCatching { attempt(progress) }
            val error = result.exceptionOrNull()
            if (error == null) {
                var done = result.getOrNull()!!
                // Tamanho certo não basta: confere o SHA-256 (um arquivo danificado faz o motor dizer só "Engine is not initialized").
                if (done is State.Done) {
                    state = State.Running(done.file.length(), done.file.length(), "conferindo o arquivo…").also(progress)
                    val verdict = runCatching { ModelIntegrity.verify(done.file) }.getOrNull()
                    if (verdict is ModelIntegrity.Verdict.Damaged) {
                        done.file.delete()
                        done = State.Failed(ModelIntegrity.describe(verdict, done.file.name))
                    }
                }
                state = done
                progress(done)
                events.info("modelo", "download: ${if (done is State.Done) "concluído (${done.file.length() shr 20} MB)" else (done as? State.Failed)?.reason}")
                return
            }
            if (error is kotlinx.coroutines.CancellationException) throw error
            attempts++
            events.warn("modelo", "download: falha de rede (${error.javaClass.simpleName}: ${error.message}); tentativa $attempts")
            if (attempts >= 8) {
                state = State.Failed("a conexão caiu várias vezes (${error.message}); toque em Baixar de novo: continua de onde parou").also(progress)
                return
            }
            state = State.Running(partial.length(), 0, "falha de rede; tentando de novo").also(progress)
            delay((attempts * 5_000L).coerceAtMost(30_000L))
        }
    }

    /** Uma tentativa: devolve Done/Failed; exceção = falha de rede (tenta de novo, retomando). */
    private suspend fun attempt(progress: (State) -> Unit): State {
        store.directory.mkdirs()
        val have = partial.takeIf { it.isFile }?.length() ?: 0L
        val conn = (URL(RECOMMENDED_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Euno-Android")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
            JarvisApp.from(appContext).secrets.get(HF_TOKEN)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer ${it.trim()}") }
        }
        try {
            val code = conn.responseCode
            when (code) {
                401, 403 -> return State.Failed(
                    "o Hugging Face pediu login ($code). Abra a página do modelo no navegador, entre na sua conta, aceite a licença " +
                        "e cole um token de leitura (huggingface.co/settings/tokens) no campo abaixo; ou baixe pelo navegador e use Importar.",
                )
                404 -> return State.Failed("o arquivo não está mais nesse endereço (404); me avise para eu atualizar o link")
                416 -> if (have > 0) return finish(have, have)
            }
            if (code !in 200..299) return State.Failed("o servidor respondeu erro $code")
            val resumed = code == 206 && have > 0
            val length = conn.getHeaderFieldLong("Content-Length", -1L)
            val total = if (length > 0) length + (if (resumed) have else 0L) else -1L
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
        /** Repositório oficial do LiteRT-LM (Google, Gemma 4 com licença Apache 2.0); arquivo genérico (CPU e GPU), ~2,59 GB. */
        const val RECOMMENDED_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val RECOMMENDED_FILE = "gemma-4-E2B-it.litertlm"
        const val RECOMMENDED_NAME = "Gemma 4 E2B"
        const val MODEL_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        /** Token de leitura do Hugging Face (só se o site pedir login), guardado criptografado no celular. */
        const val HF_TOKEN = "hf_token"
    }
}

/** Serviço em primeiro plano do download do modelo (notificação com o andamento; pode fechar o app). */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Download da IA do celular", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification("Começando o download…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val download = JarvisApp.from(this).modelDownload
        val job = scope.launch {
            try {
                download.run { s ->
                    val text = when (s) {
                        is ModelDownload.State.Running ->
                            "${s.downloadedBytes shr 20} de ${if (s.totalBytes > 0) s.totalBytes shr 20 else 2600} MB" + (s.waitingReason?.let { " — $it" } ?: "")
                        is ModelDownload.State.Done -> "Pronto: ${ModelDownload.RECOMMENDED_NAME}"
                        is ModelDownload.State.Failed -> "Falhou: ${s.reason}"
                        ModelDownload.State.Idle -> ""
                    }
                    val pct = (s as? ModelDownload.State.Running)?.let { if (it.totalBytes > 0) (it.downloadedBytes * 100 / it.totalBytes).toInt() else 0 } ?: 100
                    runCatching { nm.notify(ID, notification(text, pct, if (s is ModelDownload.State.Running) 100 else 0)) }
                }
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        download.attach(job)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun notification(text: String, progress: Int, max: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Euno: baixando ${ModelDownload.RECOMMENDED_NAME}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(max > 0)
            .setProgress(max, progress, max > 0 && progress == 0)
            .build()

    private companion object {
        const val CHANNEL = "euno_modelo"
        const val ID = 4207
    }
}
