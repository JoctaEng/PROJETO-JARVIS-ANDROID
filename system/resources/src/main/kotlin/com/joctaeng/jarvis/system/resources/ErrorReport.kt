package com.joctaeng.jarvis.system.resources

/** Monta o texto do relatório de erros (puro, testável). */
object ErrorReport {
    /**
     * @param header pares "campo" → valor (versão, aparelho, assinatura...).
     * @param sections título → conteúdo (cada um já em texto); o fim de cada seção é preservado ao cortar.
     * @param maxChars teto do relatório inteiro (o app de compartilhar tem limite de tamanho).
     *
     * O espaço é dividido de forma justa: seções pequenas ficam inteiras e o que sobra vai para as grandes
     * (antes cada seção recebia a mesma fatia e o registro de eventos era cortado mesmo com espaço livre).
     */
    fun build(header: List<Pair<String, String>>, sections: List<Pair<String, String>>, maxChars: Int = 150_000): String {
        val head = buildString {
            appendLine("# Relatório do Euno")
            header.forEach { (k, v) -> appendLine("- $k: $v") }
        }
        val bodies = sections.map { it.second.ifBlank { "(vazio)" } }
        val budgets = share(bodies.map { it.length }, (maxChars - head.length - sections.sumOf { it.first.length + 8 }).coerceAtLeast(2_000 * sections.size))
        return buildString {
            append(head)
            sections.forEachIndexed { i, (title, _) ->
                val text = bodies[i]
                val budget = budgets[i]
                appendLine()
                appendLine("## $title")
                appendLine(if (text.length <= budget) text.trimEnd() else "…(início cortado: ${text.length - budget} caracteres)…\n" + text.takeLast(budget).trimEnd())
            }
        }
    }

    /** Divisão "enche-copos": quem precisa de pouco leva tudo; o resto é repartido igualmente entre os maiores. */
    fun share(needs: List<Int>, total: Int): List<Int> {
        val result = IntArray(needs.size)
        var left = total
        var open = needs.indices.sortedBy { needs[it] }
        while (open.isNotEmpty()) {
            val fair = left / open.size
            val small = open.filter { needs[it] <= fair }
            if (small.isEmpty()) {
                open.forEach { result[it] = fair }
                break
            }
            small.forEach { result[it] = needs[it]; left -= needs[it] }
            open = open - small.toSet()
        }
        return result.toList()
    }

    private val line = Regex("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})\\.\\d{3} ([IWEC]) ([^:]+): (.*)$")

    /**
     * Todos os avisos (W), erros (E) e quedas (C) do registro, agrupados pela mensagem (números trocados por #),
     * com quantas vezes aconteceram e quando foi a primeira e a última. Nada é descartado por tamanho.
     */
    fun problemSummary(log: String): String {
        data class Group(val level: String, val tag: String, var count: Int, val first: String, var last: String, val example: String)
        val groups = LinkedHashMap<String, Group>()
        log.lineSequence().forEach { raw ->
            val m = line.find(raw) ?: return@forEach
            val (_, time, level, tag, msg) = m.destructured
            if (level == "I") return@forEach
            val key = level + "|" + tag + "|" + msg.replace(Regex("\\d+"), "#").take(160)
            val g = groups[key]
            if (g == null) groups[key] = Group(level, tag, 1, time, time, msg.take(300)) else { g.count++; g.last = time }
        }
        if (groups.isEmpty()) return "Nenhum aviso, erro ou queda no registro."
        val order = mapOf("C" to 0, "E" to 1, "W" to 2)
        val label = mapOf("C" to "QUEDA", "E" to "ERRO", "W" to "aviso")
        val total = groups.values.sumOf { it.count }
        return "Total: $total ocorrências em ${groups.size} tipos.\n" + groups.values
            .sortedWith(compareBy<Group>({ order[it.level] }, { -it.count }))
            .joinToString("\n") { g ->
                val span = if (g.count == 1) g.first else "${g.first} → ${g.last}"
                "${label[g.level]} [${g.tag}] ×${g.count} ($span): ${g.example}"
            }
    }
}
