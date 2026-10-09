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
    /** Assunto da memória (família, trabalho...). Memórias antigas ficam em "geral". */
    val category: String = MemoryCategory.GERAL,
)

/** Categorias de "Minha Memória". O cérebro escolhe uma delas ao guardar. */
object MemoryCategory {
    const val GERAL = "geral"
    val ALL = listOf("família", "pessoas", "trabalho", "preferências", "saúde", "casa", "geral")

    /** Aceita variações ("familia", "Trabalho ") e cai em "geral" se não conhecer. */
    fun normalize(raw: String?): String {
        val n = Normalizer.normalize(raw.orEmpty().lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return ALL.firstOrNull { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "") == n } ?: GERAL
    }
}

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
    fun add(text: String, source: String, reason: String, category: String = MemoryCategory.GERAL): MemoryItem {
        val item = MemoryItem(UUID.randomUUID().toString(), text.trim(), source, reason, clock(), MemoryCategory.normalize(category))
        items += item
        save()
        return item
    }

    /** Troca o texto e/ou a categoria de uma memória (editar em "Minha Memória"). */
    @Synchronized
    fun update(id: String, text: String, category: String): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0 || text.isBlank()) return false
        items[i] = items[i].copy(text = text.trim(), category = MemoryCategory.normalize(category))
        save()
        return true
    }

    /** Busca simples: memórias que compartilham palavras com a consulta, as mais parecidas primeiro. */
    @Synchronized
    fun search(query: String, limit: Int = 8): List<MemoryItem> {
        val wanted = words(query)
        if (wanted.isEmpty()) return emptyList()
        return items.map { it to words(it.text + " " + it.category).intersect(wanted).size }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryItem, Int>> { it.second }.thenByDescending { it.first.createdAtMillis })
            .take(limit).map { it.first }
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
                category = MemoryCategory.normalize(o.str("category")),
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
                        put("category", item.category)
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
