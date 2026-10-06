package com.joctaeng.jarvis.mind.cloud

import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.ChatMessage
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Role
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var lastBody = ""
    private var lastAuth: String? = null

    private fun provider(key: String? = "segredo") = OpenAiCompatibleProvider(
        CloudConfig(
            id = "teste", displayName = "Teste", baseUrl = "http://127.0.0.1:${server.address.port}/v1/",
            apiKey = key, model = "modelo-x", location = ProviderLocation.OWN_SERVER,
        ),
    )

    private fun respond(path: String, status: Int, body: String, contentType: String = "application/json") {
        server.createContext(path) { ex ->
            lastBody = ex.requestBody.bufferedReader().readText()
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest fun stop() = server.stop(0)

    private val request = LlmRequest("Você é o JARVIS.", listOf(ChatMessage(Role.USER, "Oi")))

    @Test fun streamsDeltasAndSendsSystemPromptAndKey() = runTest {
        respond(
            "/v1/chat/completions", 200,
            """
            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"content":"[feliz] Oi"}}]}

            data: {"choices":[{"delta":{"content":", Joca!"}}]}

            data: [DONE]

            """.trimIndent(),
            "text/event-stream",
        )
        val chunks = provider().generate(request).toList()
        val text = chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text }
        assertEquals("[feliz] Oi, Joca!", text)
        assertIs<LlmChunk.Done>(chunks.last())
        assertEquals("Bearer segredo", lastAuth)
        assertTrue("\"stream\":true" in lastBody)
        assertTrue("\"role\":\"system\"" in lastBody)
        assertTrue("\"model\":\"modelo-x\"" in lastBody)
    }

    @Test fun httpErrorBecomesReadableError() = runTest {
        respond("/v1/chat/completions", 401, """{"error":{"message":"Invalid API key"}}""")
        val chunk = provider().generate(request).toList().single()
        assertIs<LlmChunk.Error>(chunk)
        assertEquals("chave de API recusada (401): Invalid API key", chunk.message)
    }

    @Test fun geminiStyleErrorArrayIsUnderstood() = runTest {
        respond("/v1/chat/completions", 400, """[{"error":{"code":400,"message":"API key not valid"}}]""")
        val chunk = provider().generate(request).toList().single() as LlmChunk.Error
        assertTrue(chunk.message.endsWith("API key not valid"))
    }

    @Test fun noKeyMeansNoAuthorizationHeader() = runTest {
        respond("/v1/chat/completions", 200, "data: [DONE]\n\n", "text/event-stream")
        provider(key = null).generate(request).toList()
        assertEquals(null, lastAuth)
    }

    @Test fun listsModelsWithoutGeminiPrefix() = runTest {
        respond("/v1/models", 200, """{"data":[{"id":"models/gemini-x"},{"id":"llama3.2"}]}""")
        assertEquals(listOf("gemini-x", "llama3.2"), provider().listModels())
    }

    @Test fun unreachableServerIsAnErrorNotACrash() = runTest {
        server.start()
        val port = server.address.port
        server.stop(0)
        val p = OpenAiCompatibleProvider(
            CloudConfig("x", "x", "http://127.0.0.1:$port/v1", null, "m", ProviderLocation.OWN_SERVER, connectTimeoutMillis = 2_000),
        )
        assertIs<LlmChunk.Error>(p.generate(request).toList().single())
    }
}
