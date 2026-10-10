package com.joctaeng.jarvis.action.mcp

import com.joctaeng.jarvis.core.contracts.ToolContext
import com.joctaeng.jarvis.core.model.RiskLevel
import com.joctaeng.jarvis.core.model.ToolResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Servidor MCP mínimo, igual ao que o EduMath vai expor. */
private fun fakeServer(request: String): String? {
    val msg = Json.parseToJsonElement(request).jsonObject
    val id = msg["id"] ?: return null
    val method = msg["method"]!!.jsonPrimitive.content
    val result = when (method) {
        "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"EduMath","version":"1.0.9"},"instructions":"Use as ferramentas do EduMath."}"""
        "tools/list" -> """{"tools":[
            {"name":"listar_turmas","description":"Turmas ativas","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}},
            {"name":"lancar_nota","description":"Lança nota no EduMath","inputSchema":{"type":"object","properties":{"aluno":{"type":"string"}}}},
            {"name":"apagar_turma","description":"Apaga","inputSchema":{"type":"object"},"annotations":{"destructiveHint":true}}]}"""
        "tools/call" -> {
            val name = msg["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
            if (name == "lancar_nota") return """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"Aluno não encontrado"}],"isError":true}}"""
            if (name == "app_fechado") return """{"jsonrpc":"2.0","id":$id,"error":{"code":-32001,"message":"EduMath fechado"}}"""
            """{"content":[{"type":"text","text":"2 turmas"}],"structuredContent":{"turmas":["ENF1","RAD2"]}}"""
        }
        else -> return """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"método desconhecido"}}"""
    }
    return """{"jsonrpc":"2.0","id":$id,"result":$result}"""
}

private class LoopbackTransport : McpTransport {
    private var sink: ((String) -> Unit)? = null
    val sent = mutableListOf<String>()
    override suspend fun open(onMessage: (String) -> Unit) { sink = onMessage }
    override suspend fun send(message: String) {
        sent += message
        fakeServer(message)?.let { reply -> Thread { sink?.invoke(reply) }.start() }
    }
    override fun close() { sink = null }
}

private class Handle(private val client: McpClient) : McpServerHandle {
    override val id = "edumath"
    override val displayName = "EduMath"
    override suspend fun call(toolName: String, argumentsJson: String) = client.callTool(toolName, argumentsJson)
}

class McpClientTest {
    private var http: HttpServer? = null

    @AfterTest fun stop() { http?.stop(0) }

    @Test fun handshakeListAndCallOverLoopback() = runBlocking {
        val transport = LoopbackTransport()
        val client = McpClient(transport)
        client.initialize()
        assertEquals("EduMath", client.serverName)
        assertTrue(transport.sent.any { "notifications/initialized" in it })

        val tools = client.listTools()
        assertEquals(listOf("listar_turmas", "lancar_nota", "apagar_turma"), tools.map { it.name })
        val handle = Handle(client)
        val adapted = tools.map { McpTool(handle, it, McpNames.qualified("edumath", it.name)) }
        assertEquals(listOf(RiskLevel.READ, RiskLevel.WRITE_REVERSIBLE, RiskLevel.CRITICAL), adapted.map { it.risk })
        assertEquals("edumath_listar_turmas", adapted[0].name)

        val ok = adapted[0].execute("{}", ToolContext("s", "r"))
        assertIs<ToolResult.Success>(ok)
        assertEquals("""{"turmas":["ENF1","RAD2"]}""", ok.outputJson)
        assertIs<ToolResult.Failure>(adapted[1].execute("""{"aluno":"x"}""", ToolContext("s", "r")))

        val e = assertFailsWith<McpException> { client.callTool("app_fechado", "{}") }
        assertEquals(McpClient.APP_NOT_READY, e.code)
    }

    @Test fun streamableHttpWithJsonAndSse() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http = it }
        server.createContext("/mcp") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            val reply = fakeServer(body)
            if (reply == null) {
                ex.sendResponseHeaders(202, -1)
            } else {
                val method = Json.parseToJsonElement(body).jsonObject["method"]!!.jsonPrimitive.content
                val sse = method == "tools/list"
                // SSE: cada linha do dado leva o prefixo "data:" (a resposta de tools/list tem quebras de linha).
                val out = if (sse) "event: message\n" + reply.lines().joinToString("\n") { "data: $it" } + "\n\n" else reply
                ex.responseHeaders.add("Content-Type", if (sse) "text/event-stream" else "application/json")
                ex.responseHeaders.add("Mcp-Session-Id", "sessao-1")
                ex.sendResponseHeaders(200, out.toByteArray().size.toLong())
                ex.responseBody.use { it.write(out.toByteArray()) }
            }
            ex.close()
        }
        server.start()
        val client = McpClient(HttpMcpTransport("http://127.0.0.1:${server.address.port}/mcp"), timeoutMillis = 5_000)
        client.initialize()
        assertEquals(3, client.listTools().size)
        assertEquals("2 turmas", client.callTool("listar_turmas", "{}").text)
    }

    @Test fun qualifiedNamesAreSafeAndNotDoubled() = runTest {
        assertEquals("edumath_listar_turmas", McpNames.qualified("EduMath", "listar_turmas"))
        assertEquals("edumath_listar_turmas", McpNames.qualified("edumath", "edumath_listar_turmas"))
        assertEquals("meu_pc_abrir_arquivo", McpNames.qualified("Meu PC", "abrir.arquivo"))
    }

    @Test fun recognizesAppClosedErrors() {
        assertTrue(McpClient.saysAppClosed(McpCallResult("O EduMath está fechado. Abra o app.", null, true)))
        assertTrue(McpClient.saysAppClosed(McpCallResult("app not ready", null, true)))
        kotlin.test.assertFalse(McpClient.saysAppClosed(McpCallResult("O EduMath está fechado", null, false)))
        kotlin.test.assertFalse(McpClient.saysAppClosed(McpCallResult("turma não encontrada", null, true)))
    }
}
