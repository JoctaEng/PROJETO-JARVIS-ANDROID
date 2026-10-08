package com.joctaeng.jarvis.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voz natural do Gemini (modelos TTS). Duas formas de pedir, tentadas em ordem (a que funcionar fica como preferida):
 *  - generateContent: POST /v1beta/models/{modelo}:generateContent com responseModalities=[AUDIO] e voz pré-configurada;
 *    o áudio vem em candidates[].content.parts[].inlineData.data (PCM 16 bits, 24 kHz).
 *  - interactions: POST /v1beta/interactions (formato do SDK google-genai).
 * O áudio é procurado em qualquer lugar da resposta (texto base64 longo); se não achar, o erro traz o "esqueleto" da
 * resposta (nomes dos campos, sem o áudio) para o relatório mostrar o formato real. store=false na forma interactions.
 */
class GeminiSpeech(private val apiKey: String, private val model: String = DEFAULT_MODEL) {

    /** Áudio PCM 16 bits mono pronto para tocar. [via] diz qual forma de pedido funcionou (diagnóstico). */
    class Clip(val pcm: ByteArray, val sampleRate: Int, val via: String = "")

    private enum class Mode(val label: String) { GENERATE("generateContent"), INTERACTIONS("interactions") }

    suspend fun synthesize(text: String, voice: String, language: String = "pt-BR"): Clip = withContext(Dispatchers.IO) {
        val order = if (preferInteractions) listOf(Mode.INTERACTIONS, Mode.GENERATE) else listOf(Mode.GENERATE, Mode.INTERACTIONS)
        val problems = mutableListOf<String>()
        for (mode in order) {
            try {
                val clip = call(mode, text, voice, language)
                preferInteractions = mode == Mode.INTERACTIONS
                return@withContext clip
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "${mode.label}: ${e.message ?: e::class.simpleName}"
            }
        }
        throw IOException(problems.joinToString(" | ").take(1_200))
    }

    private fun call(mode: Mode, text: String, voice: String, language: String): Clip {
        val (url, body) = when (mode) {
            Mode.GENERATE -> "$BASE/models/$model:generateContent" to JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))),
                        ),
                )
            Mode.INTERACTIONS -> "$BASE/interactions" to JSONObject()
                .put("model", model)
                .put("input", text)
                .put("store", false)
                .put("response_format", JSONObject().put("type", "audio"))
                .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice).put("language", language))))
        }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 12_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status ${c.errorStream?.bufferedReader()?.readText()?.take(300)}")
            }
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val found = findAudioData(json) ?: throw IOException("HTTP $status sem áudio; resposta=${skeleton(json).take(600)}")
            val bytes = try {
                Base64.decode(found.data, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Base64.decode(found.data, Base64.URL_SAFE)
            }
            return toClip(bytes, found.rate ?: 24_000).let { Clip(it.pcm, it.sampleRate, mode.label) }
        } finally {
            c.disconnect()
        }
    }

    private class Found(val data: String, val rate: Int?)

    /** Procura em qualquer lugar da resposta um texto base64 longo (o áudio) e, ao lado dele, a taxa de amostragem. */
    private fun findAudioData(node: Any?): Found? = when (node) {
        is JSONObject -> {
            val keys = node.keys().asSequence().toList()
            val big = keys.firstOrNull { ((node.opt(it) as? String)?.length ?: 0) > 2_000 }
            if (big != null) {
                val mime = node.optString("mime_type").ifBlank { node.optString("mimeType") }
                val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull()
                    ?: node.optInt("sample_rate", 0).takeIf { it > 0 }
                Found(node.getString(big), rate)
            } else {
                keys.firstNotNullOfOrNull { findAudioData(node.opt(it)) }
            }
        }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findAudioData(node.opt(it)) }
        else -> null
    }

    /** Estrutura da resposta sem o conteúdo longo (texto/áudio viram "<N car.>"), para diagnóstico. */
    private fun skeleton(node: Any?, depth: Int = 0): String = when {
        depth > 5 -> "…"
        node is JSONObject -> node.keys().asSequence().joinToString(",", "{", "}") { "$it:" + skeleton(node.opt(it), depth + 1) }
        node is JSONArray -> "[" + (0 until minOf(node.length(), 3)).joinToString(",") { skeleton(node.opt(it), depth + 1) } + (if (node.length() > 3) ",…" else "") + "]"
        node is String -> if (node.length > 60) "\"<${node.length} car.>\"" else "\"$node\""
        else -> node.toString()
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        @Volatile private var preferInteractions = false
        const val DEFAULT_MODEL = "gemini-3.8-flash-tts"

        /** Vozes citadas no guia oficial; cada personagem tem a sua (pode trocar em Meu Euno). */
        val VOICES = listOf("Kore", "Aoede", "Puck", "Charon", "Fenrir", "Enceladus")

        fun defaultVoiceFor(characterId: String): String = when (characterId) {
            "luna", "selene", "astra" -> "Aoede"
            "nina", "maya" -> "Kore"
            "thor" -> "Fenrir"
            "rex" -> "Charon"
            "jocta_estrategista" -> "Enceladus"
            else -> "Puck"
        }

        /** WAV (com cabeçalho RIFF) ou PCM cru → PCM 16 bits. */
        fun toClip(bytes: ByteArray, fallbackRate: Int): Clip {
            if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF") return Clip(bytes, fallbackRate)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var rate = fallbackRate
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buf.getInt(pos + 4)
                if (id == "fmt ") rate = buf.getInt(pos + 12)
                if (id == "data") {
                    val end = minOf(bytes.size, pos + 8 + size)
                    return Clip(bytes.copyOfRange(pos + 8, end), rate)
                }
                pos += 8 + size + (size and 1)
            }
            return Clip(bytes.copyOfRange(44.coerceAtMost(bytes.size), bytes.size), rate)
        }
    }
}

/** Toca PCM 16 bits mono e só retorna quando terminar (ou quando [stopped] virar true). */
object PcmPlayer {
    fun play(clip: GeminiSpeech.Clip, stopped: () -> Boolean) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(clip.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
            .build()
        try {
            track.play()
            var offset = 0
            val step = clip.sampleRate / 5 * 2 // ~200 ms por bloco, para parar rápido
            while (offset < clip.pcm.size && !stopped()) {
                val n = track.write(clip.pcm, offset, minOf(step, clip.pcm.size - offset))
                if (n <= 0) break
                offset += n
            }
            if (!stopped()) {
                val totalFrames = clip.pcm.size / 2
                while (!stopped() && track.playbackHeadPosition < totalFrames) Thread.sleep(20)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
