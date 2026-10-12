package com.joctaeng.jarvis.mind.embed

import java.io.File
import java.nio.file.Files

/**
 * Falso determinístico para os testes: cada palavra (sem acento, 3+ letras) cai numa posição do vetor, então textos com
 * palavras em comum ficam parecidos. SÓ para teste; não é o modelo real.
 */
class FakeEmbedder(override val dims: Int = 256, override val modelId: String = "fake-1") : TextEmbedder {
    val calls = mutableListOf<Pair<List<String>, EmbedRole>>()
    val textsEmbedded: Int get() = calls.sumOf { it.first.size }

    override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> {
        calls += texts to role
        return texts.map { vector(it) }
    }

    fun vector(text: String): FloatArray {
        val v = FloatArray(dims)
        KeywordScore.words(text).forEach { v[Math.floorMod(it.hashCode(), dims)] += 1f }
        return VectorMath.normalize(v)
    }
}

internal fun tempFile(name: String = "embeddings.json"): File = File(Files.createTempDirectory("embed").toFile(), name)

internal fun memory(id: String, text: String, category: String = "geral", at: Long = 0L) =
    com.joctaeng.jarvis.mind.memory.MemoryItem(id, text, "teste", "teste", at, category)
