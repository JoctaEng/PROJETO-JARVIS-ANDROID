package com.joctaeng.jarvis.system.resources

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val tag: String) { INFO("I"), WARN("W"), ERROR("E"), CRASH("C") }

/**
 * Registro único de eventos, avisos, erros e quedas do app, em um arquivo de texto com rotação
 * (o antigo vira `.1`; no máximo ~2× [maxBytes] em disco). Nunca lança exceção: registrar não pode
 * derrubar o app. Nunca grava chaves ou textos de conversa — só o que cada chamador decide escrever.
 */
class EventLog(
    private val file: File,
    private val maxBytes: Long = 400_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) {
                val old = File(file.path + ".1")
                old.delete()
                file.renameTo(old)
            }
            val stack = error?.let { "\n" + describe(it, if (level == LogLevel.CRASH) 40 else 8) }.orEmpty()
            file.appendText("${stamp()} ${level.tag} $tag: ${message.replace('\n', ' ')}$stack\n")
        }
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun error(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    /** Fim do arquivo (o mais recente), limitado a [maxChars]; inclui o arquivo rotacionado se couber. */
    @Synchronized
    fun tail(maxChars: Int = 100_000): String = runCatching {
        val old = File(file.path + ".1")
        val text = (if (old.isFile) old.readText() else "") + (if (file.isFile) file.readText() else "")
        if (text.length <= maxChars) text else "…(início cortado)…\n" + text.takeLast(maxChars)
    }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { file.delete(); File(file.path + ".1").delete() }
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(clock()))

    private fun describe(error: Throwable, maxLines: Int): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString().lineSequence().filter { it.isNotBlank() }.take(maxLines).joinToString("\n") { "    $it" }
    }
}
