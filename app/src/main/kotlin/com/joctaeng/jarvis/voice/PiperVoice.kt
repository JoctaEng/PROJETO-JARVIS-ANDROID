package com.joctaeng.jarvis.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Voz OFFLINE leve: Piper pt-BR (VITS) pelo sherpa-onnx que o app já usa para o Kokoro. ~67 MB por voz, baixada sob demanda.
 * Pacotes oficiais do sherpa-onnx (releases "tts-models"), conferidos por SHA-256. Base de dados das vozes: CC0.
 */
class PiperVoice(context: Context) {
    data class Option(val id: String, val label: String, val sha256: String) {
        val folder get() = "vits-piper-pt_BR-$id-medium"
        val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$folder.tar.bz2"
    }

    private val appContext = context.applicationContext
    private val root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "voz")
    private val prefs = appContext.getSharedPreferences("piper", Context.MODE_PRIVATE)
    private val manager = appContext.getSystemService(DownloadManager::class.java)
    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private var loadedId: String? = null

    private fun dir(o: Option) = File(root, o.folder)
    private fun archive(o: Option) = File(root, "${o.folder}.tar.bz2")
    private fun model(o: Option) = File(dir(o), "pt_BR-${o.id}-medium.onnx")

    fun installed(o: Option): Boolean = model(o).isFile && File(dir(o), "tokens.txt").isFile
    fun anyInstalled(): Option? = VOICES.firstOrNull { installed(it) }

    fun startDownload(o: Option) {
        root.mkdirs()
        archive(o).delete()
        val request = DownloadManager.Request(Uri.parse(o.url))
            .setTitle("Euno: voz offline Piper (${o.label})")
            .setDescription("Cerca de 67 MB")
            // Algumas redes Wi-Fi são vistas pelo Android como "limitadas" e o download ficava parado em 0 MB.
            .setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, null, "voz/${archive(o).name}")
        prefs.edit().putLong("id_${o.id}", manager.enqueue(request)).apply()
        log("download da voz ${o.id} iniciado")
    }

    private val lastStatus = mutableMapOf<String, String>()

    private fun log(text: String) = runCatching { com.joctaeng.jarvis.JarvisApp.from(appContext).events.info("voz", "Piper: $text") }

    /** Registra só quando o estado muda (para o relatório mostrar por que parou). */
    private fun note(o: Option, status: String) {
        if (lastStatus[o.id] == status) return
        lastStatus[o.id] = status
        log("${o.id}: $status")
    }

    private fun pausedReason(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "esperando Wi-Fi (o Android acha que a rede é limitada)"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "esperando internet"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "falha de rede; o Android vai tentar de novo"
        else -> "pausado pelo Android (motivo $reason)"
    }

    /** Cancela um download em andamento (o usuário pode tentar de novo depois). */
    fun cancel(o: Option) {
        prefs.getLong("id_${o.id}", -1).takeIf { it >= 0 }?.let { manager.remove(it) }
        prefs.edit().remove("id_${o.id}").apply()
        archive(o).delete()
        note(o, "cancelado")
    }

    fun delete(o: Option) {
        if (loadedId == o.id) release()
        dir(o).deleteRecursively()
    }

    /** Consulta o download; ao terminar confere o SHA-256 e descompacta (chamar fora da thread principal). */
    suspend fun poll(o: Option): KokoroVoice.State = withContext(Dispatchers.IO) {
        if (installed(o)) return@withContext KokoroVoice.State.Ready
        val id = prefs.getLong("id_${o.id}", -1)
        if (id < 0) return@withContext KokoroVoice.State.NotInstalled
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                prefs.edit().remove("id_${o.id}").apply()
                return@withContext KokoroVoice.State.NotInstalled
            }
            when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    note(o, "baixado; conferindo e preparando")
                    prefs.edit().remove("id_${o.id}").apply()
                    val file = archive(o)
                    if (sha256(file) != o.sha256) {
                        file.delete()
                        note(o, "arquivo não confere (SHA-256)")
                        return@withContext KokoroVoice.State.Failed("arquivo baixado não confere (SHA-256); baixe de novo")
                    }
                    runCatching { extract(file) }.fold(
                        { KokoroVoice.State.Ready },
                        { e -> dir(o).deleteRecursively(); KokoroVoice.State.Failed("não consegui descompactar: ${e.message}") },
                    ).also { file.delete() }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    note(o, "falhou (código $reason)")
                    prefs.edit().remove("id_${o.id}").apply()
                    manager.remove(id)
                    KokoroVoice.State.Failed("o download falhou (código $reason); tente de novo no Wi-Fi")
                }
                else -> {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val text = when (status) {
                        DownloadManager.STATUS_PENDING -> "na fila do Android"
                        DownloadManager.STATUS_PAUSED -> pausedReason(reason)
                        else -> ""
                    }
                    note(o, if (text.isEmpty()) "baixando" else text)
                    KokoroVoice.State.Downloading(bytes, c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), text)
                }
            }
        }
    }

    private fun extract(file: File) {
        val base = root.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(file.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(root, entry.name)
                require(out.canonicalPath.startsWith(base)) { "caminho inválido no arquivo: ${entry.name}" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    suspend fun synthesize(text: String, o: Option, speed: Float = 1.0f): GeminiSpeech.Clip = lock.withLock {
        withContext(Dispatchers.Default) {
            if (loadedId != o.id) release()
            val engine = tts ?: OfflineTts(
                null,
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model(o).path,
                            tokens = File(dir(o), "tokens.txt").path,
                            dataDir = File(dir(o), "espeak-ng-data").path,
                        ),
                        numThreads = 4,
                    ),
                    maxNumSentences = 1,
                ),
            ).also { tts = it; loadedId = o.id }
            val audio = engine.generate(text, 0, speed)
            val pcm = ByteArray(audio.samples.size * 2)
            audio.samples.forEachIndexed { i, f ->
                val v = (f.coerceIn(-1f, 1f) * 32767).toInt()
                pcm[2 * i] = (v and 0xff).toByte()
                pcm[2 * i + 1] = (v shr 8 and 0xff).toByte()
            }
            GeminiSpeech.Clip(pcm, audio.sampleRate, "piper")
        }
    }

    fun release() {
        tts?.free()
        tts = null
        loadedId = null
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val VOICES = listOf(
            Option("faber", "Faber (masculina)", "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83"),
            Option("cadu", "Cadu (masculina)", "aba78157d4b89acc17ddef15a70f4b2474f4c189d4de6035002ac7fec9d5d303"),
            Option("jeff", "Jeff (masculina)", "da4c870fc7b20600c74261b3e1dd1c816da2ce063e18c46e1f2cd86dea88f3f0"),
        )

        fun byId(id: String): Option? = VOICES.firstOrNull { it.id == id }
    }
}
