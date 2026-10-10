package com.joctaeng.jarvis.system.resources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** E-mail do Gmail (API REST, formato "full") resumido para o cérebro ler. Sem Android: testável na JVM. */
data class GmailMessage(val id: String, val from: String, val subject: String, val date: String, val snippet: String, val body: String) {
    fun shortLine(): String = "- [id $id] $date · $from · $subject — ${snippet.take(140)}"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String, maxBody: Int = 5_000): GmailMessage {
            val o = json.parseToJsonElement(raw).jsonObject
            val payload = o["payload"] as? JsonObject
            val headers = payload?.get("headers")?.jsonArray.orEmpty().mapNotNull { h ->
                val ho = h.jsonObject
                val name = ho["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                name.lowercase() to (ho["value"]?.jsonPrimitive?.contentOrNull ?: "")
            }.toMap()
            val plain = payload?.let { findPart(it, "text/plain") }
            val html = if (plain == null) payload?.let { findPart(it, "text/html") } else null
            val body = plain ?: html?.let { HtmlText.extract(it, maxBody).text } ?: ""
            return GmailMessage(
                id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                from = headers["from"].orEmpty(),
                subject = headers["subject"].orEmpty().ifBlank { "(sem assunto)" },
                date = headers["date"].orEmpty(),
                snippet = o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty().let { HtmlText.extract(it).text },
                body = body.replace(Regex("\\r"), "").trim().take(maxBody),
            )
        }

        private fun findPart(part: JsonObject, mime: String): String? {
            val type = part["mimeType"]?.jsonPrimitive?.contentOrNull
            val data = (part["body"] as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull
            if (type == mime && !data.isNullOrBlank()) return decode(data)
            return part["parts"]?.jsonArray?.firstNotNullOfOrNull { findPart(it.jsonObject, mime) }
        }

        private fun decode(data: String): String = runCatching {
            String(Base64.getUrlDecoder().decode(data.replace('+', '-').replace('/', '_').trimEnd('=')), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
