package com.joctaeng.jarvis.mind.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Uma memória visível em "Minha Memória" (item 8 da especificação): o que é,
 * de onde veio, quando e por quê.
 */
data class MemoryItem(
    val id: String,
    val text: String,
    val source: String,
    val reason: String,
    val createdAtMillis: Long,
)

/**
 * Memória persistente simples do MVP 1: um arquivo JSON privado do app.
 * Ver docs/ADR/0006-memoria-do-mvp1.md (criptografia e busca vetorial vêm depois).
 */
class MemoryStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { prettyPrint = true }
    private val items = mutableListOf<MemoryItem>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<MemoryItem> = items.toList()

    @Synchronized
    fun add(text: String, source: String, reason: String): MemoryItem {
        val item = MemoryItem(UUID.randomUUID().toString(), text.trim(), source, reason, clock())
        items += item
        save()
        return item
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    @Synchronized
    fun clear() {
        items.clear()
        save()
    }

    /**
     * "Esqueça que X": remove a memória que mais compartilha palavras com X.
     * Sem consulta, remove a mais recente. Devolve o que foi esquecido.
     */
    @Synchronized
    fun forget(query: String?): MemoryItem? {
        val target = if (query.isNullOrBlank()) {
            items.lastOrNull()
        } else {
            val wanted = words(query)
            items.map { it to words(it.text).intersect(wanted).size }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<MemoryItem, Int>> { it.second }.thenBy { it.first.createdAtMillis })
                ?.first
        } ?: return null
        items.remove(target)
        save()
        return target
    }

    private fun words(text: String): Set<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()

    private fun load() {
        if (!file.exists()) return
        val array = runCatching { json.parseToJsonElement(file.readText()).jsonArray }.getOrNull() ?: return
        array.forEach { element ->
            val o = element.jsonObject
            items += MemoryItem(
                id = o.str("id"),
                text = o.str("text"),
                source = o.str("source"),
                reason = o.str("reason"),
                createdAtMillis = o["createdAt"]?.jsonPrimitive?.long ?: 0L,
            )
        }
    }

    private fun save() {
        val array: JsonArray = buildJsonArray {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        put("id", item.id)
                        put("text", item.text)
                        put("source", item.source)
                        put("reason", item.reason)
                        put("createdAt", item.createdAtMillis)
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

    private companion object {
        val stopWords = setOf("que", "para", "com", "uma", "uns", "das", "dos", "nas", "nos", "por", "mais", "isso", "isto", "meu", "minha", "sobre")
    }
}
