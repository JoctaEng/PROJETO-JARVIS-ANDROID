package com.joctaeng.jarvis.core.contracts

import com.joctaeng.jarvis.core.model.Capability
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.ToolCall
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de qualquer "cérebro" (seção 5.3). O personagem nunca conhece
 * implementações concretas — só esta interface, via orquestrador.
 */
interface LlmProvider {
    val id: String
    val displayName: String
    val location: ProviderLocation
    val capabilities: Set<Capability>

    /** O provedor pode atender agora? (modelo baixado, chave configurada, rede etc.) */
    suspend fun isAvailable(context: DeviceContext): Boolean

    /** Gera a resposta em streaming. O fluxo termina com [LlmChunk.Done] ou [LlmChunk.Error]. */
    fun generate(request: LlmRequest): Flow<LlmChunk>
}

data class LlmRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val maxOutputTokens: Int? = null,
)

sealed interface LlmChunk {
    data class Text(val text: String) : LlmChunk
    data class ToolRequest(val call: ToolCall) : LlmChunk
    data class Done(val stats: GenerationStats? = null) : LlmChunk
    data class Error(val message: String, val cause: Throwable? = null) : LlmChunk
}

/** Métricas medidas pelo provedor — usadas nos benchmarks da Fase 0. */
data class GenerationStats(
    val timeToFirstChunkMillis: Long,
    val totalMillis: Long,
    val chunkCount: Int,
    val charCount: Int,
)
