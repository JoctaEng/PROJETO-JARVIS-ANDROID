package com.joctaeng.jarvis.mind.embed

import com.joctaeng.jarvis.mind.memory.MemoryItem
import com.joctaeng.jarvis.mind.memory.MemoryListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Liga o MemoryStore ao índice: toda memória criada ou editada ganha vetor novo; toda memória apagada perde o vetor.
 *
 * - Apagar/editar SEMPRE tira o vetor antigo, mesmo com a opção desligada (apagar a memória apaga o vetor).
 * - Calcular vetor novo só quando [active] (opção ligada e modelo no aparelho); senão o backfill faz depois.
 * - No modo Privado, o [MemoryIndexer] não grava nada.
 */
class IndexingMemoryListener(
    private val indexer: MemoryIndexer,
    private val scope: CoroutineScope,
    private val active: () -> Boolean,
    private val onError: (Throwable) -> Unit = {},
) : MemoryListener {
    override fun onAdded(item: MemoryItem) = launchIndex(item)

    override fun onUpdated(item: MemoryItem) {
        indexer.forget(item.id) // o vetor antigo era do texto antigo
        launchIndex(item)
    }

    override fun onRemoved(id: String) = indexer.forget(id)

    override fun onCleared() = indexer.forgetAll()

    private fun launchIndex(item: MemoryItem) {
        if (!active()) return
        scope.launch {
            try {
                indexer.indexOne(item)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }
}
