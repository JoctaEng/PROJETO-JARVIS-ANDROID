package com.joctaeng.jarvis.system.resources

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Número de telefone no formato do link do WhatsApp (só dígitos, com 55 do Brasil). Null se não parecer telefone. */
object PhoneNumber {
    fun forWhatsApp(raw: String): String? {
        var digits = raw.filter { it.isDigit() }
        if (raw.trim().startsWith("00")) digits = digits.removePrefix("00")
        digits = digits.trimStart('0')
        return when {
            digits.length in 10..11 -> "55$digits" // DDD + número, sem país
            digits.length in 12..13 && digits.startsWith("55") -> digits
            digits.length in 11..15 -> digits // outro país, já com código
            else -> null
        }
    }
}

/** Data e hora de um compromisso dita pelo cérebro ("2026-10-09 15:30" ou ISO com T). Hora local do aparelho. */
object EventTime {
    private val formats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
    )

    fun parseMillis(text: String, zone: ZoneId): Long? {
        val t = text.trim().removeSuffix("Z")
        for (f in formats) {
            val dt = runCatching { LocalDateTime.parse(t, f) }.getOrNull() ?: continue
            return dt.atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }
}
