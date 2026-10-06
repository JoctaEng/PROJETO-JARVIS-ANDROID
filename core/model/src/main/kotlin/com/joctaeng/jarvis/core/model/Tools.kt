package com.joctaeng.jarvis.core.model

/** Risco de uma ação (seção 10.2). */
enum class RiskLevel { READ, WRITE_REVERSIBLE, CRITICAL }

/** Níveis de autonomia (item 32 da especificação). */
enum class AutonomyLevel(val level: Int) {
    OBSERVER(0), ASSISTANT(1), OPERATOR(2), CONTROLLED_AUTONOMOUS(3), PERSONAL_AGENT(4),
}

/** Pedido de execução de ferramenta vindo do cérebro. Argumentos em JSON (texto). */
data class ToolCall(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
)

/** Resultado honesto de uma ferramenta — sucesso nunca é presumido (item 51). */
sealed interface ToolResult {
    data class Success(val outputJson: String) : ToolResult
    data class Failure(val reason: String) : ToolResult
    data class Denied(val reason: String) : ToolResult
    data object Cancelled : ToolResult
}
