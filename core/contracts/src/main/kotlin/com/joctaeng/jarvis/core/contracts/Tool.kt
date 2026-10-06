package com.joctaeng.jarvis.core.contracts

import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult

/**
 * Contrato de toda ferramenta: conectores nativos, ferramentas MCP e skills
 * (seção 5.3 e 9). Toda execução passa pelo Policy Engine antes.
 */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema dos argumentos, como texto (compatível com MCP). */
    val inputSchemaJson: String
    val risk: RiskLevel

    suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult
}

/** Contexto de execução — quem pediu e por quê (vai para o Audit Log). */
data class ToolContext(
    val sessionId: String,
    val reason: String,
)
