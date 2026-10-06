package com.joctaeng.jarvis.action.gateway

import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.AutonomyLevel
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolCall
import com.joctaeng.jarvis.core.model.ToolResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val granted = PermissionState(androidGranted = true, jarvisGranted = true)

class PolicyEngineTest {
    @Test fun criticalAlwaysNeedsConfirmation() {
        AutonomyLevel.entries.filter { it != AutonomyLevel.OBSERVER }.forEach { level ->
            assertEquals(PolicyDecision.RequireConfirmation, PolicyEngine.evaluate(RiskLevel.CRITICAL, level, granted, partOfApprovedSkill = true))
        }
    }

    @Test fun observerNeverExecutes() {
        RiskLevel.entries.forEach { risk ->
            assertIs<PolicyDecision.Deny>(PolicyEngine.evaluate(risk, AutonomyLevel.OBSERVER, granted))
        }
    }

    @Test fun missingPermissionDeniesBeforeAutonomy() {
        val noAndroid = PermissionState(androidGranted = false, jarvisGranted = true)
        val noJarvis = PermissionState(androidGranted = true, jarvisGranted = false)
        assertIs<PolicyDecision.Deny>(PolicyEngine.evaluate(RiskLevel.READ, AutonomyLevel.PERSONAL_AGENT, noAndroid))
        assertIs<PolicyDecision.Deny>(PolicyEngine.evaluate(RiskLevel.READ, AutonomyLevel.PERSONAL_AGENT, noJarvis))
    }

    @Test fun matrixMatchesRoadmapSection10_2() {
        val write = RiskLevel.WRITE_REVERSIBLE
        assertEquals(PolicyDecision.RequireConfirmation, PolicyEngine.evaluate(RiskLevel.READ, AutonomyLevel.ASSISTANT, granted))
        assertEquals(PolicyDecision.Allow, PolicyEngine.evaluate(RiskLevel.READ, AutonomyLevel.OPERATOR, granted))
        assertEquals(PolicyDecision.RequireConfirmation, PolicyEngine.evaluate(write, AutonomyLevel.OPERATOR, granted))
        assertEquals(PolicyDecision.RequireConfirmation, PolicyEngine.evaluate(write, AutonomyLevel.CONTROLLED_AUTONOMOUS, granted))
        assertEquals(PolicyDecision.Allow, PolicyEngine.evaluate(write, AutonomyLevel.CONTROLLED_AUTONOMOUS, granted, partOfApprovedSkill = true))
        assertEquals(PolicyDecision.Allow, PolicyEngine.evaluate(write, AutonomyLevel.PERSONAL_AGENT, granted))
    }
}

private class FakeTool(override val risk: RiskLevel, private val behavior: () -> ToolResult) : Tool {
    var executions = 0
    override val name = "fake.tool"
    override val description = "ferramenta de teste"
    override val inputSchemaJson = "{}"
    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        executions++
        return behavior()
    }
}

class ToolGatewayTest {
    private val call = ToolCall("1", "fake.tool", "{}")
    private val ctx = ToolContext("s1", "pedido do usuário")

    private fun gateway(tool: FakeTool, log: AuditLog, autonomy: AutonomyLevel = AutonomyLevel.OPERATOR) =
        ToolGateway(listOf(tool), log, { autonomy }, { granted }, clock = { 42L })

    @Test fun cancelledConfirmationDoesNotExecuteAndIsLogged() = runTest {
        val tool = FakeTool(RiskLevel.CRITICAL) { ToolResult.Success("{}") }
        val log = InMemoryAuditLog()
        val result = gateway(tool, log).execute(call, ctx) { _, _ -> false }

        assertEquals(ToolResult.Cancelled, result)
        assertEquals(0, tool.executions)
        assertEquals("cancelado pelo usuário", log.entries.value.single().outcome)
    }

    @Test fun exceptionsBecomeHonestFailures() = runTest {
        val tool = FakeTool(RiskLevel.READ) { error("Agenda indisponível") }
        val log = InMemoryAuditLog()
        val result = gateway(tool, log).execute(call, ctx) { _, _ -> true }

        assertIs<ToolResult.Failure>(result)
        assertTrue(log.entries.value.single().outcome.startsWith("falhou"))
    }

    @Test fun unknownToolFailsAndIsLogged() = runTest {
        val log = InMemoryAuditLog()
        val result = gateway(FakeTool(RiskLevel.READ) { ToolResult.Success("{}") }, log)
            .execute(call.copy(toolName = "nao.existe"), ctx) { _, _ -> true }
        assertIs<ToolResult.Failure>(result)
        assertEquals("nao.existe", log.entries.value.single().toolName)
    }

    @Test fun everyExecutionIsAudited() = runTest {
        val tool = FakeTool(RiskLevel.READ) { ToolResult.Success("{\"ok\":true}") }
        val log = InMemoryAuditLog()
        gateway(tool, log).execute(call, ctx) { _, _ -> error("não deveria pedir confirmação") }
        val entry = log.entries.value.single()
        assertEquals(AuditEntry(42L, "fake.tool", "pedido do usuário", "sucesso"), entry)
    }
}
