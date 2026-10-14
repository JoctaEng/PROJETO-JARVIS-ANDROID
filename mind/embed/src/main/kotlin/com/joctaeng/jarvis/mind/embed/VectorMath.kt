package com.joctaeng.jarvis.mind.embed

import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** Um resultado da busca: o id da memória e o quanto ela se parece com a pergunta (cosseno, de -1 a 1). */
data class Scored(val id: String, val score: Float)

object VectorMath {
    /** Divide pelo tamanho do vetor (norma L2). Vetor zero continua zero (sem dividir por zero). */
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        if (sum == 0.0) return v.copyOf()
        val norm = sqrt(sum)
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /**
     * Matryoshka: fica só com as primeiras [dims] posições e NORMALIZA DE NOVO. Cortar um vetor de norma 1 não mantém a norma 1;
     * sem normalizar, o cosseno dá notas que parecem normais mas ordenam errado (aviso da ficha oficial).
     */
    fun truncate(v: FloatArray, dims: Int): FloatArray {
        require(dims > 0) { "dims precisa ser positivo" }
        require(dims <= v.size) { "o vetor tem ${v.size} posições; não dá para cortar em $dims" }
        return normalize(v.copyOf(dims))
    }

    /** Produto escalar para vetores já normalizados (L2 = 1.0). Muito mais rápido que recalcular raízes quadradas. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "tamanhos diferentes: ${a.size} e ${b.size}" }
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i].toDouble()
        return sum.coerceIn(-1.0, 1.0).toFloat()
    }

    /** Cosseno entre dois vetores. Se algum for zero, devolve 0 (não divide por zero). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "tamanhos diferentes: ${a.size} e ${b.size}" }
        var dotProduct = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dotProduct += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dotProduct / (sqrt(na) * sqrt(nb))).coerceIn(-1.0, 1.0).toFloat()
    }

    /**
     * Os [k] itens mais parecidos com [query], do maior para o menor. Empate: o MENOR id vem primeiro (resultado estável).
     * Só entram os que têm nota >= [minScore]. Vetores de tamanho diferente do da pergunta são ignorados.
     */
    fun topK(query: FloatArray, index: Map<String, FloatArray>, k: Int, minScore: Float = -1f): List<Scored> {
        if (k <= 0 || index.isEmpty()) return emptyList()
        return index.asSequence()
            .filter { it.value.size == query.size }
            .map { Scored(it.key, dot(query, it.value)) }
            .filter { it.score >= minScore }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.id })
            .take(k)
            .toList()
    }
}

/** Busca por palavras (plano B quando não há modelo): sem acento, sem maiúsculas, ignora palavras de 1 e 2 letras. */
object KeywordScore {
    // Mesmas palavras que o MemoryStore ignora, para as duas buscas concordarem.
    private val stopWords = setOf("que", "para", "com", "uma", "uns", "das", "dos", "nas", "nos", "por", "mais", "isso", "isto", "meu", "minha", "sobre")
    private val accents = Regex("\\p{M}")
    private val splitRegex = Regex("[^a-z0-9]+")

    fun words(text: String): Set<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(accents, "")
            .split(splitRegex)
            .filter { it.length > 2 && it !in stopWords }
            .toSet()

    /** Quantas palavras (diferentes) a pergunta e o texto têm em comum. 0 = nada a ver. */
    fun score(query: String, text: String): Int {
        val wanted = words(query)
        if (wanted.isEmpty()) return 0
        return words(text).count { it in wanted }
    }
}
