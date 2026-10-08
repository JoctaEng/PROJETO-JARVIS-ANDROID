package com.joctaeng.jarvis.system.resources

/**
 * Quanto esperar depois de um erro de cota (HTTP 429) da API do Google. A resposta pode trazer
 * `"retryDelay": "23s"`, "Please retry in 23.45s" ou "retry in 5h48m11s". Sem dica, null.
 */
object RetryHint {
    private val delayField = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"")
    private val retryIn = Regex("retry in\\s+(?:(\\d+)h)?\\s*(?:(\\d+)m(?!s))?\\s*(?:(\\d+(?:\\.\\d+)?)s)?", RegexOption.IGNORE_CASE)

    fun millis(message: String): Long? {
        delayField.find(message)?.let { return (it.groupValues[1].toDouble() * 1000).toLong() }
        val m = retryIn.find(message) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLongOrNull() ?: 0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val ms = ((h * 60 + min) * 60) * 1000 + (sec * 1000).toLong()
        return ms.takeIf { it > 0 }
    }

    /** A cota que acabou é a diária? (o Google cita "PerDay" no identificador da cota). */
    fun isDaily(message: String): Boolean = message.contains("PerDay", ignoreCase = true) || message.contains("per day", ignoreCase = true)
}
