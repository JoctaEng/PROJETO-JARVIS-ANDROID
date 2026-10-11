package com.joctaeng.jarvis.action.gateway

import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.core.model.ToolCall
import com.joctaeng.jarvis.core.model.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** O que acontece numa resposta com ferramentas, na ordem em que acontece. */
sealed interface AgentEvent {
    data class Text(val text: String) : AgentEvent

    /** Texto que o cérebro escreveu junto com um pedido de ferramenta ("vou tentar…"): não é a resposta final. */
    data class Aside(val text: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data object Done : AgentEvent
}

/**
 * Laço do agente: gera → executa as ferramentas pedidas (sempre pelo [ToolGateway]) → devolve o resultado
 * ao cérebro → gera de novo, até o cérebro responder sem pedir ferramenta ou chegar a [maxRounds].
 */
class AgentRunner(
    private val gateway: ToolGateway,
    private val maxRounds: Int = 4,
) {
    fun run(
        history: List<ChatMessage>,
        generate: (List<ChatMessage>) -> Flow<LlmChunk>,
        context: ToolContext,
        confirm: suspend (Tool, ToolCall) -> Boolean,
        /**
         * Segura o texto de cada rodada até saber se ela pede ferramenta: se pedir, vira [AgentEvent.Aside] (não é
         * falado nem mostrado como resposta); se não, sai como a resposta. Assim o usuário ouve só o resultado final,
         * não cada tentativa (pedido 65). Custa o streaming da resposta quando há ferramentas no prompt.
         */
        holdToolRoundText: Boolean = false,
        /**
         * Confere a resposta final ([HonestyCheck]): ferramenta "fingida" em texto ou ação dita sem ferramenta. Uma vez
         * por pedido, devolve a correção ao cérebro em vez de mostrar/falar a mentira. Só vale com [holdToolRoundText].
         */
        verifyClaims: Boolean = false,
    ): Flow<AgentEvent> = flow {
        val messages = history.toMutableList()
        var counter = 0
        var anySuccess = false
        var lastSuccess: String? = null
        var corrected = false
        for (round in 1..maxRounds) {
            val filter = ToolCallFilter { "call-${++counter}" }
            val raw = StringBuilder()
            val held = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            var failed: String? = null
            generate(messages).collect { chunk ->
                when (chunk) {
                    is LlmChunk.Text -> {
                        raw.append(chunk.text)
                        val out = filter.feed(chunk.text)
                        if (out.visible.isNotEmpty()) {
                            if (holdToolRoundText) held.append(out.visible) else emit(AgentEvent.Text(out.visible))
                        }
                        calls += out.calls
                    }
                    is LlmChunk.Error -> failed = chunk.message
                    is LlmChunk.ToolRequest -> calls += chunk.call
                    is LlmChunk.Done -> Unit
                }
            }
            val tail = filter.finish()
            if (tail.visible.isNotEmpty()) {
                if (holdToolRoundText) held.append(tail.visible) else emit(AgentEvent.Text(tail.visible))
            }
            calls += tail.calls
            if (verifyClaims && holdToolRoundText && !corrected && failed == null && calls.isEmpty() && round < maxRounds) {
                val fix = HonestyCheck.problem(held.toString(), anySuccess, lastSuccess)
                if (fix != null) {
                    corrected = true
                    emit(AgentEvent.Aside("corrigido antes de responder: ${held.toString().trim().take(200)}"))
                    messages += ChatMessage(Role.ASSISTANT, raw.toString())
                    messages += ChatMessage(Role.USER, fix)
                    continue
                }
            }
            if (held.isNotBlank()) {
                if (calls.isEmpty() || failed != null) emit(AgentEvent.Text(held.toString())) else emit(AgentEvent.Aside(held.toString().trim()))
            }
            failed?.let {
                emit(AgentEvent.Error(it))
                return@flow
            }
            if (calls.isEmpty()) {
                emit(AgentEvent.Done)
                return@flow
            }
            if (round == maxRounds) {
                emit(AgentEvent.Error("Parei: muitas ferramentas seguidas numa resposta só."))
                return@flow
            }
            messages += ChatMessage(Role.ASSISTANT, raw.toString())
            val responses = calls.map { call ->
                emit(AgentEvent.ToolStarted(call))
                val result = gateway.execute(call, context, confirm = confirm)
                if (result is ToolResult.Success) {
                    anySuccess = true
                    lastSuccess = call.toolName
                }
                emit(AgentEvent.ToolFinished(call, result))
                ToolProtocol.responseMessage(call, result)
            }
            messages += ChatMessage(Role.USER, responses.joinToString("\n"))
        }
    }
}
