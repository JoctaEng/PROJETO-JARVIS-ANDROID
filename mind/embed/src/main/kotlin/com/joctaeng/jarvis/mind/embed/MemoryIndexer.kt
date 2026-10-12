package com.joctaeng.jarvis.mind.embed

import com.joctaeng.jarvis.mind.memory.MemoryItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Mantém o índice de significado em dia com as memórias. Quem chama (o app) avisa quando uma memória é criada, editada ou apagada.
 * Apagar a memória apaga o vetor. No modo Privado nada é gravado no índice.
 */
class MemoryIndexer(
    private val index: EmbeddingIndexStore,
    private val embedder: TextEmbedder,
    private val privateMode: () -> Boolean = { false },
    private val batchSize: Int = BATCH,
) {
    data class BackfillResult(
        /** Memórias que ganharam vetor agora. */
        val indexed: Int,
        /** Memórias que já estavam com o vetor certo (texto igual). */
        val upToDate: Int,
        val total: Int,
        /** Modo Privado: nada foi feito. */
        val skippedPrivate: Boolean = false,
    )

    /** Calcula e guarda o vetor de uma memória. Devolve false se não fez nada (Privado, ou o texto não mudou). */
    suspend fun indexOne(item: MemoryItem): Boolean {
        if (privateMode()) return false
        val hash = EmbeddingIndexStore.hash(item.text)
        if (index.hashOf(item.id) == hash) return false
        val vector = embedder.embed(listOf(item.text), EmbedRole.DOCUMENT).singleOrNull()
            ?: error("o modelo não devolveu o vetor da memória")
        index.put(item.id, hash, VectorMath.truncate(vector, index.dims))
        return true
    }

    fun forget(id: String) {
        index.remove(id)
    }

    fun forgetAll() {
        index.clear()
    }

    /**
     * Calcula os vetores que faltam, em lotes de [batchSize], com progresso. Pode ser cancelada a qualquer momento
     * (entre lotes) e retomada depois: só refaz o que não tem vetor ou cujo texto mudou. Vetores de memórias que não existem mais saem.
     */
    suspend fun backfill(memories: List<MemoryItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): BackfillResult {
        val total = memories.size
        if (privateMode()) return BackfillResult(0, 0, total, skippedPrivate = true)
        index.removeAllExcept(memories.map { it.id }.toSet())
        val pending = memories.filter { index.hashOf(it.id) != EmbeddingIndexStore.hash(it.text) }
        val upToDate = total - pending.size
        onProgress(upToDate, total)
        var done = upToDate
        for (batch in pending.chunked(batchSize.coerceAtLeast(1))) {
            currentCoroutineContext().ensureActive()
            val vectors = embedder.embed(batch.map { it.text }, EmbedRole.DOCUMENT)
            check(vectors.size == batch.size) { "o modelo devolveu ${vectors.size} vetores para ${batch.size} textos" }
            index.putAll(
                batch.indices.associate { i ->
                    batch[i].id to IndexEntry(EmbeddingIndexStore.hash(batch[i].text), VectorMath.truncate(vectors[i], index.dims))
                },
            )
            done += batch.size
            onProgress(done, total)
        }
        return BackfillResult(indexed = pending.size, upToDate = upToDate, total = total)
    }

    companion object {
        const val BATCH = 8
    }
}
