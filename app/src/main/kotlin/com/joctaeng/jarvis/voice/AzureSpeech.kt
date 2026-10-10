package com.joctaeng.jarvis.voice

import com.joctaeng.jarvis.system.resources.AzureSsml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Voz do Azure (Microsoft), vozes neurais pt-BR. REST oficial: POST https://{região}.tts.speech.microsoft.com/cognitiveservices/v1
 * com a chave no cabeçalho Ocp-Apim-Subscription-Key e o texto em SSML; pede PCM 16 bits 24 kHz (mesmo player do Gemini).
 * A chave fica só no celular (SecretStore).
 */
class AzureSpeech(private val key: String, private val region: String) {
    data class AzureVoice(val shortName: String, val localName: String, val gender: String)

    private fun host() = "https://${region.trim().lowercase()}.tts.speech.microsoft.com"

    suspend fun synthesize(text: String, voice: String, rate: Float = 1.0f): GeminiSpeech.Clip = withContext(Dispatchers.IO) {
        val conn = (URL("${host()}/cognitiveservices/v1").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Ocp-Apim-Subscription-Key", key.trim())
            setRequestProperty("Content-Type", "application/ssml+xml")
            setRequestProperty("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
            setRequestProperty("User-Agent", "Euno")
        }
        try {
            conn.outputStream.use { it.write(AzureSsml.build(text, voice, rate).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw IOException("Azure HTTP $code ${body.take(300)}")
            }
            val pcm = conn.inputStream.use { it.readBytes() }
            if (pcm.isEmpty()) throw IOException("Azure devolveu áudio vazio")
            GeminiSpeech.Clip(pcm, 24_000, "azure")
        } finally {
            conn.disconnect()
        }
    }

    /** Vozes pt-BR disponíveis para esta chave/região (também serve para testar a chave). */
    suspend fun voices(): List<AzureVoice> = withContext(Dispatchers.IO) {
        val conn = (URL("${host()}/cognitiveservices/voices/list").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Ocp-Apim-Subscription-Key", key.trim())
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("Azure HTTP $code (confira a chave e a região)")
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("Locale") == "pt-BR" }
                .map { AzureVoice(it.optString("ShortName"), it.optString("LocalName"), it.optString("Gender")) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_FEMALE = "pt-BR-FranciscaNeural"
        const val DEFAULT_MALE = "pt-BR-AntonioNeural"
    }
}
