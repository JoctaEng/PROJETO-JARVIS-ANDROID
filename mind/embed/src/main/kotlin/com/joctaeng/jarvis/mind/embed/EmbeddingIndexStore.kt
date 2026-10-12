package com.joctaeng.jarvis.mind.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** Vetor de uma memória + a impressão digital do texto que o gerou (se o texto não mudou, não recalcula). */
class IndexEntry(val textHash: String, val vector: FloatArray)

/**
 * Índice de significado das memórias, num arquivo JSON privado (`filesDir/memory/embeddings.json`):
 * `{ "model": "...", "dims": 256, "prefixVersion": 1, "items": { "<idDaMemória>": { "textHash": "...", "v": [...] } } }`.
 * Se o modelo, o número de dimensões ou a versão dos prefixos mudarem, o índice inteiro é descartado (vetores de modelos
 * diferentes não se comparam). Arquivo ilegível também vira índice vazio, nunca erro.
 */
class EmbeddingIndexStore(
    private val file: File,
    val modelId: String,
    val dims: Int,
    val prefixVersion: Int = EmbedPrompts.VERSION,
) {
    private val json = Json
    private val items = LinkedHashMap<String, IndexEntry>()

    /** Verdadeiro se, ao abrir, havia um índice de outro modelo/dimensão/prefixo que foi descartado. */
    var discardedOnLoad: Boolean = false
        private set

    init {
        load()
    }

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun ids(): Set<String> = items.keys.toSet()

    @Synchronized
    fun hashOf(id: String): String? = items[id]?.textHash

    /** Cópia dos vetores (id → vetor), para a busca. */
    @Synchronized
    fun vectors(): Map<String, FloatArray> = items.mapValues { it.value.vector }

    @Synchronized
    fun put(id: String, textHash: String, vector: FloatArray) {
        putAll(mapOf(id to IndexEntry(textHash, vector)))
    }

    /** Grava vários de uma vez (um único salvamento em disco). */
    @Synchronized
    fun putAll(entries: Map<String, IndexEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { (id, e) ->
            require(e.vector.size == dims) { "vetor de ${e.vector.size} posições num índice de $dims" }
            items[id] = e
        }
        save()
    }

    /** Apagar a memória apaga o vetor. */
    @Synchronized
    fun remove(id: String): Boolean {
        val had = items.remove(id) != null
        if (had) save()
        return had
    }

    @Synchronized
    fun removeAllExcept(keep: Set<String>): Int {
        val before = items.size
        items.keys.retainAll(keep)
        val removed = before - items.size
        if (removed > 0) save()
        return removed
    }

    @Synchronized
    fun clear() {
        items.clear()
        if (file.exists()) save()
    }

    private fun load() {
        if (!file.exists()) return
        val root = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        val sameModel = root.str("model") == modelId
        val sameDims = root["dims"]?.jsonPrimitive?.int == dims
        val samePrefix = root["prefixVersion"]?.jsonPrimitive?.int == prefixVersion
        if (!sameModel || !sameDims || !samePrefix) {
            discardedOnLoad = true
            runCatching { file.delete() }
            return
        }
        val stored = root["items"]?.jsonObject ?: return
        stored.forEach { (id, element) ->
            val o = element.jsonObject
            val v = o["v"]?.jsonArray?.map { it.jsonPrimitive.content.toFloat() }?.toFloatArray() ?: return@forEach
            if (v.size == dims) items[id] = IndexEntry(o.str("textHash"), v)
        }
    }

    private fun save() {
        val root = buildJsonObject {
            put("model", modelId)
            put("dims", dims)
            put("prefixVersion", prefixVersion)
            put(
                "items",
                buildJsonObject {
                    items.forEach { (id, e) ->
                        put(
                            id,
                            buildJsonObject {
                                put("textHash", e.textHash)
                                put("v", buildJsonArray { e.vector.forEach { add(JsonPrimitive(it)) } })
                            },
                        )
                    }
                },
            )
        }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(JsonObject.serializer(), root))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

    companion object {
        /** Impressão digital (SHA-256) do texto da memória. */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
