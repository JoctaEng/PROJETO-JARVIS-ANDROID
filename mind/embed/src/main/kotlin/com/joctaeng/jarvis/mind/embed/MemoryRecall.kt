package com.joctaeng.jarvis.mind.embed

import com.joctaeng.jarvis.mind.memory.MemoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Escolhe quais memórias vão para o prompt do cérebro.
 *
 * - Opção DESLIGADA: devolve TODAS, exatamente como na 0.29.1 (comportamento idêntico).
 * - Opção LIGADA: as [k] mais parecidas em SENTIDO com o pedido (cosseno acima de [minScore]), com teto de ~[maxChars] caracteres.
 *   Memória ainda sem vetor (acabou de ser criada) entra por palavras. Qualquer falha (sem modelo, sem vetores, erro,
 *   [timeoutMs] estourado) cai na busca por palavras; nunca derruba a conversa.
 */
class MemoryRecall(
    private val memories: () -> List<MemoryItem>,
    private val index: EmbeddingIndexStore,
    private val enabled: () -> Boolean,
    /** Devolve o motor de embedding pronto (carrega sob demanda) ou null se não há modelo. */
    private val embedder: suspend () -> TextEmbedder?,
    private val minScore: Float = DEFAULT_MIN_SCORE,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onNote: (String) -> Unit = {},
) {
    enum class Mode { ALL, SEMANTIC, KEYWORD }

    data class Result(val items: List<MemoryItem>, val mode: Mode, val note: String = "")

    /** Contagem desde que o app abriu, para o relatório. */
    data class Stats(val all: Int, val semantic: Int, val keyword: Int, val avgQueryMs: Long)

    private var nAll = 0
    private var nSemantic = 0
    private var nKeyword = 0
    private var queryNanos = 0L

    @Synchronized
    fun stats(): Stats = Stats(nAll, nSemantic, nKeyword, if (nSemantic == 0) 0 else queryNanos / nSemantic / 1_000_000)

    /** As linhas prontas para o prompt: `(categoria) texto`, ou só o texto na categoria "geral" (formato de sempre). */
    suspend fun relevantLines(query: String, k: Int = DEFAULT_K, maxChars: Int = DEFAULT_MAX_CHARS): List<String> =
        relevant(query, k, maxChars).items.map(::promptLine)

    suspend fun relevant(query: String, k: Int = DEFAULT_K, maxChars: Int = DEFAULT_MAX_CHARS): Result {
        val all = memories()
        if (!enabled()) {
            synchronized(this) { nAll++ }
            return Result(all, Mode.ALL)
        }
        if (all.isEmpty() || query.isBlank()) return Result(emptyList(), Mode.KEYWORD, "nada para buscar")
        val semantic = try {
            semantic(query, all, k, maxChars)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onNote("memória por sentido falhou (${e.javaClass.simpleName}: ${e.message}); usando palavras")
            null
        }
        if (semantic != null) return semantic
        return keyword(query, all, k, maxChars)
    }

    private suspend fun semantic(query: String, all: List<MemoryItem>, k: Int, maxChars: Int): Result? {
        if (index.size() == 0) return null.also { onNote("sem vetores ainda; usando palavras") }
        val t0 = nanoTime()
        val queryVector = withTimeoutOrNull(timeoutMs) {
            val e = embedder() ?: return@withTimeoutOrNull null
            e.embed(listOf(query), EmbedRole.QUERY).singleOrNull()
        }
        if (queryVector == null) return null.also { onNote("sem o modelo ou tempo esgotado (${timeoutMs} ms); usando palavras") }
        val q = VectorMath.truncate(queryVector, index.dims)
        val elapsed = nanoTime() - t0
        val vectors = index.vectors()
        // Vetor só vale se o texto da memória é o mesmo de quando ele foi calculado.
        val fresh = all.filter { vectors.containsKey(it.id) && index.hashOf(it.id) == EmbeddingIndexStore.hash(it.text) }
        val byId = fresh.associateBy { it.id }
        val ranked = VectorMath.topK(q, vectors.filterKeys { it in byId }, k, minScore).mapNotNull { byId[it.id] }
        // Memórias novas, ainda sem vetor, entram por palavras para não ficarem invisíveis.
        val freshIds = byId.keys
        val extra = rankByWords(query, all.filter { it.id !in freshIds }).take((k - ranked.size).coerceAtLeast(0))
        synchronized(this) {
            nSemantic++
            queryNanos += elapsed
        }
        return Result(capChars(ranked + extra, maxChars), Mode.SEMANTIC)
    }

    private fun keyword(query: String, all: List<MemoryItem>, k: Int, maxChars: Int): Result {
        synchronized(this) { nKeyword++ }
        val byWords = rankByWords(query, all).take(k)
        // Sem nenhuma palavra em comum: manda as mais recentes, para o cérebro não ficar sem contexto algum.
        val picked = byWords.ifEmpty { all.sortedByDescending { it.createdAtMillis }.take(k) }
        return Result(capChars(picked, maxChars), Mode.KEYWORD)
    }

    private fun rankByWords(query: String, items: List<MemoryItem>): List<MemoryItem> =
        items.map { it to KeywordScore.score(query, it.text + " " + it.category) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryItem, Int>> { it.second }.thenByDescending { it.first.createdAtMillis })
            .map { it.first }

    companion object {
        const val DEFAULT_K = 8
        const val DEFAULT_MAX_CHARS = 900
        const val DEFAULT_TIMEOUT_MS = 3_000L

        /**
         * PROVISÓRIO: não calibrado com o modelo real (não deu para rodá-lo no ambiente de desenvolvimento). Valor baixo de propósito:
         * o limite de [DEFAULT_K] memórias já segura o tamanho do prompt. Calibrar no aparelho (ver docs/FASE-3.md).
         */
        const val DEFAULT_MIN_SCORE = 0.30f

        fun promptLine(item: MemoryItem): String = if (item.category == "geral") item.text else "(${item.category}) ${item.text}"

        /** Mantém a ordem e corta quando passaria de [maxChars]; a primeira (a mais relevante) sempre entra. */
        fun capChars(items: List<MemoryItem>, maxChars: Int): List<MemoryItem> {
            val out = ArrayList<MemoryItem>()
            var used = 0
            for (item in items) {
                val len = promptLine(item).length + 1
                if (out.isNotEmpty() && used + len > maxChars) break
                out += item
                used += len
            }
            return out
        }
    }
}
