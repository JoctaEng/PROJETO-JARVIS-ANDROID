package com.joctaeng.jarvis.embed

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.joctaeng.jarvis.mind.embed.EmbedPrompts
import com.joctaeng.jarvis.mind.embed.EmbedRole
import com.joctaeng.jarvis.mind.embed.TextEmbedder
import com.joctaeng.jarvis.mind.embed.VectorMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Provedor do modelo de embedding local via LiteRT-LM (seção 4 da especificação).
 *
 * Características:
 * - Carrega o [EmbeddingEngine] sob demanda e fecha após 60 segundos sem uso.
 * - Roda sempre em thread secundária (Dispatchers.IO).
 * - Trunca o vetor para 256 dimensões (Matryoshka) com normalização L2.
 * - Registra métricas de tempo de carga e de consulta para o relatório de diagnóstico.
 */
class EmbeddingRuntime(
    private val modelFile: File,
    private val scope: CoroutineScope,
    override val dims: Int = 256,
) : TextEmbedder {

    override val modelId: String = "litert-community/embeddinggemma-2-text-270m-litert-lm"

    private val lock = Mutex()
    private var engine: EmbeddingEngine? = null
    private var idleJob: Job? = null

    // Métricas para o relatório
    @Volatile var lastLoadTimeMs: Long = 0L
        private set
    @Volatile var totalQueries: Long = 0L
        private set
    @Volatile var totalQueryTimeMs: Long = 0L
        private set

    val isLoaded: Boolean get() = engine != null

    val isAvailable: Boolean get() = modelFile.isFile && modelFile.length() > 0

    val avgQueryTimeMs: Long
        get() = if (totalQueries > 0) totalQueryTimeMs / totalQueries else 0L

    /**
     * Gera os vetores para uma lista de textos.
     * Retorna a lista de FloatArray com [dims] dimensões normalizadas.
     */
    override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val preparedTexts = texts.map { EmbedPrompts.format(it, role) }

        return withTimeoutOrNull(3_000L) {
            val start = System.currentTimeMillis()
            val vectors = lock.withLock {
                idleJob?.cancel()
                val eng = ensureEngine()
                withContext(Dispatchers.IO) {
                    val opts = EmbeddingOptions(outputSize = dims)
                    val response = eng.computeEmbeddingBatch(preparedTexts, opts)
                    response.embeddings.map { raw ->
                        val floatArray = raw.floatArray
                        VectorMath.truncate(floatArray, dims)
                    }
                }
            }
            val elapsed = System.currentTimeMillis() - start
            totalQueries++
            totalQueryTimeMs += elapsed
            scheduleUnload()
            vectors
        } ?: throw kotlinx.coroutines.TimeoutCancellationException("Tempo esgotado (3s) gerando embeddings")
    }

    private suspend fun ensureEngine(): EmbeddingEngine = withContext(Dispatchers.IO) {
        engine?.let { return@withContext it }
        val start = System.currentTimeMillis()
        val config = EmbeddingEngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
        )
        val created = EmbeddingEngine(config)
        try {
            created.initialize()
        } catch (e: Exception) {
            runCatching { created.close() }
            throw IllegalStateException("Falha ao inicializar o motor de embeddings LiteRT: ${e.message}", e)
        }
        engine = created
        lastLoadTimeMs = System.currentTimeMillis() - start
        created
    }

    private fun scheduleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(60_000L)
            if (isActive) {
                close()
            }
        }
    }

    suspend fun close() = lock.withLock {
        idleJob?.cancel()
        idleJob = null
        withContext(Dispatchers.IO) {
            runCatching { engine?.close() }
            engine = null
        }
    }
}
