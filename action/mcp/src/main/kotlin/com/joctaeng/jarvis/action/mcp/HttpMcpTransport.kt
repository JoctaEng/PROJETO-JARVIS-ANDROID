package com.joctaeng.jarvis.action.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transporte "Streamable HTTP" do MCP: cada mensagem é um POST; a resposta vem como JSON ou como
 * eventos SSE. Guarda o Mcp-Session-Id que o servidor devolver. Para servidores no PC ou na rede.
 */
class HttpMcpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 120_000,
) : McpTransport {
    private var onMessage: ((String) -> Unit)? = null
    @Volatile private var sessionId: String? = null

    override suspend fun open(onMessage: (String) -> Unit) {
        this.onMessage = onMessage
    }

    override suspend fun send(message: String) = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION)
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(message.toByteArray()) }
            val status = c.responseCode
            c.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (status == 202 || status == 204) return@withContext
            if (status !in 200..299) {
                throw IOException("servidor MCP respondeu HTTP $status: ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
            }
            val type = c.contentType.orEmpty()
            c.inputStream.bufferedReader().use { reader ->
                if (type.startsWith("text/event-stream")) {
                    val data = StringBuilder()
                    reader.lineSequence().forEach { line ->
                        when {
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                            line.isEmpty() && data.isNotEmpty() -> {
                                onMessage?.invoke(data.toString().trimEnd())
                                data.clear()
                            }
                        }
                    }
                    if (data.isNotEmpty()) onMessage?.invoke(data.toString().trimEnd())
                } else {
                    val body = reader.readText()
                    if (body.isNotBlank()) onMessage?.invoke(body)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    override fun close() {
        onMessage = null
    }
}
