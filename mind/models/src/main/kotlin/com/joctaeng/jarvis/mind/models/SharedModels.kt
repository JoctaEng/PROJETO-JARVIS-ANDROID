package com.joctaeng.jarvis.mind.models

/**
 * Contrato PÚBLICO (somente leitura) dos modelos já baixados no Euno, para o Joca Finanças não baixar de novo.
 * Documentado em docs/CONTRATO-MODELOS-JOCA.md — mudar aqui exige mudar lá e no Joca.
 */
object SharedModels {
    const val AUTHORITY = "com.joctaeng.jarvis.modelos"
    const val PATH_MODELS = "modelos"
    const val PATH_FILE = "arquivo"
    const val CONTRACT_VERSION = 1

    /** Apps que podem ler (pacote verificado pelo UID de quem chama, não pelo que ele diz ser). */
    val ALLOWED_PACKAGES = setOf("com.aistudio.jocafinancas.wxyzkp")

    const val COL_ID = "id"
    const val COL_NAME = "nome"
    const val COL_FILE = "arquivo"
    const val COL_ENGINE = "motor"
    const val COL_SIZE = "tamanho_bytes"
    const val COL_SHA = "sha256"
    const val COL_CAPS = "capacidades"
    const val COL_ACTIVE = "ativo"
    const val COL_LICENSE = "licenca"
    const val COL_CONTRACT = "versao_contrato"

    val COLUMNS = arrayOf(COL_ID, COL_NAME, COL_FILE, COL_ENGINE, COL_SIZE, COL_SHA, COL_CAPS, COL_ACTIVE, COL_LICENSE, COL_CONTRACT)

    /** Um arquivo de modelo presente no Euno (só os do catálogo e completos entram). */
    data class Installed(val model: CatalogModel, val sizeBytes: Long, val sha256: String?, val active: Boolean)

    fun row(i: Installed): Array<Any?> = arrayOf(
        i.model.id,
        i.model.name,
        i.model.file,
        if (i.model.engine == ModelEngine.GGUF) "gguf" else "litertlm",
        i.sizeBytes,
        i.sha256,
        i.model.capabilities.joinToString(",") { it.key },
        if (i.active) 1 else 0,
        i.model.license,
        CONTRACT_VERSION,
    )

    /** Só arquivos completos do catálogo (nunca `.download`, nem nome fora do catálogo). */
    fun installed(catalog: ModelCatalog, files: List<Pair<String, Long>>, activeFile: String?, shaOf: (String) -> String?): List<Installed> =
        files.mapNotNull { (name, size) ->
            val m = catalog.byFile(name) ?: return@mapNotNull null
            if (size <= 0) return@mapNotNull null
            if (!m.sizeApproximate && size != m.sizeBytes) return@mapNotNull null
            Installed(m, size, shaOf(name) ?: m.sha256, name == activeFile)
        }

    fun allowed(callerPackages: Collection<String>): Boolean = callerPackages.any { it in ALLOWED_PACKAGES }
}
