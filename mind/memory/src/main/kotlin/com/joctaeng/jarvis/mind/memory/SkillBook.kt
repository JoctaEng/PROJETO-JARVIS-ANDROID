package com.joctaeng.jarvis.mind.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.text.Normalizer

/**
 * Uma habilidade (skill): um pedido curto ("modo aula", "preparar aula") que vira uma sequência combinada de passos.
 * [steps] é a receita em português que o cérebro segue com as ferramentas; [triggers] são as frases que a ativam.
 */
data class Skill(
    val id: String,
    val name: String,
    val category: String,
    val triggers: List<String>,
    val steps: String,
    val builtIn: Boolean,
    val enabled: Boolean = true,
)

/**
 * Biblioteca de skills (v0.27, pedido 65c): as prontas do [SkillCatalog] + as que o usuário ensina
 * ("quando eu disser X, faça Y"). Guarda num JSON privado do app só o que é do usuário: as ensinadas e quais estão desligadas.
 */
class SkillBook(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { prettyPrint = true }
    private val taught = mutableListOf<Skill>()
    private val disabled = mutableSetOf<String>()

    init {
        load()
    }

    @Synchronized
    fun all(): List<Skill> = SkillCatalog.builtIn.map { it.copy(enabled = it.id !in disabled) } + taught.map { it.copy(enabled = it.id !in disabled) }

    /** A skill cujo gatilho aparece no pedido (palavras inteiras; vence o gatilho mais longo), ou null. */
    @Synchronized
    fun match(utterance: String): Skill? {
        val said = " ${normalize(utterance)} "
        if (said.isBlank()) return null
        return all().filter { it.enabled }
            .flatMap { skill -> skill.triggers.map { skill to normalize(it) } }
            .filter { (_, trig) -> trig.isNotBlank() && said.contains(" $trig ") }
            // as ensinadas pelo usuário vencem as prontas no empate
            .maxWithOrNull(compareBy<Pair<Skill, String>> { it.second.split(' ').size }.thenBy { if (it.first.builtIn) 0 else 1 })
            ?.first
    }

    /** Ensina (ou reescreve) uma skill do usuário. Devolve a skill salva. */
    @Synchronized
    fun teach(trigger: String, steps: String, name: String? = null): Skill {
        val trig = trigger.trim().trim('"', '“', '”', '\'').trimEnd('.', ',', '!', '?')
        require(normalize(trig).isNotBlank()) { "gatilho vazio" }
        require(steps.isNotBlank()) { "passos vazios" }
        val existing = taught.indexOfFirst { s -> s.triggers.any { normalize(it) == normalize(trig) } }
        val skill = Skill(
            id = taught.getOrNull(existing)?.id ?: "usuario-${clock()}",
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: trig.replaceFirstChar { it.uppercase() },
            category = "Minhas",
            triggers = listOf(trig),
            steps = steps.trim(),
            builtIn = false,
        )
        if (existing >= 0) taught[existing] = skill else taught += skill
        save()
        return skill
    }

    /** Apaga uma skill ensinada (pelo id, nome ou gatilho). As prontas não se apagam: só desligam. */
    @Synchronized
    fun remove(idOrName: String): Skill? {
        val key = normalize(idOrName)
        val i = taught.indexOfFirst { it.id == idOrName || normalize(it.name) == key || it.triggers.any { t -> normalize(t) == key } }
        if (i < 0) return null
        val removed = taught.removeAt(i)
        disabled -= removed.id
        save()
        return removed
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        if (enabled) disabled -= id else disabled += id
        save()
    }

    /** Texto para o prompt quando a skill é ativada pelo pedido. */
    fun describe(skill: Skill): String =
        "SKILL ATIVA \"${skill.name}\" (o pedido combina com ela). Siga estes passos com as ferramentas, na ordem, " +
            "adaptando ao pedido; pule o que não se aplica e pergunte só o que faltar: ${skill.steps}"

    /** Lista curta para o cérebro saber o que existe ("skills: Modo aula, Preparar aula…"). */
    fun catalogLine(max: Int = 40): String =
        all().filter { it.enabled }.take(max).joinToString("; ") { "${it.name} (\"${it.triggers.first()}\")" }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val root = json.parseToJsonElement(f.readText()).jsonObject
            root["disabled"]?.jsonArray?.forEach { disabled += it.jsonPrimitive.content }
            root["taught"]?.jsonArray?.forEach { el ->
                val o = el.jsonObject
                taught += Skill(
                    id = o["id"]!!.jsonPrimitive.content,
                    name = o["name"]!!.jsonPrimitive.content,
                    category = "Minhas",
                    triggers = (o["triggers"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                    steps = o["steps"]!!.jsonPrimitive.content,
                    builtIn = false,
                    enabled = o["enabled"]?.jsonPrimitive?.boolean ?: true,
                )
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val root = buildJsonObject {
            put("disabled", buildJsonArray { disabled.sorted().forEach { add(JsonPrimitive(it)) } })
            put(
                "taught",
                buildJsonArray {
                    taught.forEach { s ->
                        add(
                            buildJsonObject {
                                put("id", s.id)
                                put("name", s.name)
                                put("triggers", buildJsonArray { s.triggers.forEach { add(JsonPrimitive(it)) } })
                                put("steps", s.steps)
                            },
                        )
                    }
                },
            )
        }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root))
            tmp.renameTo(f)
        }
    }

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}

/** "Quando eu disser X, faça Y": o jeito falado de ensinar uma skill. Puro, para testar. */
object SkillTeaching {
    data class Lesson(val trigger: String, val steps: String)

    private val start = Regex(
        """^(?:(?:euno|joca)[,!]?\s+)?(?:aprenda(?: que)?[:,]?\s+|aprende(?: que)?[:,]?\s+)?(?:quando|toda vez que|sempre que)\s+eu\s+(?:disser|falar|pedir)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Separadores entre o gatilho e o que fazer: vírgula/dois-pontos, ou "faça", "você", "quero que você", "é para". */
    private val separators = listOf(
        Regex("""^["“](.+?)["”]\s*[,:]?\s*(.+)$"""),
        Regex("""^(.+?)\s*[,:]\s*(.+)$"""),
        Regex("""^(.+?)\s+(?:quero que (?:você|voce)|(?:você|voce) (?:deve|vai)|(?:é|e) para|faça|faca|faz)\s+(.+)$""", RegexOption.IGNORE_CASE),
    )

    fun parse(utterance: String): Lesson? {
        val m = start.find(utterance.trim().trimEnd('.', '!')) ?: return null
        val rest = m.groupValues[1].trim()
        for (sep in separators) {
            val s = sep.find(rest) ?: continue
            val trigger = s.groupValues[1].trim().trim('"', '“', '”')
            val steps = s.groupValues[2].trim()
                .replace(Regex("""^(?:(?:você|voce)\s+)?(?:faça|faca|faz|deve|vai)\s+""", RegexOption.IGNORE_CASE), "")
            if (SkillBook.normalize(trigger).isNotBlank() && steps.length >= 3) return Lesson(trigger, steps)
        }
        return null
    }
}
