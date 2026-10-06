package com.joctaeng.jarvis.mind.persona

import java.util.Locale

/** Pedidos explícitos sobre memória (seção 8.2): "lembre que…" e "esqueça isto". */
sealed interface MemoryCommand {
    data class Remember(val fact: String) : MemoryCommand

    /** [query] nulo = esquecer a última coisa memorizada. */
    data class Forget(val query: String?) : MemoryCommand
}

object MemoryCommands {
    private val ptBr = Locale.forLanguageTag("pt-BR")
    private val rememberPrefixes = listOf(
        "lembre-se de que", "lembre-se que", "lembre que", "lembra que", "memorize que", "guarde que", "anote que",
    )
    private val forgetLast = listOf(
        "esqueça isto", "esqueça isso", "esquece isso", "esquece isto", "esqueça o que eu disse", "esqueça o que eu falei",
    )
    private val forgetPrefixes = listOf("esqueça que", "esquece que", "esqueça sobre", "esqueça")
    private val wakeWords = Regex("^(ei\\s+|oi\\s+)?(jarvis|joca)[,!.:]?\\s+", RegexOption.IGNORE_CASE)

    fun parse(utterance: String): MemoryCommand? {
        val text = utterance.trim().replace(wakeWords, "").trimEnd('.', '!', ' ')
        val lower = text.lowercase(ptBr)
        forgetLast.firstOrNull { lower == it || lower.startsWith("$it ") && lower.length - it.length < 3 }
            ?.let { return MemoryCommand.Forget(null) }
        rememberPrefixes.firstOrNull { lower.startsWith("$it ") }?.let { prefix ->
            val fact = text.substring(prefix.length).trim()
            return if (fact.isEmpty()) null else MemoryCommand.Remember(fact.replaceFirstChar { it.uppercase(ptBr) })
        }
        forgetPrefixes.firstOrNull { lower.startsWith("$it ") }?.let { prefix ->
            val query = text.substring(prefix.length).trim()
            return MemoryCommand.Forget(query.ifEmpty { null })
        }
        return null
    }
}
