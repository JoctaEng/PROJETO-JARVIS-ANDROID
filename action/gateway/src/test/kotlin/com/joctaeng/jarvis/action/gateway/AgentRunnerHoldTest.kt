package com.joctaeng.jarvis.action.gateway

import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.model.ToolResult
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.Role
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRunnerHoldTest {
    @Test fun attemptsAreSilentAndOnlyTheResultIsSaid() = runTest {
        val gateway = ToolGateway(
            listOf(HoldFakeTool("tela_tocar")), InMemoryAuditLog(),
            autonomy = { AutonomyLevel.OPERATOR }, permissions = { PermissionState(true, true) },
        )
        var round = 0
        val events = AgentRunner(gateway).run(
            listOf(ChatMessage(Role.USER, "toca no dia 15")),
            { _ ->
                round++
                flow {
                    if (round == 1) emit(LlmChunk.Text("Vou tentar tocar no 15. <tool_call>{\"name\":\"tela_tocar\",\"arguments\":{\"nome\":\"15\"}}</tool_call>"))
                    else emit(LlmChunk.Text("Pronto, abri o dia 15."))
                    emit(LlmChunk.Done())
                }
            },
            ToolContext("s", "r"),
            { _, _ -> true },
            holdToolRoundText = true,
        ).toList()
        assertEquals("Pronto, abri o dia 15.", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.text })
        assertEquals("Vou tentar tocar no 15.", events.filterIsInstance<AgentEvent.Aside>().single().text)
    }
}

private class HoldFakeTool(override val name: String) : Tool {
    override val risk = RiskLevel.WRITE_REVERSIBLE
    override val description = "teste"
    override val inputSchemaJson = "{\"type\":\"object\"}"
    override suspend fun execute(argumentsJson: String, context: ToolContext) = ToolResult.Success("{\"ok\":true}")
}
