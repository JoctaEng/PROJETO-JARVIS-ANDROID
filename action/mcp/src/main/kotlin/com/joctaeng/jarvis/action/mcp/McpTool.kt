package com.joctaeng.jarvis.action.mcp

import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ferramenta de um servidor MCP vista pelo Euno como qualquer outra: passa pelo Policy Engine e pelo Audit Log.
 * Risco vem das anotações do servidor (readOnlyHint → leitura; destructiveHint → crítica; senão escrita reversível).
 */
class McpTool(
    private val server: McpServerHandle,
    private val info: McpToolInfo,
    override val name: String,
) : Tool {
    override val description: String = buildString {
        append("[").append(server.displayName).append("] ")
        append(info.description.ifBlank { info.title ?: info.name })
    }
    override val inputSchemaJson: String = info.inputSchemaJson
    override val risk: RiskLevel = when {
        info.destructive -> RiskLevel.CRITICAL
        info.readOnly -> RiskLevel.READ
        else -> RiskLevel.WRITE_REVERSIBLE
    }

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        val result = server.call(info.name, argumentsJson)
        if (result.isError) return ToolResult.Failure(result.text.ifBlank { "o ${server.displayName} recusou o pedido" })
        val output = result.structuredJson ?: buildJsonObject { put("texto", JsonPrimitive(result.text)) }.toString()
        return ToolResult.Success(output)
    }
}

/** Um servidor MCP conectado (ou conectável sob demanda). */
interface McpServerHandle {
    val id: String
    val displayName: String
    suspend fun call(toolName: String, argumentsJson: String): McpCallResult
}

object McpNames {
    /** Nome de ferramenta único entre servidores: "edumath_listar_turmas". Sem pontos nem espaços (o cérebro copia exato). */
    fun qualified(serverId: String, toolName: String): String {
        val prefix = serverId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val tool = toolName.replace(Regex("[^A-Za-z0-9_]+"), "_")
        return if (tool.lowercase().startsWith("${prefix}_")) tool else "${prefix}_$tool"
    }
}
