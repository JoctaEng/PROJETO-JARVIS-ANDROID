package com.joctaeng.jarvis.mind.models

/** Marcação de cada modelo para ESTE aparelho (RAM total e espaço livre reais). */
enum class FitLevel(val label: String) {
    RECOMMENDED("Recomendado"),
    WORKS("Funciona"),
    TOO_HEAVY("Pesado demais"),
}

data class Fit(val level: FitLevel, val reason: String)

/**
 * Regras (simples e explicáveis ao usuário):
 * - versão do LiteRT-LM do app menor que a exigida → Pesado demais (não abre aqui);
 * - RAM total abaixo da mínima, ou falta espaço para o arquivo + folga → Pesado demais;
 * - RAM total ≥ recomendada → Recomendado; senão → Funciona (pode ficar lento ou fechar outros apps).
 */
object DeviceFit {
    /** Folga de espaço além do arquivo (o download grava em `.download` e o sistema precisa de respiro). */
    const val STORAGE_MARGIN_BYTES = 512L shl 20

    fun rate(model: CatalogModel, totalRamBytes: Long, freeStorageBytes: Long, alreadyDownloaded: Boolean, runtimeLiteRtLm: String): Fit {
        val ramMb = totalRamBytes shr 20
        val need = model.minLiteRtLm
        if (model.engine == ModelEngine.LITERTLM && need != null && compareVersions(runtimeLiteRtLm, need) < 0) {
            return Fit(FitLevel.TOO_HEAVY, "precisa do LiteRT-LM $need; o app tem $runtimeLiteRtLm")
        }
        if (ramMb < model.minRamMb) {
            return Fit(FitLevel.TOO_HEAVY, "pede ${gb(model.minRamMb)} de RAM; o aparelho tem ${gb(ramMb.toInt())}")
        }
        if (!alreadyDownloaded && freeStorageBytes < model.sizeBytes + STORAGE_MARGIN_BYTES) {
            return Fit(FitLevel.TOO_HEAVY, "falta espaço: precisa de ${bytes(model.sizeBytes + STORAGE_MARGIN_BYTES)} livres; há ${bytes(freeStorageBytes)}")
        }
        return if (ramMb >= model.recommendedRamMb) {
            Fit(FitLevel.RECOMMENDED, "RAM de ${gb(ramMb.toInt())} ≥ ${gb(model.recommendedRamMb)} recomendados")
        } else {
            Fit(FitLevel.WORKS, "RAM entre o mínimo (${gb(model.minRamMb)}) e o recomendado (${gb(model.recommendedRamMb)}): pode ficar lento")
        }
    }

    /** Compara versões "0.17.1" × "0.18.0" (números; o que faltar conta como 0). */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun gb(mb: Int): String = String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0).replace('.', ',')

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024)).replace('.', ',')
        else -> "${b shr 20} MB"
    }
}
