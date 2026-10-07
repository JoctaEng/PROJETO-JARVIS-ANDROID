package com.joctaeng.jarvis.mind.llama

import com.joctaeng.jarvis.core.contracts.GenerationStats
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.contracts.LocalLlmProvider
import com.joctaeng.jarvis.core.model.Capability
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cérebro local via llama.cpp para modelos `.gguf` (ADR 0009). O motor nativo guarda um modelo por vez
 * e reaproveita o começo da conversa já lido (cache de prefixo), então respostas seguidas saem mais rápido.
 */
class LlamaCppProvider(private val modelFile: File) : LocalLlmProvider {

    override val id: String = "local-llamacpp"
    override val displayName: String = "IA do celular (${modelFile.nameWithoutExtension})"
    override val location = ProviderLocation.ON_DEVICE
    override val capabilities = setOf(Capability.TEXT, Capability.STREAMING)

    private val loaded get() = owner === this

    /** Qwen3 "pensa" em voz alta antes de responder; desligado, a resposta chega muito antes. */
    private val noThinking = modelFile.name.contains("qwen3", ignoreCase = true)

    override suspend fun isAvailable(context: DeviceContext): Boolean = modelFile.isFile && LlamaNative.loadError == null

    suspend fun load() = lock.withLock { loadLocked() }

    val isLoaded: Boolean get() = loaded

    private suspend fun loadLocked() {
        if (loaded) return
        LlamaNative.loadError?.let { error("Motor local indisponível: $it") }
        withContext(Dispatchers.IO) {
            when (LlamaNative.nativeLoad(modelFile.absolutePath, CONTEXT, THREADS, THREADS_BATCH, BATCH)) {
                0 -> owner = this@LlamaCppProvider
                -1 -> error("Não consegui abrir o modelo ${modelFile.name} (arquivo incompleto ou formato não suportado)")
                else -> error("Memória insuficiente para o contexto do modelo")
            }
        }
    }

    override suspend fun unload() = lock.withLock {
        if (loaded) {
            withContext(Dispatchers.IO) { LlamaNative.nativeUnload() }
            owner = null
        }
    }

    override fun generate(request: LlmRequest): Flow<LlmChunk> = callbackFlow {
        val cancelled = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            lock.withLock {
                loadLocked()
                val messages = buildList {
                    if (request.systemPrompt.isNotBlank()) add("system" to request.systemPrompt)
                    request.messages.forEach { m ->
                        when (m.role) {
                            Role.USER -> add("user" to m.text)
                            Role.ASSISTANT -> add("assistant" to m.text)
                            Role.SYSTEM, Role.TOOL -> Unit
                        }
                    }
                }
                val start = System.nanoTime()
                var chars = 0
                var chunks = 0
                val code = LlamaNative.nativeGenerate(
                    messages.map { it.first }.toTypedArray(),
                    messages.map { it.second }.toTypedArray(),
                    request.maxOutputTokens ?: MAX_TOKENS,
                    TEMPERATURE,
                    noThinking,
                ) { piece ->
                    if (cancelled.get()) return@nativeGenerate false
                    chars += piece.length
                    chunks++
                    trySendBlocking(LlmChunk.Text(piece)).isSuccess
                }
                val m = LlamaNative.nativeMetrics()
                when {
                    code >= 0 -> send(
                        LlmChunk.Done(
                            GenerationStats(
                                timeToFirstChunkMillis = m[3],
                                totalMillis = (System.nanoTime() - start) / 1_000_000,
                                chunkCount = chunks,
                                charCount = chars,
                            ),
                        ),
                    )
                    code == -6 -> send(LlmChunk.Error("A conversa ficou longa demais para a IA do celular. Limpe a conversa e tente de novo."))
                    else -> send(LlmChunk.Error("A IA do celular falhou (código $code)"))
                }
            }
            channel.close()
        }
        awaitClose {
            cancelled.set(true)
            job.cancel()
        }
    }.catch { e ->
        emit(LlmChunk.Error(e.message ?: e::class.simpleName ?: "erro na IA do celular", e))
    }.flowOn(Dispatchers.IO)

    private companion object {
        /** O motor nativo guarda um modelo só, para o app inteiro: só quem carregou pode descarregar. */
        val lock = Mutex()
        @Volatile var owner: LlamaCppProvider? = null

        /** Valores medidos no EduMath no mesmo aparelho (Redmi Note 13 Pro+): 2 threads geram mais rápido que 4. */
        const val CONTEXT = 4096
        const val THREADS = 2
        const val THREADS_BATCH = 4
        const val BATCH = 512
        const val MAX_TOKENS = 400
        const val TEMPERATURE = 0.7f
    }
}
