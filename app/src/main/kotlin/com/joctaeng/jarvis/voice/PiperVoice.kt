package com.joctaeng.jarvis.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    private val lock = Mutex()
    private var tts: OfflineTts? = null
    private var loadedId: String? = null

    private fun dir(o: Option) = File(root, o.folder)
    private fun archive(o: Option) = File(root, "${o.folder}.tar.bz2")
    private fun model(o: Option) = File(dir(o), "pt_BR-${o.id}-medium.onnx")

    fun installed(o: Option): Boolean = model(o).isFile && File(dir(o), "tokens.txt").isFile
    fun anyInstalled(): Option? = VOICES.firstOrNull { installed(it) }

    // Download feito pelo próprio app (o DownloadManager do Android deixava o pedido "na fila" sem sair do 0 MB).
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val progress = java.util.concurrent.ConcurrentHashMap<String, KokoroVoice.State>()

    fun startDownload(o: Option) {
        if (jobs[o.id]?.isActive == true) return
        progress[o.id] = KokoroVoice.State.Downloading(0, 0, "conectando")
        log("download da voz ${o.id} iniciado")
        jobs[o.id] = scope.launch { download(o) }
    }

    private suspend fun download(o: Option) {
        root.mkdirs()
        val part = File(root, "${o.folder}.part")
        try {
            val conn = (java.net.URL(o.url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Euno")
            }
            val code = conn.responseCode
            if (code !in 200..299) error("o servidor respondeu HTTP $code")
            val total = conn.contentLengthLong
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            progress[o.id] = KokoroVoice.State.Downloading(done, total, "")
                        }
                    }
                }
            }
            conn.disconnect()
            progress[o.id] = KokoroVoice.State.Extracting
            note(o, "baixado (${done shr 20} MB); conferindo e preparando")
            val file = archive(o)
            file.delete()
            if (!part.renameTo(file)) error("não consegui salvar o arquivo")
            if (sha256(file) != o.sha256) {
                file.delete()
                error("arquivo baixado não confere (SHA-256); baixe de novo")
            }
            try {
                extract(file)
            } catch (e: Exception) {
                dir(o).deleteRecursively()
                error("não consegui descompactar: ${e.message}")
            } finally {
                file.delete()
            }
            progress[o.id] = KokoroVoice.State.Ready
            note(o, "pronta")
        } catch (e: kotlinx.coroutines.CancellationException) {
            part.delete()
            progress.remove(o.id)
            throw e
        } catch (e: Exception) {
            part.delete()
            val why = e.message ?: e.javaClass.simpleName
            progress[o.id] = KokoroVoice.State.Failed("$why. Confira a internet e tente de novo.")
            note(o, "falhou: $why")
        }
    }

    private val lastStatus = mutableMapOf<String, String>()

    private fun log(text: String) = runCatching { com.joctaeng.jarvis.JarvisApp.from(appContext).events.info("voz", "Piper: $text") }

    /** Registra só quando o estado muda (para o relatório mostrar o que aconteceu). */
    private fun note(o: Option, status: String) {
        if (lastStatus[o.id] == status) return
        lastStatus[o.id] = status
        log("${o.id}: $status")
    }

    /** Cancela um download em andamento (o usuário pode tentar de novo depois). */
    fun cancel(o: Option) {
        jobs.remove(o.id)?.cancel()
        progress.remove(o.id)
        File(root, "${o.folder}.part").delete()
        archive(o).delete()
        note(o, "cancelado")
    }

    fun delete(o: Option) {
        if (loadedId == o.id) release()
        dir(o).deleteRecursively()
        progress.remove(o.id)
    }

    /** Situação da voz: pronta, baixando (com bytes), preparando ou falhou. */
    suspend fun poll(o: Option): KokoroVoice.State = withContext(Dispatchers.IO) {
        if (installed(o)) KokoroVoice.State.Ready else progress[o.id] ?: KokoroVoice.State.NotInstalled
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
