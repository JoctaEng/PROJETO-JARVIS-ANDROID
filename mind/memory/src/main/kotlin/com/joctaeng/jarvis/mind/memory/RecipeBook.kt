package com.joctaeng.jarvis.mind.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.text.Normalizer

/** Um passo que deu certo: ferramenta + argumentos (JSON). */
data class RecipeStep(val tool: String, val argumentsJson: String)

/** Caminho que funcionou para um pedido ("pesquisa X no Gmail"): o Euno repete na próxima vez, em vez de tentar às cegas. */
data class Recipe(val request: String, val steps: List<RecipeStep>, val uses: Int, val lastUsedMillis: Long)

/**
 * Memória de caminhos (pedido 65/72). Guarda só sequências com 2+ passos bem-sucedidos que mexeram na tela;
 * acha a receita de um pedido parecido por palavras em comum. Arquivo JSON privado do app; até [max] receitas.
 */
class RecipeBook(private val file: File?, private val clock: () -> Long = System::currentTimeMillis, private val max: Int = 60) {
    private val json = Json { prettyPrint = true }
    private val items = mutableListOf<Recipe>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<Recipe> = items.toList()

    @Synchronized
    fun record(request: String, steps: List<RecipeStep>) {
        if (steps.size < 2 || steps.none { it.tool.startsWith("tela_") }) return
        val key = tokens(request)
        if (key.isEmpty()) return
        val i = items.indexOfFirst { similarity(tokens(it.request), key) >= 0.9 }
        val recipe = Recipe(request.trim().take(200), steps.take(12), (items.getOrNull(i)?.uses ?: 0) + 1, clock())
        if (i >= 0) items[i] = recipe else items += recipe
        if (items.size > max) items.sortBy { it.lastUsedMillis }.also { while (items.size > max) items.removeAt(0) }
        save()
    }

    /** Receita de um pedido parecido (≥ [threshold] de palavras em comum), ou null. */
    @Synchronized
    fun find(request: String, threshold: Double = 0.5): Recipe? {
        val key = tokens(request)
        if (key.isEmpty()) return null
        return items.map { it to similarity(tokens(it.request), key) }
            .filter { it.second >= threshold }
            .maxWithOrNull(compareBy<Pair<Recipe, Double>> { it.second }.thenBy { it.first.uses })
            ?.first
    }

    @Synchronized
    fun forget(request: String): Boolean {
        val key = tokens(request)
        val removed = items.removeAll { similarity(tokens(it.request), key) >= 0.9 }
        if (removed) save()
        return removed
    }

    /** Texto curto para o prompt: "Caminho que já deu certo para 'X': abrir_app{...} → tela_tocar{...}". */
    fun describe(recipe: Recipe): String =
        "Caminho que já deu certo para um pedido parecido (\"${recipe.request}\"): " +
            recipe.steps.joinToString(" → ") { "${it.tool}${it.argumentsJson.take(80)}" } +
            ". Siga esse caminho (adaptando os valores ao pedido atual) antes de tentar outro."

    private fun similarity(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    private val stop = setOf(
        "o", "a", "os", "as", "um", "uma", "de", "do", "da", "dos", "das", "no", "na", "nos", "nas", "em", "e", "para", "pra", "por",
        "com", "que", "me", "meu", "minha", "eu", "voce", "euno", "favor", "agora", "ai", "la", "ali", "isso", "esse", "essa",
    )

    private fun tokens(s: String): Set<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in stop }.toSet()

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            json.parseToJsonElement(f.readText()).jsonArray.forEach { e ->
                val o = e.jsonObject
                items += Recipe(
                    o.getValue("request").jsonPrimitive.content,
                    o.getValue("steps").jsonArray.map { s ->
                        val so = s.jsonObject
                        RecipeStep(so.getValue("tool").jsonPrimitive.content, so.getValue("args").jsonPrimitive.content)
                    },
                    o["uses"]?.jsonPrimitive?.int ?: 1,
                    o["last"]?.jsonPrimitive?.long ?: 0L,
                )
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = buildJsonArray {
            items.forEach { r ->
                add(
                    buildJsonObject {
                        put("request", r.request)
                        put("uses", r.uses)
                        put("last", r.lastUsedMillis)
                        put("steps", buildJsonArray { r.steps.forEach { s -> add(buildJsonObject { put("tool", s.tool); put("args", s.argumentsJson) }) } })
                    },
                )
            }
        }
        runCatching { f.writeText(json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), arr)) }
    }
}
