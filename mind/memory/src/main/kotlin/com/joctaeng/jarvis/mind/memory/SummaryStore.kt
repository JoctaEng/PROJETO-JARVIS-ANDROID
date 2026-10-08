package com.joctaeng.jarvis.mind.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Resumo de uma conversa, guardado para dar contexto a conversas novas (só se o usuário quiser). */
data class ConversationSummary(
    val id: String,
    val title: String,
    val text: String,
    val createdAtMillis: Long,
    /** Marcado = entra como contexto nas conversas novas. */
    val useInNewChats: Boolean,
)

/** Resumos de conversa em um arquivo JSON privado do app; o usuário vê, edita, apaga e escolhe quais valem. */
class SummaryStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { prettyPrint = true }
    private val items = mutableListOf<ConversationSummary>()

    init {
        load()
    }

    /** Mais novos primeiro. */
    @Synchronized
    fun all(): List<ConversationSummary> = items.sortedByDescending { it.createdAtMillis }

    @Synchronized
    fun add(title: String, text: String, useInNewChats: Boolean = false): ConversationSummary {
        val item = ConversationSummary(UUID.randomUUID().toString(), title.trim().ifEmpty { "Conversa" }, text.trim(), clock(), useInNewChats)
        items += item
        save()
        return item
    }

    @Synchronized
    fun update(id: String, title: String, text: String, useInNewChats: Boolean): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return false
        items[i] = items[i].copy(title = title.trim().ifEmpty { "Conversa" }, text = text.trim(), useInNewChats = useInNewChats)
        save()
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    /** Texto dos resumos marcados (mais novos primeiro), cortado em [maxChars] sem passar do limite. */
    @Synchronized
    fun activeContext(maxChars: Int): String {
        val out = StringBuilder()
        for (s in all().filter { it.useInNewChats }) {
            val block = "- ${s.title}: ${s.text.replace('\n', ' ')}"
            if (out.isNotEmpty() && out.length + block.length + 1 > maxChars) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(if (out.isEmpty() && block.length > maxChars) block.take(maxChars) else block)
        }
        return out.toString()
    }

    private fun load() {
        if (!file.exists()) return
        val array = runCatching { json.parseToJsonElement(file.readText()).jsonArray }.getOrNull() ?: return
        array.forEach { element ->
            val o = element.jsonObject
            items += ConversationSummary(
                id = o.str("id").ifEmpty { UUID.randomUUID().toString() },
                title = o.str("title"),
                text = o.str("text"),
                createdAtMillis = o["createdAt"]?.jsonPrimitive?.long ?: 0L,
                useInNewChats = o["use"]?.jsonPrimitive?.boolean ?: false,
            )
        }
    }

    private fun save() {
        val array: JsonArray = buildJsonArray {
            items.forEach { s ->
                add(
                    buildJsonObject {
                        put("id", s.id)
                        put("title", s.title)
                        put("text", s.text)
                        put("createdAt", s.createdAtMillis)
                        put("use", s.useInNewChats)
                    },
                )
            }
        }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(JsonArray.serializer(), array))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
}
