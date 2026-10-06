package com.joctaeng.jarvis.diagnostics

import android.content.Context
import java.io.File

/** Identificadores dos registros de cada prova de conceito. */
object Poc {
    const val OVERLAY = "poc01_overlay"
    const val TOUCH_SESSION = "poc02_touch_session"
    const val LOCAL_LLM = "poc03_local_llm"
    const val SPEECH = "poc04_speech"
    const val RENDERER = "poc05_renderer"
    val all = listOf(OVERLAY, TOUCH_SESSION, LOCAL_LLM, SPEECH, RENDERER)
}

typealias Record = Map<String, String>

/**
 * Registro local e simples das medições da Fase 0: um arquivo por PoC, uma
 * linha por evento, campos `chave=valor` separados por TAB. Fica só no aparelho.
 */
class Diagnostics(context: Context) {
    private val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }

    @Synchronized
    fun append(poc: String, vararg fields: Pair<String, Any?>) {
        val line = (listOf("ts" to System.currentTimeMillis()) + fields)
            .joinToString("\t") { (k, v) -> "$k=${sanitize(v)}" }
        File(dir, "$poc.log").appendText(line + "\n")
    }

    @Synchronized
    fun read(poc: String): List<Record> {
        val file = File(dir, "$poc.log")
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { line ->
            line.split('\t').associate { field ->
                val i = field.indexOf('=')
                if (i < 0) field to "" else field.substring(0, i) to field.substring(i + 1)
            }
        }
    }

    @Synchronized
    fun clear(poc: String) {
        File(dir, "$poc.log").delete()
    }

    private fun sanitize(value: Any?): String =
        value?.toString()?.replace('\t', ' ')?.replace('\n', ' ') ?: ""
}
