package com.joctaeng.jarvis.mind.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Motor que lê o arquivo. */
enum class ModelEngine(val extension: String) { LITERTLM(".litertlm"), GGUF(".gguf") }

/** Para que serve o modelo: conversar (pode virar o cérebro do celular) ou gerar vetores (memória por sentido). */
enum class ModelKind { CHAT, EMBEDDING }

/** O que o modelo sabe fazer (o que está no catálogo; visão/áudio/ferramentas são conferidos no arquivo ao salvar). */
enum class ModelCapability(val key: String, val label: String) {
    TEXT("texto", "conversa"),
    TOOLS("ferramentas", "usa ferramentas"),
    VISION("visao", "lê imagens"),
    AUDIO("audio", "ouve áudio"),
    EMBEDDINGS("embeddings", "memória por sentido"),
    STT("fala_texto", "fala → texto"),
    ;

    companion object {
        fun of(key: String): ModelCapability? = entries.firstOrNull { it.key == key }
    }
}

/** Um modelo do catálogo, com tudo o que a tela e o download precisam. */
data class CatalogModel(
    val id: String,
    val name: String,
    val engine: ModelEngine,
    val kind: ModelKind,
    val repo: String,
    val file: String,
    val sizeBytes: Long,
    val sizeApproximate: Boolean,
    val sha256: String?,
    val minRamMb: Int,
    val recommendedRamMb: Int,
    val capabilities: Set<ModelCapability>,
    val license: String,
    val gated: Boolean,
    val minLiteRtLm: String?,
    val source: String,
    val notes: String,
) {
    /** Endereço do arquivo no Hugging Face (ramo principal). */
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$file"

    /** Lista de arquivos do repositório com o SHA-256 de cada um (para conferir o download). */
    val treeApi: String get() = "https://huggingface.co/api/models/$repo/tree/main"

    val pageUrl: String get() = "https://huggingface.co/$repo"

    fun has(c: ModelCapability) = c in capabilities
}

/** Catálogo versionado de modelos offline (JSON em recursos; conferido em [verifiedAt]). */
data class ModelCatalog(
    val version: Int,
    val verifiedAt: String,
    val models: List<CatalogModel>,
) {
    fun byId(id: String): CatalogModel? = models.firstOrNull { it.id == id }

    /** Modelo do catálogo dono deste nome de arquivo (o arquivo baixado ou importado com o mesmo nome). */
    fun byFile(fileName: String): CatalogModel? = models.firstOrNull { it.file == fileName }

    val chatModels: List<CatalogModel> get() = models.filter { it.kind == ModelKind.CHAT }

    companion object {
        const val RESOURCE = "/com/joctaeng/jarvis/mind/models/catalogo-modelos.json"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Catálogo que vem dentro do app. */
        fun bundled(): ModelCatalog {
            val text = ModelCatalog::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("catálogo de modelos ausente ($RESOURCE)")
            return parse(text)
        }

        /** Lê e valida o JSON; erro claro se faltar campo, houver id repetido ou valores sem sentido. */
        fun parse(text: String): ModelCatalog {
            val root = json.parseToJsonElement(text).jsonObject
            val version = root.int("versao")
            val verifiedAt = root.str("verificadoEm")
            require(Regex("""\d{4}-\d{2}-\d{2}""").matches(verifiedAt)) { "verificadoEm deve ser AAAA-MM-DD" }
            val models = root["modelos"]?.jsonArray?.map { parseModel(it.jsonObject) } ?: error("catálogo sem \"modelos\"")
            val dup = models.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dup.isEmpty()) { "id repetido no catálogo: $dup" }
            val dupFiles = models.groupBy { it.file }.filterValues { it.size > 1 }.keys
            require(dupFiles.isEmpty()) { "arquivo repetido no catálogo: $dupFiles" }
            return ModelCatalog(version, verifiedAt, models)
        }

        private fun parseModel(o: JsonObject): CatalogModel {
            val id = o.str("id")
            val engine = when (o.str("motor")) {
                "litertlm" -> ModelEngine.LITERTLM
                "gguf" -> ModelEngine.GGUF
                else -> error("$id: motor desconhecido ${o.str("motor")}")
            }
            val kind = when (o.str("tipo")) {
                "chat" -> ModelKind.CHAT
                "embedding" -> ModelKind.EMBEDDING
                else -> error("$id: tipo desconhecido ${o.str("tipo")}")
            }
            val file = o.str("arquivo")
            require(file.endsWith(engine.extension)) { "$id: o arquivo $file não termina em ${engine.extension}" }
            require(!file.contains('/') && !file.contains("..")) { "$id: nome de arquivo inválido" }
            val repo = o.str("repo")
            require(Regex("""[\w.-]+/[\w.-]+""").matches(repo)) { "$id: repositório inválido $repo" }
            val caps = (o["capacidades"] as? JsonArray ?: error("$id: sem capacidades")).map {
                ModelCapability.of(it.jsonPrimitive.content) ?: error("$id: capacidade desconhecida ${it.jsonPrimitive.content}")
            }.toSet()
            val size = (o["tamanhoBytes"] as? JsonPrimitive)?.longOrNull ?: error("$id: sem tamanhoBytes")
            require(size > 0) { "$id: tamanho inválido" }
            val sha = (o["sha256"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            require(sha == null || Regex("[0-9a-f]{64}").matches(sha)) { "$id: sha256 inválido" }
            val minRam = o.int("ramMinimaMb")
            val recRam = o.int("ramRecomendadaMb")
            require(minRam in 512..recRam) { "$id: RAM mínima deve ser ≤ recomendada" }
            return CatalogModel(
                id = id,
                name = o.str("nome"),
                engine = engine,
                kind = kind,
                repo = repo,
                file = file,
                sizeBytes = size,
                sizeApproximate = (o["tamanhoAproximado"] as? JsonPrimitive)?.booleanOrNull ?: true,
                sha256 = sha,
                minRamMb = minRam,
                recommendedRamMb = recRam,
                capabilities = caps,
                license = o.str("licenca"),
                gated = (o["gated"] as? JsonPrimitive)?.booleanOrNull ?: false,
                minLiteRtLm = (o["versaoMinimaLiteRtLm"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                source = o.str("fonte"),
                notes = (o["notas"] as? JsonPrimitive)?.content.orEmpty(),
            )
        }

        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() } ?: error("campo \"$key\" ausente")

        private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: error("campo \"$key\" ausente")
    }
}
