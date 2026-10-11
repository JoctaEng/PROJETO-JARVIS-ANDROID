package com.joctaeng.jarvis.mind.intent

/**
 * Modelo de frase: uma forma compacta de escrever muitas maneiras de pedir a mesma coisa. Casa com o pedido já sem acento
 * e com datas/horas/durações/valores trocados por marcadores.
 *
 *  - `abre o gmail`            palavras exatas (separadas por espaço)
 *  - `(abre|abra|abrir)`       alternativas (podem ter mais de uma palavra: `(o que|quais)`)
 *  - `(por favor)?`  `me?`     opcional (grupo ou palavra com `?`)
 *  - `abr~`                    palavra que COMEÇA com isso (abre, abra, abrir…)
 *  - `{titulo}`  `{titulo?}`   trecho livre (uma ou mais palavras; o `?` torna opcional); volta com as palavras originais
 *  - `<D>` `<T>` `<DUR>` `<M>` `<R>` data, hora, duração, valor, repetição extraídos (com `?` ficam opcionais: `<D?>`)
 *  - `...`                     quaisquer palavras (zero ou mais)
 */
class Template(val source: String) {
    private sealed interface Node
    private data class Word(val text: String, val prefix: Boolean, val optional: Boolean) : Node
    private data class Group(val alts: List<List<Node>>, val optional: Boolean) : Node
    private data class Cap(val name: String, val optional: Boolean) : Node
    private data object Anything : Node

    private val nodes: List<Node> = parse(source)
    private val captureNames: List<String> = collect(nodes)
    private val regex: Regex = Regex("^" + seq(nodes) + "\\s*$")

    /** Quantas palavras fixas o modelo tem: modelo mais específico ganha do mais solto. */
    val specificity: Int = specificityOf(nodes)

    /** Palavras fixas (para escolher candidatos a mostrar à IA tradutora). */
    val words: Set<String> = wordsOf(nodes).toSet()

    fun match(ex: Extracted): Map<String, String>? {
        val tokens = ex.toks
        val text = " " + ex.skeleton
        val m = regex.find(text) ?: return null
        val starts = IntArray(tokens.size)
        val ends = IntArray(tokens.size)
        var pos = 1
        for ((i, t) in tokens.withIndex()) {
            starts[i] = pos
            pos += t.norm.length
            ends[i] = pos
            pos += 1
        }
        val out = mutableMapOf<String, String>()
        for (name in captureNames) {
            val g = runCatching { m.groups[name] }.getOrNull() ?: continue
            val first = starts.indexOfFirst { it >= g.range.first }
            val last = ends.indexOfLast { it <= g.range.last + 1 }
            if (first < 0 || last < first) continue
            val span = tokens.subList(first, last + 1)
            val words = span.filterNot { it.norm in PLACEHOLDERS }.joinToString(" ") { it.orig }.trim()
            if (words.isNotEmpty()) out[name] = words
            // com as datas/horas/valores como foram ditos ("chego às 8h"): para textos que vão inteiros a outra pessoa
            val full = span.map { it.orig }.filter { it.isNotBlank() }.joinToString(" ").trim()
            if (full.isNotEmpty()) out["$name+"] = full
        }
        return out
    }

    // -------- exemplos para os testes ("expansão")

    /** Quantas frases diferentes este modelo representa (com [samples] palavras possíveis em cada trecho livre). */
    fun count(samples: Map<String, List<String>> = emptyMap()): Long = countSeq(nodes, samples)

    /** Até [max] frases de exemplo, espalhadas por todas as combinações. Trechos livres e marcadores vêm de [samples]. */
    fun expand(samples: Map<String, List<String>>, max: Int = 40): List<String> {
        val total = count(samples)
        if (total <= 0) return emptyList()
        val step = if (total <= max) 1L else total / max
        val out = mutableListOf<String>()
        var i = 0L
        while (i < total && out.size < max) {
            out += render(nodes, i, samples).replace(Regex("\\s+"), " ").trim()
            i += step
        }
        return out.distinct()
    }

    // -------- compilação

    private fun seq(list: List<Node>): String = list.joinToString("") { node ->
        when (node) {
            is Word -> {
                val w = Regex.escape(node.text) + if (node.prefix) "\\w*" else ""
                if (node.optional) "(?:\\s+$w)?" else "\\s+$w"
            }
            is Cap -> {
                val c = "\\s+(?<${node.name}>\\S+(?:\\s+\\S+)*?)"
                if (node.optional) "(?:$c)?" else c
            }
            is Group -> "(?:" + node.alts.joinToString("|") { seq(it) } + ")" + if (node.optional) "?" else ""
            Anything -> "(?:\\s+\\S+)*?"
        }
    }

    private fun collect(list: List<Node>): List<String> = list.flatMap { n ->
        when (n) {
            is Cap -> listOf(n.name)
            is Group -> n.alts.flatMap { collect(it) }
            else -> emptyList()
        }
    }.distinct()

    private fun specificityOf(list: List<Node>): Int = list.sumOf { n ->
        when (n) {
            is Word -> if (n.optional) 0 else if (n.text in PLACEHOLDERS) 2 else 3
            is Group -> if (n.optional) 0 else n.alts.minOf { specificityOf(it) }
            is Cap, Anything -> 0
        }
    }

