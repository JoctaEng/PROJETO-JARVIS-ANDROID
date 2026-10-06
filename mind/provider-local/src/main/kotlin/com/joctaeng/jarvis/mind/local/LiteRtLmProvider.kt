package com.joctaeng.jarvis.mind.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.joctaeng.jarvis.core.contracts.GenerationStats
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmProvider
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.Capability
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class LocalBackend { CPU, GPU }

/**
 * Cérebro local via LiteRT-LM (seção 7.1). Carrega o modelo sob demanda e
 * permite descarregar a qualquer momento (regra "um modelo pesado por vez").
 *
 * @param modelFile arquivo `.litertlm` já presente no aparelho.
 */
class LiteRtLmProvider(
    private val modelFile: File,
    private val backend: LocalBackend,
    private val cacheDir: File,
) : LlmProvider {

    override val id: String = "local-litertlm-${backend.name.lowercase()}"
    override val displayName: String = "IA local (${modelFile.nameWithoutExtension}, ${backend.name})"
    override val location = ProviderLocation.ON_DEVICE
    override val capabilities = setOf(Capability.TEXT, Capability.STREAMING)

    private val lock = Mutex()
    private var engine: Engine? = null

    val isLoaded: Boolean get() = engine != null

    override suspend fun isAvailable(context: DeviceContext): Boolean = modelFile.isFile

    /** Carrega o modelo e devolve o tempo gasto em ms (0 se já estava carregado). */
    suspend fun load(): Long = lock.withLock {
        if (engine != null) return@withLock 0L
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = when (backend) {
                    LocalBackend.CPU -> Backend.CPU()
                    LocalBackend.GPU -> Backend.GPU()
                },
                cacheDir = cacheDir.absolutePath,
            )
            val created = Engine(config)
            try {
                created.initialize()
            } catch (e: Exception) {
                created.close()
                throw e
            }
            engine = created
            (System.nanoTime() - start) / 1_000_000
        }
    }

    /** Libera a memória do modelo (chamado também em onTrimMemory). */
    suspend fun unload() = lock.withLock {
        engine?.close()
        engine = null
    }

    override fun generate(request: LlmRequest): Flow<LlmChunk> = flow {
        load()
        val loaded = checkNotNull(engine) { "Modelo não carregado" }
        val lastUser = request.messages.lastOrNull { it.role == Role.USER }
            ?: error("Nenhuma mensagem do usuário no pedido")
        val history = request.messages.takeWhile { it !== lastUser }.mapNotNull { it.toLiteRt() }

        val config = ConversationConfig(
            systemInstruction = request.systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
            initialMessages = history,
            maxOutputToken = request.maxOutputTokens,
        )

        val start = System.nanoTime()
        var firstChunkAt = -1L
        var chunks = 0
        var chars = 0
        loaded.createConversation(config).use { conversation ->
            conversation.sendMessageAsync(lastUser.text).collect { message ->
                val text = message.toString()
                if (text.isEmpty()) return@collect
                if (firstChunkAt < 0) firstChunkAt = System.nanoTime()
                chunks++
                chars += text.length
                emit(LlmChunk.Text(text))
            }
        }
        val end = System.nanoTime()
        emit(
            LlmChunk.Done(
                GenerationStats(
                    timeToFirstChunkMillis = if (firstChunkAt < 0) -1 else (firstChunkAt - start) / 1_000_000,
                    totalMillis = (end - start) / 1_000_000,
                    chunkCount = chunks,
                    charCount = chars,
                ),
            ),
        )
    }.catch { e ->
        emit(LlmChunk.Error(e.message ?: e::class.simpleName ?: "erro no modelo local", e))
    }.flowOn(Dispatchers.IO)

    private fun ChatMessage.toLiteRt(): Message? = when (role) {
        Role.USER -> Message.user(text)
        Role.ASSISTANT -> Message.model(text)
        Role.SYSTEM, Role.TOOL -> null
    }
}
