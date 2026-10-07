package com.joctaeng.jarvis.mind.cloud

import com.joctaeng.jarvis.core.contracts.GenerationStats
import com.joctaeng.jarvis.core.contracts.LlmChunk
import com.joctaeng.jarvis.core.contracts.LlmProvider
import com.joctaeng.jarvis.core.contracts.LlmRequest
import com.joctaeng.jarvis.core.model.Capability
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ProviderLocation
import com.joctaeng.jarvis.core.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Configuração de um provedor online. A chave nunca fica no código (seção 10.3). */
data class CloudConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val location: ProviderLocation,
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 60_000,
)

/**
 * Cliente do formato "chat completions" compatível com OpenAI, com streaming SSE.
 * Funciona com OpenAI, Gemini (endpoint de compatibilidade), OpenRouter e
 * servidores próprios como Ollama e vLLM — por isso é o primeiro provedor online.
 */
class OpenAiCompatibleProvider(private val config: CloudConfig) : LlmProvider {

    override val id: String = config.id
    override val displayName: String = config.displayName
    override val location: ProviderLocation = config.location
    override val capabilities = setOf(Capability.TEXT, Capability.STREAMING)

    private val json = Json { ignoreUnknownKeys = true }
    private val base = config.baseUrl.trim().trimEnd('/')

    override suspend fun isAvailable(context: DeviceContext): Boolean =
        base.isNotEmpty() && config.model.isNotBlank() && context.online

    override fun generate(request: LlmRequest): Flow<LlmChunk> = flow {
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            request.maxOutputTokens?.let { put("max_tokens", it) }
            put(
                "messages",
                buildJsonArray {
                    if (request.systemPrompt.isNotBlank()) add(message("system", request.systemPrompt))
                    request.messages.forEach { m ->
                        val role = when (m.role) {
                            Role.USER -> "user"
                            Role.ASSISTANT -> "assistant"
                            Role.SYSTEM -> "system"
                            Role.TOOL -> null
                        }
                        if (role != null) add(message(role, m.text))
                    }
                },
            )
        }

        val start = System.nanoTime()
        var firstAt = -1L
        var chunks = 0
        var chars = 0
        val connection = open("$base/chat/completions", "POST")
        try {
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) {
                emit(LlmChunk.Error(describeError(status, connection.errorStream?.bufferedReader()?.readText())))
                return@flow
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    val data = SseParser.data(line) ?: continue
                    if (data == "[DONE]") break
                    val text = SseParser.deltaText(json, data) ?: continue
                    if (text.isEmpty()) continue
                    if (firstAt < 0) firstAt = System.nanoTime()
                    chunks++
                    chars += text.length
                    emit(LlmChunk.Text(text))
                }
            }
        } finally {
            connection.disconnect()
        }
        val end = System.nanoTime()
        emit(
            LlmChunk.Done(
                GenerationStats(
                    timeToFirstChunkMillis = if (firstAt < 0) -1 else (firstAt - start) / 1_000_000,
                    totalMillis = (end - start) / 1_000_000,
                    chunkCount = chunks,
                    charCount = chars,
                ),
            ),
        )
    }.catch { e ->
        emit(LlmChunk.Error(networkMessage(e), e))
    }.flowOn(Dispatchers.IO)

    /** Lista os modelos do provedor (GET /models), para o usuário escolher sem digitar. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val connection = open("$base/models", "GET")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException(describeError(status, connection.errorStream?.bufferedReader()?.readText()))
            }
            val root = json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            root["data"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .map { it.removePrefix("models/") }
                .sorted()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.readTimeoutMillis
            setRequestProperty("Content-Type", "application/json")
            // Chave colada do navegador costuma vir com espaço ou quebra de linha, e o provedor a recusa.
            config.apiKey?.trim()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            doOutput = method == "POST"
        }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", JsonPrimitive(content))
    }

    private fun describeError(status: Int, body: String?): String {
        val detail = body?.let {
            runCatching {
                val root = json.parseToJsonElement(it)
                val error = (if (root is kotlinx.serialization.json.JsonArray) root.firstOrNull() else root)
                    ?.jsonObject?.get("error")
                error?.let { e -> (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString() }
            }.getOrNull() ?: it.take(200)
        }
        val hint = when {
            // O Gemini responde 400 (e não 401) para chave inválida.
            status in 400..403 && detail?.contains("API key", ignoreCase = true) == true -> "chave de API recusada"
            else -> when (status) {
            401, 403 -> "chave de API recusada"
            404 -> "endereço ou modelo não encontrado"
            429 -> "limite de uso atingido"
            in 500..599 -> "erro no servidor do provedor"
            else -> "erro HTTP"
            }
        }
        return "$hint ($status)" + (detail?.let { ": $it" } ?: "")
    }

    private fun networkMessage(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "sem conexão ou endereço inválido"
        is java.net.SocketTimeoutException -> "o provedor demorou demais para responder"
        is java.net.ConnectException -> "não consegui conectar ao servidor"
        else -> e.message ?: e::class.simpleName ?: "erro de rede"
    }
}

/** Leitura das linhas do streaming (Server-Sent Events) no formato OpenAI. */
internal object SseParser {
    fun data(line: String): String? = if (line.startsWith("data:")) line.substring(5).trim() else null

    fun deltaText(json: Json, data: String): String? = runCatching {
        val choice = json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@runCatching null
        choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