    private fun wordsOf(list: List<Node>): List<String> = list.flatMap { n ->
        when (n) {
            is Word -> if (n.text in PLACEHOLDERS) emptyList() else listOf(n.text)
            is Group -> n.alts.flatMap { wordsOf(it) }
            else -> emptyList()
        }
    }

    // -------- exemplos

    private fun countSeq(list: List<Node>, samples: Map<String, List<String>>): Long {
        var total = 1L
        for (n in list) {
            val c = when (n) {
                is Word -> if (n.text in PLACEHOLDERS) maxOf(1, samples[n.text]?.size ?: 1).toLong() + (if (n.optional) 1 else 0) else if (n.optional) 2L else 1L
                is Cap -> maxOf(1, samples[n.name]?.size ?: 1).toLong() + if (n.optional) 1 else 0
                is Group -> n.alts.sumOf { countSeq(it, samples) } + if (n.optional) 1 else 0
                Anything -> 1L
            }
            total = (total * c).coerceAtMost(1_000_000_000L)
        }
        return total
    }

    private fun render(list: List<Node>, index: Long, samples: Map<String, List<String>>): String {
        var idx = index
        val sb = StringBuilder()
        for (n in list) {
            val c = countSeq(listOf(n), samples)
            val here = idx % c
            idx /= c
            when (n) {
                is Word -> if (n.text in PLACEHOLDERS) {
                    val opts = samples[n.text] ?: listOf(n.text)
                    if (!(n.optional && here >= opts.size)) sb.append(' ').append(opts[(here % opts.size).toInt()])
                } else if (!(n.optional && here == 1L)) sb.append(' ').append(sample(n, samples))
                is Cap -> {
                    val opts = samples[n.name] ?: listOf("coisa")
                    if (!(n.optional && here >= opts.size)) sb.append(' ').append(opts[(here % opts.size).toInt()])
                }
                is Group -> {
                    if (n.optional && here == c - 1) continue
                    var k = here
                    for (alt in n.alts) {
                        val ac = countSeq(alt, samples)
                        if (k < ac) { sb.append(' ').append(render(alt, k, samples)); break }
                        k -= ac
                    }
                }
                Anything -> Unit
            }
        }
        return sb.toString()
    }

    private fun sample(w: Word, samples: Map<String, List<String>>): String = when {
        w.text in PLACEHOLDERS -> (samples[w.text] ?: listOf(w.text)).first()
        w.prefix -> w.text + "a"
        else -> w.text
    }

    // -------- leitura do modelo

    private fun parse(src: String): List<Node> {
        val p = Parser(src)
        return p.sequence(false)
    }

    private class Parser(val s: String) {
        var i = 0

        fun sequence(inGroup: Boolean): List<Node> {
            val out = mutableListOf<Node>()
            while (i < s.length) {
                when (val c = s[i]) {
                    ' ' -> i++
                    ')', '|' -> if (inGroup) return out else error("modelo com ')' sobrando: $s")
                    '(' -> out += group()
                    '{' -> {
                        val end = s.indexOf('}', i)
                        require(end > 0) { "modelo com '{' sem fechar: $s" }
                        val raw = s.substring(i + 1, end)
                        i = end + 1
                        out += Cap(raw.removeSuffix("?"), raw.endsWith("?"))
                    }
                    '<' -> {
                        val end = s.indexOf('>', i)
                        require(end > 0) { "modelo com '<' sem fechar: $s" }
                        val raw = s.substring(i + 1, end)
                        i = end + 1
                        val ph = when (raw.removeSuffix("?")) {
                            "D" -> "xdatax"; "T" -> "xtimex"; "DUR" -> "xdurx"; "M" -> "xmoneyx"; "R" -> "xrecx"
                            else -> error("marcador desconhecido <$raw> em: $s")
                        }
                        out += Word(ph, false, raw.endsWith("?"))
                    }
                    else -> {
                        val start = i
                        while (i < s.length && s[i] !in " ()|{}<>") i++
                        var w = s.substring(start, i)
                        if (w == "...") { out += Anything; continue }
                        val optional = w.endsWith("?")
                        if (optional) w = w.removeSuffix("?")
                        val prefix = w.endsWith("~")
                        if (prefix) w = w.removeSuffix("~")
                        require(w.isNotEmpty()) { "palavra vazia em: $s (caractere '$c')" }
                        out += Word(w, prefix, optional)
                    }
                }
            }
            return out
        }

        fun group(): Group {
            i++ // (
            val alts = mutableListOf<List<Node>>()
            while (true) {
                alts += sequence(true)
                require(i < s.length) { "modelo com '(' sem fechar: $s" }
                if (s[i] == '|') { i++; continue }
                i++ // )
                break
            }
            var optional = false
            if (i < s.length && s[i] == '?') { optional = true; i++ }
            return Group(alts, optional)
        }
    }

    companion object {
        val PLACEHOLDERS = setOf("xdatax", "xtimex", "xdurx", "xmoneyx", "xrecx")
    }
}
