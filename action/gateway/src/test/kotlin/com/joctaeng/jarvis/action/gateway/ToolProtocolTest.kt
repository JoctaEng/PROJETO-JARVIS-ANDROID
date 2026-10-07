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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolProtocolTest {
    @Test fun filterHidesCallsEvenWhenTagIsSplitAcrossChunks() {
        var n = 0
        val f = ToolCallFilter { "c${++n}" }
        val chunks = listOf("Vou ligar a lan", "terna. <to", "ol_call>{\"name\": \"lanterna\", \"argu", "ments\": {\"ligar\": true}}</tool", "_call> Pronto")
        val visible = StringBuilder()
        val calls = chunks.flatMap { c -> f.feed(c).also { visible.append(it.visible) }.calls } + f.finish().also { visible.append(it.visible) }.calls
        assertEquals("Vou ligar a lanterna.  Pronto", visible.toString())
        assertEquals(1, calls.size)
        assertEquals("lanterna", calls[0].toolName)
        assertEquals("{\"ligar\":true}", calls[0].argumentsJson)
    }

    @Test fun lessThanSignIsNotSwallowed() {
        val f = ToolCallFilter { "c" }
        val out = f.feed("x < 3 e y <")
        val end = f.finish()
        assertEquals("x < 3 e y <", out.visible + end.visible)
    }

    @Test fun parsesFencedJsonArgumentsAsStringAndMissingClose() {
        val call = ToolProtocol.parseCall("```json\n{\"name\":\"abrir_app\",\"arguments\":\"{\\\"nome\\\":\\\"EduMath\\\"}\"}\n```", "1")!!
        assertEquals("abrir_app", call.toolName)
        assertEquals("EduMath", ToolProtocol.argument(call, "nome"))
        val f = ToolCallFilter { "2" }
        f.feed("<tool_call>{\"name\":\"estado_do_celular\"}")
        assertEquals("estado_do_celular", f.finish().calls.single().toolName)
    }

    @Test fun systemSectionListsTools() {
        val section = ToolProtocol.systemSection(listOf(FakeTool("lanterna", RiskLevel.WRITE_REVERSIBLE)))
        assertTrue("<tool_call>" in section && "\"name\":\"lanterna\"" in section && "\"parameters\":{\"type\":\"object\"}" in section, section)
    }

    @Test fun runnerExecutesToolAndFeedsResultBack() = runTest {
        val log = InMemoryAuditLog()
        val gateway = ToolGateway(
            listOf(FakeTool("lanterna", RiskLevel.WRITE_REVERSIBLE)), log,
            autonomy = { AutonomyLevel.OPERATOR }, permissions = { PermissionState(true, true) },
        )
        val seen = mutableListOf<List<ChatMessage>>()
        val events = AgentRunner(gateway).run(
            history = listOf(ChatMessage(Role.USER, "liga a lanterna")),
            generate = { msgs ->
                seen += msgs
                flow {
                    if (seen.size == 1) emit(LlmChunk.Text("Ok! <tool_call>{\"name\":\"lanterna\",\"arguments\":{\"ligar\":true}}</tool_call>"))
                    else emit(LlmChunk.Text("Lanterna ligada."))
                    emit(LlmChunk.Done())
                }
            },
            context = ToolContext("s", "pedido do usuário"),
            confirm = { _, _ -> true },
        ).toList()
        val text = events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.text }
        assertEquals("Ok! Lanterna ligada.", text)
        assertIs<ToolResult.Success>(events.filterIsInstance<AgentEvent.ToolFinished>().single().result)
        assertTrue(seen[1].last().text.startsWith("<tool_response>{\"name\":\"lanterna\",\"status\":\"sucesso\""), seen[1].last().text)
        assertEquals("sucesso", log.entries.value.single().outcome)
        assertIs<AgentEvent.Done>(events.last())
    }

    @Test fun cancelledConfirmationIsReportedToTheBrain() = runTest {
        val gateway = ToolGateway(
            listOf(FakeTool("criar_alarme", RiskLevel.WRITE_REVERSIBLE)), InMemoryAuditLog(),
            autonomy = { AutonomyLevel.OPERATOR }, permissions = { PermissionState(true, true) },
        )
        val seen = mutableListOf<List<ChatMessage>>()
        AgentRunner(gateway).run(
            listOf(ChatMessage(Role.USER, "alarme às 7")),
            { msgs -> seen += msgs; flow { emit(LlmChunk.Text(if (seen.size == 1) "<tool_call>{\"name\":\"criar_alarme\"}</tool_call>" else "Tudo bem, não criei.")) } },
            ToolContext("s", "r"),
            confirm = { _, _ -> false },
        ).toList()
        assertTrue("\"status\":\"cancelado\"" in seen[1].last().text)
    }

    private class FakeTool(override val name: String, override val risk: RiskLevel) : Tool {
        override val description = "teste"
        override val inputSchemaJson = "{\"type\":\"object\"}"
        override suspend fun execute(argumentsJson: String, context: ToolContext) = ToolResult.Success("{\"ok\":true}")
    }
}
