package com.joctaeng.jarvis.mind.persona

/** Corta o histórico para caber no cérebro do celular: mantém o fim, por tamanho em caracteres e número de mensagens. */
object HistoryTrim {
    fun <T> keepRecent(items: List<T>, size: (T) -> Int, maxChars: Int, maxItems: Int): List<T> {
        val out = ArrayDeque<T>()
        var total = 0
        for (item in items.asReversed()) {
            val s = size(item)
            // A mensagem mais recente sempre fica (mesmo grande); as anteriores só se couberem.
            if (out.isNotEmpty() && (out.size >= maxItems || total + s > maxChars)) break
            out.addFirst(item)
            total += s
        }
        return out.toList()
    }
}
