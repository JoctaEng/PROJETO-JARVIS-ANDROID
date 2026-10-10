package com.joctaeng.jarvis.tools

import android.util.Base64
import com.joctaeng.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Olha o print da tela com o Gemini (que enxerga imagens) e responde em texto: o que há na tela e onde fica cada
 * coisa, em porcentagem da largura e da altura. Assim o Euno entende o espaço e toca no lugar certo (pedido 65).
 * Usa a chave do Gemini que o usuário cadastrou; a imagem vai só para o Google, como as conversas.
 */
object ScreenVision {
    private const val PROMPT =
        "Você vê um print da tela de um celular Android. Responda em português do Brasil, curto e objetivo.\n" +
            "1) Diga que app/tela é e o que aparece de importante.\n" +
            "2) Liste os elementos que se pode tocar e que interessam ao pedido, um por linha, no formato: " +
            "- nome do elemento → (x%, y%) — o centro do elemento em porcentagem da largura (x, da esquerda) e da altura (y, de cima).\n" +
            "Seja preciso nas posições. Ignore o balão ou o personagem do assistente Euno, se aparecerem num canto.\n" +
            "Não invente o que não está visível."

    suspend fun look(app: JarvisApp, jpeg: ByteArray, question: String, screenText: String): String = withContext(Dispatchers.IO) {
        val (base, model, key) = app.brains.visionBrain()
            ?: error("preciso de um cérebro Gemini com chave (Meu Euno → Cérebro) para olhar a tela")
        val ask = buildString {
            append(PROMPT)
            if (question.isNotBlank()) append("\nPedido do usuário: ").append(question.take(300))
            if (screenText.isNotBlank()) append("\nTextos que o Android leu desta tela (ajudam a dar nome certo):\n").append(screenText.take(1_500))
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", ask))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                ),
            )
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 700)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val conn = (URL(base.trim().trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${key.trim()}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                error("o Gemini recusou o print ($code): ${err.take(160)}")
            }
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
                .ifBlank { error("o Gemini não descreveu a tela") }
        } finally {
            conn.disconnect()
        }
    }
}
