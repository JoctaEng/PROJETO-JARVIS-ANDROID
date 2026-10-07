package com.joctaeng.jarvis.poc

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.getSystemService
import java.io.File

/**
 * Baixa o modelo recomendado da IA do celular pelo DownloadManager do Android: continua com o app fechado,
 * retoma se a rede cair e só usa Wi-Fi. O arquivo chega como `.download` e só vira `.gguf` quando termina.
 */
class ModelDownload(context: Context, private val store: ModelStore) {
    private val appContext = context.applicationContext
    private val manager = requireNotNull(appContext.getSystemService<DownloadManager>())
    private val prefs = appContext.getSharedPreferences("model_download", Context.MODE_PRIVATE)

    sealed interface State {
        data object Idle : State
        data class Running(val downloadedBytes: Long, val totalBytes: Long, val waitingReason: String?) : State
        data class Done(val file: File) : State
        data class Failed(val reason: String) : State
    }

    val target: File get() = File(store.directory, RECOMMENDED_FILE)
    private val partial: File get() = File(store.directory, "$RECOMMENDED_FILE.download")

    fun start() {
        if (current() is State.Running) return
        partial.delete()
        val request = DownloadManager.Request(Uri.parse(RECOMMENDED_URL))
            .setTitle("Euno: IA do celular (Qwen3-4B)")
            .setDescription("Cerca de 2,4 GB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(appContext, null, "${store.directory.name}/${partial.name}")
        prefs.edit().putLong(KEY_ID, manager.enqueue(request)).apply()
    }

    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1)
        if (id >= 0) manager.remove(id)
        prefs.edit().remove(KEY_ID).apply()
        partial.delete()
    }

    /** Consulta o andamento; ao terminar, troca `.download` por `.gguf`. */
    fun current(): State {
        val id = prefs.getLong(KEY_ID, -1)
        if (id < 0) return if (target.isFile) State.Done(target) else State.Idle
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                prefs.edit().remove(KEY_ID).apply()
                return State.Idle
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(KEY_ID).apply()
                    when {
                        total > 0 && partial.length() != total -> {
                            val got = partial.length()
                            partial.delete()
                            State.Failed("arquivo incompleto (${got shr 20} de ${total shr 20} MB)")
                        }
                        partial.renameTo(target) -> State.Done(target)
                        else -> State.Failed("não consegui salvar o arquivo")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(KEY_ID).apply()
                    manager.remove(id)
                    State.Failed(failureText(reason))
                }
                DownloadManager.STATUS_PAUSED -> State.Running(done, total, pauseText(reason))
                else -> State.Running(done, total, null)
            }
        }
    }

    private fun pauseText(reason: Int) = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "aguardando Wi-Fi"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "sem internet; continua quando voltar"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "falha de rede; tentando de novo"
        else -> "pausado"
    }

    private fun failureText(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "sem espaço livre (precisa de ~2,5 GB)"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "a conexão caiu e não deu para retomar; tente de novo"
        in 400..599 -> "o servidor respondeu erro $reason"
        else -> "erro $reason"
    }

    companion object {
        /** Repositório oficial da Qwen (Apache 2.0), na mesma revisão usada pelo EduMath. */
        const val RECOMMENDED_URL =
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/a9a60d009fa7ff9606305047c2bf77ac25dbec49/Qwen3-4B-Q4_K_M.gguf"
        const val RECOMMENDED_FILE = "Qwen3-4B-Q4_K_M.gguf"
        private const val KEY_ID = "id"
    }
}
