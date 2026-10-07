package com.joctaeng.jarvis.action.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Canal por onde passam as mensagens JSON-RPC do MCP: Binder (apps do celular) ou HTTP (PC/rede). */
interface McpTransport {
    /** Começa a receber mensagens; [onMessage] pode ser chamado de qualquer thread. */
    suspend fun open(onMessage: (String) -> Unit)
    suspend fun send(message: String)
    fun close()
}

data class McpToolInfo(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchemaJson: String,
    val readOnly: Boolean,
    val destructive: Boolean,
)

data class McpCallResult(val text: String, val structuredJson: String?, val isError: Boolean)

class McpException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

/** Cliente MCP (JSON-RPC 2.0): initialize → tools/list → tools/call. */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "Euno",
    private val clientVersion: String = "1",
    private val timeoutMillis: Long = 60_000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private var opened = false

    var serverName: String? = null
        private set
    var instructions: String? = null
        private set

    suspend fun initialize() {
        if (!opened) {
            transport.open(::onMessage)
            opened = true
        }
        val result = request("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", clientName); put("version", clientVersion) })
        })
        serverName = result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull
        notify("notifications/initialized")
    }

    suspend fun listTools(): List<McpToolInfo> {
        val out = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        do {
            val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            result["tools"]?.jsonArray.orEmpty().forEach { el ->
                val t = el.jsonObject
                val annotations = t["annotations"] as? JsonObject
                out += McpToolInfo(
                    name = t["name"]!!.jsonPrimitive.content,
                    title = (t["title"] as? JsonPrimitive)?.contentOrNull,
                    description = (t["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    inputSchemaJson = (t["inputSchema"] ?: JsonObject(mapOf("type" to JsonPrimitive("object")))).toString(),
                    readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
                    destructive = (annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null)
        return out
    }

    suspend fun callTool(name: String, argumentsJson: String): McpCallResult {
        val args = runCatching { json.parseToJsonElement(argumentsJson) }.getOrElse { JsonObject(emptyMap()) }
        val result = request("tools/call", buildJsonObject { put("name", name); put("arguments", args) })
        val text = result["content"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.takeIf { c -> (c["type"] as? JsonPrimitive)?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
        return McpCallResult(
            text = text,
            structuredJson = result["structuredContent"]?.toString(),
            isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    fun close() {
        pending.values.forEach { it.completeExceptionally(McpException(-1, "conexão encerrada")) }
        pending.clear()
        transport.close()
        opened = false
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            transport.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
            }.toString())
            return withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun notify(method: String) {
        transport.send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString())
    }

    private fun onMessage(raw: String) {
        val msg = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val id = (msg["id"] as? JsonPrimitive)?.let { it.contentOrNull?.toLongOrNull() } ?: return
        val deferred = pending[id] ?: return
        val error = msg["error"] as? JsonObject
        if (error != null) {
            deferred.completeExceptionally(
                McpException(
                    code = (error["code"] as? JsonPrimitive)?.intOrNull ?: -1,
                    message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "erro do servidor MCP",
                    data = error["data"],
                ),
            )
        } else {
            deferred.complete(msg["result"] as? JsonObject ?: JsonObject(emptyMap()))
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        /** Erro que um app do celular devolve quando a parte dele que atende o MCP não está aberta. */
        const val APP_NOT_READY = -32001
    }
}
