package com.joctaeng.jarvis.action.gateway

import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.Role
import com.joctaeng.jarvis.core.model.ToolResult
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HonestyCheckTest {
    @Test fun fakeToolTextIsCaught() {
        assertNotNull(HonestyCheck.problem("Consultando sua agenda agora... [usando agenda_consultar] Você não tem nada.", anyToolSucceeded = false))
        assertNotNull(HonestyCheck.problem("[usando tela_ler] pronto", anyToolSucceeded = true))
    }

    @Test fun claimWithoutToolIsCaught() {
        assertNotNull(HonestyCheck.problem("Pronto, Joctã! Já estamos na tela inicial.", false))
        assertNotNull(HonestyCheck.problem("Entendido. Voltando para a tela inicial agora.", false))
        assertNotNull(HonestyCheck.problem("Pronto, rolei a tela do Gmail para baixo.", false))
    }

    @Test fun honestTextPasses() {
        assertNull(HonestyCheck.problem("Pronto, abri o Gmail.", anyToolSucceeded = true))
        assertNull(HonestyCheck.problem("Ainda não abri, quer que eu abra?", false))
        assertNull(HonestyCheck.problem("Tudo bem por aqui! Como posso ajudar?", false))
    }

    @Test fun runnerCorrectsTheBrainOnce() = runTest {
        val gateway = ToolGateway(
            listOf(OkTool("tela_navegar")), InMemoryAuditLog(),
            autonomy = { AutonomyLevel.OPERATOR }, permissions = { PermissionState(true, true) },
        )
        val seen = mutableListOf<List<ChatMessage>>()
        val events = AgentRunner(gateway).run(
            listOf(ChatMessage(Role.USER, "volta para a tela inicial")),
            { msgs ->
                seen += msgs.toList()
                flow {
                    when (seen.size) {
                        1 -> emit(LlmChunk.Text("Pronto, já estamos na tela inicial."))
                        2 -> emit(LlmChunk.Text("<tool_call>{\"name\":\"tela_navegar\",\"arguments\":{\"acao\":\"inicio\"}}</tool_call>"))
                        else -> emit(LlmChunk.Text("Pronto, voltei para a tela inicial."))
                    }
                    emit(LlmChunk.Done())
                }
            },
            ToolContext("s", "r"), { _, _ -> true },
            holdToolRoundText = true, verifyClaims = true,
        ).toList()
        assertEquals("Pronto, voltei para a tela inicial.", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.text })
        assertTrue(seen[1].last().text.startsWith("ATENÇÃO"))
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolFinished>().size)
    }
}

private class OkTool(override val name: String) : Tool {
    override val risk = RiskLevel.WRITE_REVERSIBLE
    override val description = "teste"
    override val inputSchemaJson = "{\"type\":\"object\"}"
    override suspend fun execute(argumentsJson: String, context: ToolContext) = ToolResult.Success("{\"ok\":true}")
}
