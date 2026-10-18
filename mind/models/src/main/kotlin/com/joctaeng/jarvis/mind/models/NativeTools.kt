package com.joctaeng.jarvis.mind.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Uma ferramenta como o modelo vê na chamada de função nativa. */
data class NativeToolSpec(val name: String, val description: String, val parametersJson: String) {
    /** Descrição no formato que o LiteRT-LM espera em OpenApiTool.getToolDescriptionJsonString(). */
    fun openApiJson(): String = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", runCatching { Json.parseToJsonElement(parametersJson) }.getOrElse { JsonObject(emptyMap()) })
    }.toString()
}

/** Prompt sem a lista de ferramentas em texto + as ferramentas para registrar no motor. */
data class NativeToolSplit(val prompt: String, val tools: List<NativeToolSpec>)

/**
 * Ponte entre o protocolo de texto do Euno (`<tool_call>`, ADR 0011) e a chamada de função NATIVA do modelo (Gemma 4 no
 * LiteRT-LM). O prompt continua sendo montado igual para todos os cérebros; o provedor local tira o bloco `<tools>` do
 * texto e registra as mesmas ferramentas no motor. A execução continua 100% pelo ToolGateway: a chamada nativa vira o
 * mesmo pedido de ferramenta que o texto geraria.
 */
object NativeTools {
    private const val OPEN = "<tools>"
    private const val CLOSE = "</tools>"
    const val NOTE = "(As ferramentas acima estão registradas no formato nativo de chamada de função: chame-as por ele. " +
        "Se não conseguir, use o formato <tool_call> descrito.)"

    /** Separa o bloco `<tools>…</tools>` do prompt. Sem bloco (ou JSON ilegível) → nada a registrar. */
    fun split(systemPrompt: String): NativeToolSplit {
        val a = systemPrompt.indexOf(OPEN)
        val b = systemPrompt.indexOf(CLOSE, a + 1)
        if (a < 0 || b < 0) return NativeToolSplit(systemPrompt, emptyList())
        val body = systemPrompt.substring(a + OPEN.length, b)
        val tools = body.lines().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { line ->
            runCatching {
                val o = Json.parseToJsonElement(line).jsonObject
                val name = o["name"]?.jsonPrimitive?.content ?: return@runCatching null
                NativeToolSpec(name, o["description"]?.jsonPrimitive?.content.orEmpty(), (o["parameters"] ?: JsonObject(emptyMap())).toString())
            }.getOrNull()
        }
        if (tools.isEmpty()) return NativeToolSplit(systemPrompt, emptyList())
        val prompt = systemPrompt.substring(0, a) + "Ferramentas: " + tools.joinToString(", ") { it.name } + ".\n" + NOTE + systemPrompt.substring(b + CLOSE.length)
        return NativeToolSplit(prompt, tools)
    }

    /** Argumentos que o motor devolve (mapa) em JSON. */
    fun argumentsJson(args: Map<String, Any?>): String = toJson(args).toString()

    /** A chamada nativa escrita no protocolo de texto, para o histórico da próxima rodada. */
    fun protocolText(name: String, argumentsJson: String): String {
        val args = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrElse { JsonObject(emptyMap()) }
        return "<tool_call>" + buildJsonObject { put("name", name); put("arguments", args) } + "</tool_call>"
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        is Array<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }
}
