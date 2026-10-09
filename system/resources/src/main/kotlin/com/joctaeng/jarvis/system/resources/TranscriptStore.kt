package com.joctaeng.jarvis.system.resources

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda a conversa em disco, um arquivo por dia (`AAAA-MM-DD.txt`), turno a turno, para poder exportar tudo.
 * Nunca lança exceção. Quem chama decide se grava (modo privado não grava).
 */
class TranscriptStore(private val dir: File, private val keepDays: Int = 60, private val clock: () -> Long = System::currentTimeMillis) {
    @Synchronized
    fun append(speaker: String, text: String) {
        runCatching {
            dir.mkdirs()
            val now = Date(clock())
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now)
            val hour = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(now)
            File(dir, "$day.txt").appendText("[$hour] $speaker: ${text.trim()}\n\n")
            prune()
        }
    }

    /** Todas as conversas guardadas, do dia mais antigo ao mais novo, com título por dia. */
    @Synchronized
    fun readAll(): String = runCatching {
        files().joinToString("\n") { "===== ${it.name.removeSuffix(".txt")} =====\n" + it.readText() }
    }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { files().forEach { it.delete() } }
    }

    private fun files(): List<File> =
        dir.listFiles { f -> f.isFile && Regex("\\d{4}-\\d{2}-\\d{2}\\.txt").matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun prune() {
        val all = files()
        if (all.size > keepDays) all.dropLast(keepDays).forEach { it.delete() }
    }
}
