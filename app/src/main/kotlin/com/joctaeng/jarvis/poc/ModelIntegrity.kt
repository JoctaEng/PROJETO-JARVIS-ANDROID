package com.joctaeng.jarvis.poc

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Confere se o arquivo do modelo é o original (teste da 0.28.1: o Gemma 4 tinha o tamanho certo mas estava danificado, e o
 * motor só dizia "Engine is not initialized"). Compara o SHA-256 do arquivo com o que o Hugging Face publica para ele.
 */
object ModelIntegrity {
    sealed interface Verdict {
        data class Ok(val sha: String) : Verdict
        data class Damaged(val expected: String, val actual: String) : Verdict
        /** Sem internet ou o site não deu o valor: só dá para mostrar o SHA-256 calculado. */
        data class Unknown(val actual: String, val why: String) : Verdict
    }

    fun sha256(file: File, onProgress: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress?.invoke(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 esperado do arquivo [name] no repositório do modelo recomendado, ou null. */
    fun expectedSha256(name: String): String? = runCatching {
        val conn = (URL(TREE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Euno-Android")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("path") == name }
                ?.optJSONObject("lfs")?.optString("oid")?.takeIf { it.length == 64 }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    fun verify(file: File, onProgress: ((Long) -> Unit)? = null): Verdict {
        val actual = sha256(file, onProgress)
        val expected = expectedSha256(file.name)
            ?: return Verdict.Unknown(actual, "não consegui consultar o valor original no Hugging Face (sem internet?)")
        return if (expected.equals(actual, ignoreCase = true)) Verdict.Ok(actual) else Verdict.Damaged(expected, actual)
    }

    /** Frase curta para a tela. */
    fun describe(v: Verdict, name: String): String = when (v) {
        is Verdict.Ok -> "Arquivo conferido: $name é o original (SHA-256 confere)."
        is Verdict.Damaged ->
            "ARQUIVO DANIFICADO: $name tem o tamanho certo, mas o conteúdo não é o original (SHA-256 ${v.actual.take(10)}… ≠ ${v.expected.take(10)}…). " +
                "Toque em Apagar e baixe de novo (de preferência por \"Baixar Gemma 4\" no Wi-Fi)."
        is Verdict.Unknown -> "Não deu para conferir: ${v.why}. SHA-256 do arquivo: ${v.actual.take(16)}…"
    }

    private const val TREE_URL = "https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/tree/main"
}
