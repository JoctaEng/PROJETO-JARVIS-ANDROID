package com.joctaeng.jarvis.action.gateway

import com.joctaeng.jarvis.core.contracts.Tool
import com.joctaeng.jarvis.core.model.ToolCall
import com.joctaeng.jarvis.core.model.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Chamada de ferramentas por texto, no formato que o Qwen3 aprendeu (<tool_call>…</tool_call>), usado igual
 * pela IA do celular e pelos cérebros online — um só caminho, testável e independente do provedor (ADR 0011).
 */
object ToolProtocol {
    const val OPEN = "<tool_call>"
    const val CLOSE = "</tool_call>"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun systemSection(tools: Collection<Tool>): String = buildString {
        appendLine("## Ferramentas")
        appendLine("Você pode agir no celular do usuário e nos apps dele com as ferramentas abaixo. Para usar uma, escreva apenas:")
        appendLine("$OPEN{\"name\": \"nome_da_ferramenta\", \"arguments\": {…}}$CLOSE")
        appendLine("Depois pare e espere o resultado, que chega numa mensagem <tool_response>. Pode usar várias em sequência.")
        appendLine("Use uma ferramenta só quando o pedido for claro e precisar dela; se o pedido estiver confuso, cortado ou ambíguo, pergunte antes de agir. Nunca diga que fez algo antes de o resultado confirmar;")
        appendLine("se vier erro, negado ou cancelado, diga isso com honestidade. Não mostre o JSON ao usuário.")
        appendLine("<tools>")
        tools.forEach { tool ->
            val schema = runCatching { json.parseToJsonElement(tool.inputSchemaJson) }.getOrElse { JsonObject(emptyMap()) }
            val line = buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", schema)
            }
            appendLine(line.toString())
        }
        append("</tools>")
    }

    /** Lê o JSON de dentro de <tool_call>; tolera cercas ``` e "arguments" como texto. */
    fun parseCall(body: String, id: String): ToolCall? {
        val cleaned = body.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull() ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val args: JsonElement = when (val a = obj["arguments"] ?: obj["parameters"]) {
            null -> JsonObject(emptyMap())
            is JsonPrimitive -> runCatching { json.parseToJsonElement(a.content) }.getOrElse { JsonObject(emptyMap()) }
            else -> a
        }
        return ToolCall(id = id, toolName = name, argumentsJson = args.toString())
    }

    fun responseMessage(call: ToolCall, result: ToolResult): String {
        val body = buildJsonObject {
            put("name", call.toolName)
            when (result) {
                is ToolResult.Success -> {
                    put("status", "sucesso")
                    put("resultado", runCatching { json.parseToJsonElement(result.outputJson) }.getOrElse { JsonPrimitive(result.outputJson) })
                }
                is ToolResult.Failure -> { put("status", "erro"); put("motivo", result.reason) }
                is ToolResult.Denied -> { put("status", "negado"); put("motivo", result.reason) }
                ToolResult.Cancelled -> { put("status", "cancelado"); put("motivo", "o usuário não confirmou") }
            }
        }
        return "<tool_response>$body</tool_response>"
    }

    fun argument(call: ToolCall, key: String): String? =
        runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

/**
 * Separa, no texto em streaming, o que é para o usuário do que é pedido de ferramenta.
 * Segura o finalzinho que pode ser o começo de "<tool_call>" até ter certeza.
 */
class ToolCallFilter(private val newId: () -> String) {
    private val buffer = StringBuilder()
    private var inside = false

    data class Output(val visible: String, val calls: List<ToolCall>, val malformed: Int = 0)

    fun feed(chunk: String): Output {
        buffer.append(chunk)
        return drain(final = false)
    }

    fun finish(): Output = drain(final = true)

    private fun drain(final: Boolean): Output {
        val visible = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var malformed = 0
        while (true) {
            if (inside) {
                val end = buffer.indexOf(ToolProtocol.CLOSE)
                if (end < 0) {
                    if (final) {
                        // Fechamento esquecido: tenta ler o JSON assim mesmo.
                        ToolProtocol.parseCall(buffer.toString(), newId())?.let { calls += it } ?: run { if (buffer.isNotBlank()) malformed++ }
                        buffer.clear()
                        inside = false
                    }
                    break
                }
                ToolProtocol.parseCall(buffer.substring(0, end), newId())?.let { calls += it } ?: malformed++
                buffer.delete(0, end + ToolProtocol.CLOSE.length)
                inside = false
                continue
            }
            val start = buffer.indexOf(ToolProtocol.OPEN)
            if (start >= 0) {
                visible.append(buffer, 0, start)
                buffer.delete(0, start + ToolProtocol.OPEN.length)
                inside = true
                continue
            }
            val keep = if (final) 0 else partialTagLength()
            visible.append(buffer, 0, buffer.length - keep)
            buffer.delete(0, buffer.length - keep)
            break
        }
        return Output(visible.toString(), calls, malformed)
    }

    /** Quantos caracteres finais podem ser o início de "<tool_call>". */
    private fun partialTagLength(): Int {
        val tag = ToolProtocol.OPEN
        for (len in minOf(tag.length - 1, buffer.length) downTo 1) {
            if (buffer.endsWith(tag.substring(0, len))) return len
        }
        return 0
    }
}
