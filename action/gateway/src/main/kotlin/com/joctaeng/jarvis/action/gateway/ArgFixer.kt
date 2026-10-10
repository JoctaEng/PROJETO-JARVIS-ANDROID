package com.joctaeng.jarvis.action.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/**
 * Conserta os argumentos que o cérebro manda com outro nome ("app" em vez de "nome", "title" em vez de "titulo"...).
 * Na v0.15.0 várias ferramentas falharam com "informe o nome do app"/"fato vazio" porque o campo chegava com outro nome.
 * Nunca troca um valor que já veio certo.
 */
object ArgFixer {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val aliases: Map<String, List<String>> = mapOf(
        "nome" to listOf("app", "aplicativo", "app_name", "nome_app", "nome_do_app", "name", "application", "package", "botao", "button", "alvo", "target", "label", "contato_nome"),
        "texto" to listOf("text", "mensagem", "message", "conteudo", "content", "valor", "value"),
        "mensagem" to listOf("message", "texto", "text", "msg", "conteudo", "content"),
        "contato" to listOf("contact", "nome", "name", "para", "destinatario", "to", "pessoa"),
        "fato" to listOf("texto", "text", "memoria", "memory", "fact", "conteudo", "content", "informacao", "info", "nota", "note"),
        "titulo" to listOf("title", "nome", "name", "evento", "assunto", "summary", "descricao", "description"),
        "consulta" to listOf("query", "busca", "pesquisa", "termo", "q", "texto", "text", "search", "pergunta"),
        "trecho" to listOf("texto", "text", "fato", "query", "consulta", "memoria"),
        "filtro" to listOf("filter", "query", "nome", "busca"),
        "periodo" to listOf("period", "quando", "dia", "intervalo"),
        "ligar" to listOf("on", "ativar", "estado", "state", "enabled", "ligada"),
        "direcao" to listOf("direction", "sentido"),
        "acao" to listOf("action", "comando"),
        "url" to listOf("link", "endereco", "site", "endereco_web"),
        "local" to listOf("lugar", "endereco", "destino", "place", "address", "location"),
    )

    /** Devolve os argumentos com os nomes do esquema da ferramenta (JSON). */
    fun normalize(argumentsJson: String, schemaJson: String): String {
        val args = parseObject(argumentsJson) ?: return argumentsJson
        val schema = parseObject(schemaJson) ?: return argumentsJson
        val props = (schema["properties"] as? JsonObject)?.keys.orEmpty()
        if (props.isEmpty()) return argumentsJson
        val required = runCatching { schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
        val out = LinkedHashMap<String, JsonElement>(args)
        val byKey = args.keys.associateBy { key(it) }
        val used = HashSet<String>()
        fun blank(e: JsonElement?) = e == null || (e is JsonPrimitive && e.isString && e.content.isBlank())
        for (p in props) {
            if (!blank(out[p])) { used += p; continue }
            // mesmo nome com acento/maiúscula/sublinhado diferente, ou um apelido conhecido
            val candidate = (listOf(p) + aliases[p].orEmpty()).mapNotNull { byKey[key(it)] }.firstOrNull { it != p && it !in used && !blank(args[it]) }
            if (candidate != null) { out[p] = args.getValue(candidate); used += candidate; used += p }
        }
        // Um campo obrigatório ainda vazio e um único valor de texto "sobrando": é ele.
        val missing = required.filter { blank(out[it]) }
        val leftovers = args.keys.filter { it !in props && it !in used && (args[it] as? JsonPrimitive)?.isString == true && !blank(args[it]) }
        if (missing.size == 1 && leftovers.size == 1) out[missing.single()] = args.getValue(leftovers.single())
        return JsonObject(out).toString()
    }

    /** Campos obrigatórios que continuam vazios (para o erro dizer o formato certo). */
    fun missingRequired(argumentsJson: String, schemaJson: String): List<String> {
        val args = parseObject(argumentsJson) ?: JsonObject(emptyMap())
        val schema = parseObject(schemaJson) ?: return emptyList()
        val required = runCatching { schema["required"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
        return required.filter { r -> args[r].let { it == null || (it is JsonPrimitive && it.isString && it.content.isBlank()) } }
    }

    /** "campos=[nome(6)]": nomes dos campos e tamanho dos valores, sem o conteúdo (vai para o relatório). */
    fun shape(argumentsJson: String): String {
        val args = parseObject(argumentsJson) ?: return "campos=? (não é JSON: ${argumentsJson.length} car.)"
        return "campos=[" + args.entries.joinToString(", ") { (k, v) ->
            k + when (v) {
                is JsonPrimitive -> "(${v.contentOrNull?.length ?: 0})"
                is JsonObject -> "{…}"
                is JsonArray -> "[…]"
                else -> ""
            }
        } + "]"
    }

    /** Exemplo do formato certo para a ferramenta, com os campos obrigatórios. */
    fun example(toolName: String, fields: List<String>): String =
        "<tool_call>" + buildJsonObject {
            put("name", JsonPrimitive(toolName))
            put("arguments", JsonObject(fields.associateWith { JsonPrimitive("…") }))
        } + "</tool_call>"

    private fun parseObject(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun key(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")
}
