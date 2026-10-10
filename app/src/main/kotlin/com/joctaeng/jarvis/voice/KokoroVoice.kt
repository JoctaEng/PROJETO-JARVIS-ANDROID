package com.joctaeng.jarvis.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Voz natural OFFLINE: Kokoro-82M v1.0 (int8) pelo sherpa-onnx 1.13.8. Português do Brasil confirmado nos metadados
 * do modelo: pf_dora (42, feminina), pm_alex (43) e pm_santa (44), masculinas. Modelo de ~132 MB baixado sob demanda.
 */
class KokoroVoice(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "voz")
    private val modelDir = File(root, FOLDER)
    private val archive = File(root, "$FOLDER.tar.bz2")
    private val prefs = appContext.getSharedPreferences("kokoro", Context.MODE_PRIVATE)
    private val manager = appContext.getSystemService(DownloadManager::class.java)
    private val lock = Mutex()
    private var tts: OfflineTts? = null

    val installed: Boolean get() = File(modelDir, "model.int8.onnx").isFile && File(modelDir, "voices.bin").isFile

    sealed interface State {
        data object NotInstalled : State
        /** [note]: por que está parado, quando está (na fila, esperando Wi-Fi...). */
        data class Downloading(val bytes: Long, val total: Long, val note: String = "") : State
        data object Extracting : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    fun startDownload() {
        root.mkdirs()
        archive.delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Euno: voz offline (Kokoro)")
            .setDescription("Cerca de 130 MB")
            .setAllowedOverMetered(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, null, "voz/${archive.name}")
        prefs.edit().putLong("id", manager.enqueue(request)).apply()
    }

    fun cancel() {
        prefs.getLong("id", -1).takeIf { it >= 0 }?.let { manager.remove(it) }
        prefs.edit().remove("id").apply()
        archive.delete()
    }

    fun delete() {
        tts?.free()
        tts = null
        modelDir.deleteRecursively()
    }

    /** Consulta o download; ao terminar confere o SHA-256 e descompacta (chamar fora da thread principal). */
    suspend fun poll(): State = withContext(Dispatchers.IO) {
        if (installed) return@withContext State.Ready
        val id = prefs.getLong("id", -1)
        if (id < 0) return@withContext State.NotInstalled
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                prefs.edit().remove("id").apply()
                return@withContext State.NotInstalled
            }
            when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove("id").apply()
                    if (sha256(archive) != SHA256) {
                        archive.delete()
                        return@withContext State.Failed("arquivo baixado não confere (SHA-256); baixe de novo")
                    }
                    runCatching { extract() }.fold(
                        { State.Ready },
                        { e -> modelDir.deleteRecursively(); State.Failed("não consegui descompactar: ${e.message}") },
                    ).also { archive.delete() }
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove("id").apply()
                    manager.remove(id)
                    State.Failed("o download falhou; tente de novo no Wi-Fi")
                }
                else -> State.Downloading(
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                )
            }
        }
    }

    private fun extract() {
        val base = root.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(root, entry.name)
                // Nunca escrever fora da pasta da voz (proteção contra "../" no arquivo).
                require(out.canonicalPath.startsWith(base)) { "caminho inválido no arquivo: ${entry.name}" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    /** Gera a fala em PCM 16 bits (mesmo formato que o player do Gemini usa). */
    suspend fun synthesize(text: String, speakerId: Int, speed: Float = 1.0f): GeminiSpeech.Clip = lock.withLock {
        withContext(Dispatchers.Default) {
            val engine = tts ?: OfflineTts(
                null,
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(modelDir, "model.int8.onnx").path,
                            voices = File(modelDir, "voices.bin").path,
                            tokens = File(modelDir, "tokens.txt").path,
                            dataDir = File(modelDir, "espeak-ng-data").path,
                            lang = "pt-br",
                        ),
                        numThreads = 4,
                    ),
                    maxNumSentences = 1,
                ),
            ).also { tts = it }
            val audio = engine.generate(text, speakerId, speed)
            val pcm = ByteArray(audio.samples.size * 2)
            audio.samples.forEachIndexed { i, f ->
                val v = (f.coerceIn(-1f, 1f) * 32767).toInt()
                pcm[2 * i] = (v and 0xff).toByte()
                pcm[2 * i + 1] = (v shr 8 and 0xff).toByte()
            }
            GeminiSpeech.Clip(pcm, audio.sampleRate)
        }
    }

    fun release() {
        tts?.free()
        tts = null
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
        const val FOLDER = "kokoro-int8-multi-lang-v1_0"
        const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$FOLDER.tar.bz2"
        const val SHA256 = "4c3052abaa60943a341f193888cf6abd68787dae6ab8ae5c925a706caa247e4e"

        /** Vozes pt-BR do Kokoro v1.0 (id no voices.bin). */
        val VOICES = linkedMapOf(42 to "Dora (feminina)", 43 to "Alex (masculina)", 44 to "Santa (masculina)")

        fun defaultSpeakerFor(characterId: String, female: Boolean): Int = when {
            female || characterId == "astra" -> 42
            characterId == "thor" || characterId == "rex" || characterId.startsWith("jocta") -> 44
            else -> 43
        }
    }
}
