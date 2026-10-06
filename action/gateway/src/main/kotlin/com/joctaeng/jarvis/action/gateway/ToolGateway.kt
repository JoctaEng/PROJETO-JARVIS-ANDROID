package com.joctaeng.jarvis.action.gateway

import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.core.model.ToolCall
import com.joctaeng.jarvis.core.model.ToolResult
import kotlinx.coroutines.CancellationException

/**
 * Tool Gateway (seção 5.1): única porta de saída para ações. Toda chamada passa
 * pelo [PolicyEngine], pede confirmação quando necessário e é registrada no
 * [AuditLog] com o resultado *real* — nunca presume sucesso.
 */
class ToolGateway(
    tools: List<Tool>,
    private val auditLog: AuditLog,
    private val autonomy: () -> AutonomyLevel,
    private val permissions: (Tool) -> PermissionState,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val registry = tools.associateBy { it.name }

    val availableTools: Collection<Tool> get() = registry.values

    /**
     * @param confirm chamado quando a política exige confirmação; deve mostrar
     *   [Cancelar] [Confirmar] ao usuário e devolver a escolha.
     */
    suspend fun execute(
        call: ToolCall,
        context: ToolContext,
        partOfApprovedSkill: Boolean = false,
        confirm: suspend (Tool, ToolCall) -> Boolean,
    ): ToolResult {
        val tool = registry[call.toolName]
            ?: return record(call.toolName, context, ToolResult.Failure("Ferramenta desconhecida: ${call.toolName}"))

        val decision = PolicyEngine.evaluate(tool.risk, autonomy(), permissions(tool), partOfApprovedSkill)
        when (decision) {
            is PolicyDecision.Deny -> return record(tool.name, context, ToolResult.Denied(decision.reason))
            PolicyDecision.RequireConfirmation ->
                if (!confirm(tool, call)) return record(tool.name, context, ToolResult.Cancelled)
            PolicyDecision.Allow -> Unit
        }

        val result = try {
            tool.execute(call.argumentsJson, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: e::class.simpleName ?: "erro desconhecido")
        }
        return record(tool.name, context, result)
    }

    private fun record(toolName: String, context: ToolContext, result: ToolResult): ToolResult {
        val outcome = when (result) {
            is ToolResult.Success -> "sucesso"
            is ToolResult.Failure -> "falhou: ${result.reason}"
            is ToolResult.Denied -> "negado: ${result.reason}"
            ToolResult.Cancelled -> "cancelado pelo usuário"
        }
        auditLog.append(AuditEntry(clock(), toolName, context.reason, outcome))
        return result
    }
}
