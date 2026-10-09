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
class EventLog private constructor(
    private val dir: File?,
    private val single: File?,
    private val maxBytes: Long,
    private val keepDays: Int,
    private val clock: () -> Long,
) {
    /** Um arquivo só, com rotação (modo antigo, usado nos testes). */
    constructor(file: File, maxBytes: Long = 400_000, clock: () -> Long = System::currentTimeMillis) :
        this(null, file, maxBytes, 0, clock)

    private val file: File get() = single ?: File(dir, "euno-${dayStamp()}.log")

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        runCatching {
            val file = file
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) {
                val old = File(file.path + ".1")
                old.delete()
                file.renameTo(old)
            }
            val stack = error?.let { "\n" + describe(it, if (level == LogLevel.CRASH) 40 else 8) }.orEmpty()
            file.appendText("${stamp()} ${level.tag} $tag: ${message.replace('\n', ' ')}$stack\n")
            if (dir != null) pruneOldDays()
        }
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun error(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    /** Fim do registro (o mais recente), limitado a [maxChars]; no modo diário junta todos os dias guardados. */
    @Synchronized
    fun tail(maxChars: Int = 100_000): String = runCatching {
        val text = allFiles().joinToString("") { it.readText() }
        if (text.length <= maxChars) text else "…(início cortado)…\n" + text.takeLast(maxChars)
    }.getOrDefault("")

    /** Tudo o que está guardado, do mais antigo ao mais novo, sem corte. */
    @Synchronized
    fun readAll(): String = runCatching { allFiles().joinToString("") { it.readText() } }.getOrDefault("")

    /** Quantos dias de registro existem e quantos bytes ocupam. */
    @Synchronized
    fun stats(): Pair<Int, Long> = runCatching {
        val files = allFiles()
        files.map { it.name.removeSuffix(".1") }.distinct().size to files.sumOf { it.length() }
    }.getOrDefault(0 to 0L)

    @Synchronized
    fun clear() {
        runCatching { allFiles().forEach { it.delete() } }
    }

    /** Arquivos em ordem do mais antigo ao mais novo (o ".1" de um dia vem antes do arquivo do dia). */
    private fun allFiles(): List<File> {
        if (single != null) return listOf(File(single.path + ".1"), single).filter { it.isFile }
        val all = dir?.listFiles { f -> f.isFile && dayFile.matches(f.name) }.orEmpty()
        return all.sortedWith(compareBy({ it.name.removeSuffix(".1") }, { !it.name.endsWith(".1") }))
    }

    private val dayFile = Regex("euno-\\d{4}-\\d{2}-\\d{2}\\.log(\\.1)?")

    private fun pruneOldDays() {
        val limit = keepDays.coerceAtLeast(1)
        val days = allFiles().map { it.name.removeSuffix(".1") }.distinct().sorted()
        if (days.size <= limit) return
        val drop = days.dropLast(limit).toSet()
        allFiles().filter { it.name.removeSuffix(".1") in drop }.forEach { it.delete() }
    }

    private fun dayStamp(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(clock()))

    companion object {
        /** Um arquivo por dia em [dir] (`euno-AAAA-MM-DD.log`), guardando [keepDays] dias; cada dia aceita até [maxBytes] por arquivo. */
        fun daily(dir: File, keepDays: Int = 7, maxBytes: Long = 8_000_000, clock: () -> Long = System::currentTimeMillis): EventLog =
            EventLog(dir, null, maxBytes, keepDays, clock)
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(clock()))

    private fun describe(error: Throwable, maxLines: Int): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString().lineSequence().filter { it.isNotBlank() }.take(maxLines).joinToString("\n") { "    $it" }
    }
}
