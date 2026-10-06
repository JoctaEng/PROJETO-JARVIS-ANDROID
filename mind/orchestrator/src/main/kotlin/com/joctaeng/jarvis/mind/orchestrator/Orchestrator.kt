package com.joctaeng.jarvis.mind.orchestrator

import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmProvider
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.DeviceContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Eventos que a camada de presença recebe do orquestrador. */
sealed interface OrchestratorEvent {
    data class Notice(val text: String) : OrchestratorEvent
    data class RoutedTo(val providerId: String) : OrchestratorEvent
    data class FellBack(val fromProviderId: String, val reason: String) : OrchestratorEvent
    data class Chunk(val chunk: LlmChunk) : OrchestratorEvent
    data class Failed(val reason: String) : OrchestratorEvent
}

/**
 * AI Orchestrator (seção 7). Escolhe o cérebro pela [RoutingPolicy] e, se um
 * provedor falhar *antes* de produzir texto, tenta o próximo (regra 7), sempre
 * avisando. Se falhar no meio da resposta, não troca de cérebro em silêncio:
 * reporta a falha.
 */
class Orchestrator(
    private val providers: List<LlmProvider>,
    private val policy: RoutingPolicy = RoutingPolicy(),
) {
    fun respond(request: LlmRequest, device: DeviceContext, hints: RoutingHints = RoutingHints()): Flow<OrchestratorEvent> = flow {
        val infos = providers.map {
            ProviderInfo(it.id, it.location, it.capabilities, available = it.isAvailable(device))
        }
        val plan = policy.plan(infos, device, hints)
        plan.notices.forEach { emit(OrchestratorEvent.Notice(it)) }
        if (plan.isEmpty) {
            emit(OrchestratorEvent.Failed("Nenhum cérebro disponível."))
            return@flow
        }

        val byId = providers.associateBy { it.id }
        for (providerId in plan.orderedProviderIds) {
            val provider = byId.getValue(providerId)
            emit(OrchestratorEvent.RoutedTo(providerId))
            var producedText = false
            var failure: String? = null
            try {
                provider.generate(request).collect { chunk ->
                    when (chunk) {
                        is LlmChunk.Error -> failure = chunk.message
                        else -> {
                            if (chunk is LlmChunk.Text) producedText = true
                            if (failure == null) emit(OrchestratorEvent.Chunk(chunk))
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failure = e.message ?: e::class.simpleName ?: "erro desconhecido"
            }

            val error = failure ?: return@flow
            if (producedText) {
                emit(OrchestratorEvent.Failed("A resposta foi interrompida: $error"))
                return@flow
            }
            emit(OrchestratorEvent.FellBack(providerId, error))
        }
        emit(OrchestratorEvent.Failed("Todos os cérebros disponíveis falharam."))
    }
}
