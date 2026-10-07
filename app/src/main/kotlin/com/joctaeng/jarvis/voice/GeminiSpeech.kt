package com.joctaeng.jarvis.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
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
 * Voz natural do Gemini (Gemini 3.8 TTS, API Interactions). Formato conferido no SDK oficial google-genai 2.28:
 * POST /v1beta/interactions, chave em x-goog-api-key, speech_config [{voice, language}], áudio em output_audio
 * (WAV 24 kHz mono 16 bits). store=false: o Google não guarda o pedido.
 */
class GeminiSpeech(private val apiKey: String, private val model: String = DEFAULT_MODEL) {

    /** Áudio PCM 16 bits mono pronto para tocar. */
    class Clip(val pcm: ByteArray, val sampleRate: Int)

    suspend fun synthesize(text: String, voice: String, language: String = "pt-BR"): Clip = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("store", false)
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice).put("language", language))))
        val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            if (status !in 200..299) {
                throw IOException("voz do Gemini respondeu $status: ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
            }
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val audio = json.optJSONObject("output_audio") ?: findAudio(json) ?: throw IOException("resposta sem áudio")
            val bytes = Base64.decode(audio.getString("data"), Base64.DEFAULT)
            toClip(bytes, audio.optInt("sample_rate", 24_000))
        } finally {
            c.disconnect()
        }
    }

    private fun findAudio(json: JSONObject): JSONObject? {
        val outputs = json.optJSONArray("outputs") ?: return null
        for (i in 0 until outputs.length()) {
            val o = outputs.optJSONObject(i) ?: continue
            if (o.optString("type") == "audio" && o.has("data")) return o
        }
        return null
    }

    companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
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
