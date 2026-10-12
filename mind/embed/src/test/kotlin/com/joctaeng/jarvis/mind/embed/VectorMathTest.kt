package com.joctaeng.jarvis.mind.embed

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VectorMathTest {
    private fun norm(v: FloatArray): Double = sqrt(v.fold(0.0) { a, x -> a + x.toDouble() * x })

    /** Vetor de 768 posições com a energia nas primeiras 128 (como os embeddings Matryoshka) + uma cauda pequena. */
    private fun vec(seed: Int, head: Map<Int, Float>): FloatArray {
        val v = FloatArray(768) { ((it * 31 + seed * 17) % 13 - 6) * 0.001f }
        head.forEach { (i, x) -> v[i] += x }
        return VectorMath.normalize(v)
    }

    @Test fun truncateKeepsNormOneAt256And128() {
        val v = vec(1, mapOf(0 to 3f, 5 to 2f, 100 to 1f, 400 to 4f, 700 to 4f))
        listOf(256, 128).forEach { d ->
            val t = VectorMath.truncate(v, d)
            assertEquals(d, t.size)
            assertTrue(abs(norm(t) - 1.0) < 1e-5, "norma em $d dims = ${norm(t)}")
        }
    }

    @Test fun truncateKeepsRelativeOrder() {
        val q = vec(1, mapOf(0 to 5f, 1 to 4f, 2 to 3f))
        val a = vec(2, mapOf(0 to 5f, 1 to 4f, 2 to 2f)) // quase igual à pergunta
        val b = vec(3, mapOf(0 to 2f, 1 to 1f, 50 to 5f)) // meio parecido
        val c = vec(4, mapOf(60 to 5f, 70 to 5f, 90 to 5f)) // diferente
        listOf(768, 256, 128).forEach { d ->
            val tq = VectorMath.truncate(q, d)
            val sims = listOf(a, b, c).map { VectorMath.cosine(tq, VectorMath.truncate(it, d)) }
            assertTrue(sims[0] > sims[1] && sims[1] > sims[2], "ordem quebrou em $d dims: $sims")
        }
    }

    @Test fun truncateRejectsBadSizes() {
        assertFailsWith<IllegalArgumentException> { VectorMath.truncate(FloatArray(10), 20) }
        assertFailsWith<IllegalArgumentException> { VectorMath.truncate(FloatArray(10), 0) }
    }

    @Test fun cosineSelfOrthogonalAndZero() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertTrue(abs(VectorMath.cosine(a, a) - 1f) < 1e-5)
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        val zero = FloatArray(3)
        assertEquals(0f, VectorMath.cosine(zero, a)) // sem dividir por zero
        assertEquals(0f, VectorMath.cosine(zero, zero))
        assertTrue(!VectorMath.cosine(zero, a).isNaN())
        assertFailsWith<IllegalArgumentException> { VectorMath.cosine(a, FloatArray(2)) }
    }

    @Test fun topKOrdersByScoreAndFiltersByMinScore() {
        val q = floatArrayOf(1f, 0f)
        val index = mapOf(
            "perto" to floatArrayOf(0.9f, 0.1f),
            "meio" to floatArrayOf(0.6f, 0.6f),
            "longe" to floatArrayOf(0f, 1f),
        )
        assertEquals(listOf("perto", "meio", "longe"), VectorMath.topK(q, index, 10).map { it.id })
        assertEquals(listOf("perto"), VectorMath.topK(q, index, 10, minScore = 0.9f).map { it.id })
        assertEquals(listOf("perto", "meio"), VectorMath.topK(q, index, 2).map { it.id })
    }

    @Test fun topKTiesBreakByLowestIdAndHandlesBigK() {
        val q = floatArrayOf(1f, 0f)
        val same = floatArrayOf(1f, 0f)
        val index = linkedMapOf("c" to same, "a" to same, "b" to same)
        assertEquals(listOf("a", "b", "c"), VectorMath.topK(q, index, 5).map { it.id }) // k maior que a lista não quebra
        assertEquals(listOf("a"), VectorMath.topK(q, index, 1).map { it.id })
        assertTrue(VectorMath.topK(q, index, 0).isEmpty())
        assertTrue(VectorMath.topK(q, emptyMap(), 3).isEmpty())
    }

    @Test fun topKIgnoresVectorsOfOtherSize() {
        val index = mapOf("ok" to floatArrayOf(1f, 0f), "errado" to floatArrayOf(1f, 0f, 0f))
        assertEquals(listOf("ok"), VectorMath.topK(floatArrayOf(1f, 0f), index, 5).map { it.id })
    }

    @Test fun normalizeKeepsZeroVector() {
        assertContentEquals(FloatArray(4), VectorMath.normalize(FloatArray(4)))
    }
}

class KeywordScoreTest {
    @Test fun ignoresAccentsAndCase() {
        assertEquals(1, KeywordScore.score("quando é o ANIVERSÁRIO?", "o aniversario dela é em maio"))
        assertEquals(KeywordScore.words("aniversário"), KeywordScore.words("aniversario"))
    }

    @Test fun ignoresOneAndTwoLetterWords() {
        assertTrue(KeywordScore.words("é o de da em a").isEmpty())
        assertEquals(0, KeywordScore.score("é o de", "é o de da em"))
        assertEquals(setOf("mae"), KeywordScore.words("a mãe é"))
    }

    @Test fun zeroWhenNothingInCommon() {
        assertEquals(0, KeywordScore.score("senha do wifi", "reunião de pais na quinta"))
    }
}

class EmbedPromptsTest {
    @Test fun usesOfficialTaskPrefixes() {
        assertEquals("task: search result | query: onde moro?", EmbedPrompts.format("onde moro?", EmbedRole.QUERY))
        assertEquals("title: none | text: moro em São Luís", EmbedPrompts.format("moro em São Luís", EmbedRole.DOCUMENT))
    }
}
