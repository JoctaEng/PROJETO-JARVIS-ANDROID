package com.joctaeng.jarvis.mind.intent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A IA como TRADUTORA (v0.29, pedido 81): quando o motor não entende um pedido, a IA recebe só as poucas intenções que
 * mais se parecem e devolve uma linha de JSON; o Euno executa. A IA não age, não vê as 50 ferramentas, não conversa aqui.
 */
object Translator {
    data class Translation(val intentId: String, val slots: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Instrução curta e o pedido, para qualquer cérebro (inclusive o pequeno do celular). */
    fun prompt(utterance: String, candidates: List<IntentDef>, ctx: Ctx): Pair<String, String> {
        val list = candidates.joinToString("\n") { d ->
            val slots = d.slots.joinToString("; ") { "${it.name}${if (it.required) "*" else ""}: ${it.help}" }
            "- ${d.id}: ${d.describe}" + if (slots.isNotBlank()) " [$slots]" else ""
        }
        val system = "Você traduz pedidos em português para um comando. Responda SÓ com uma linha JSON: " +
            "{\"intent\":\"<id>\",\"slots\":{\"nome\":\"valor\"}} ou {\"intent\":\"none\"} se nenhuma opção serve. " +
            "Não explique. Datas AAAA-MM-DD, horas HH:mm, duração em minutos (duration_min), valores como 152,30 (amount). " +
            "Hoje é ${Speak.weekday(ctx.today)}, ${ctx.today}, agora ${ctx.now.toLocalTime().withSecond(0).withNano(0)}. Opções:\n$list"
        return system to utterance
    }

    /** Lê a resposta da IA (mesmo com texto em volta ou cerca de código). Null se não for um comando válido. */
    fun parse(reply: String, known: Collection<IntentDef>): Translation? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { json.parseToJsonElement(reply.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val id = (o["intent"] as? JsonPrimitive)?.content?.trim() ?: return null
        if (id.equals("none", true) || id.isBlank()) return null
        if (known.none { it.id == id }) return null
        val slots = (o["slots"] as? JsonObject)?.mapNotNull { (k, v) ->
            val value = runCatching { v.jsonPrimitive.content }.getOrNull()?.trim()
            if (value.isNullOrBlank() || value == "null") null else k to value
        }?.toMap().orEmpty()
        return Translation(id, slots)
    }
}
