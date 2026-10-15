package com.joctaeng.jarvis.system.resources

import java.io.File

/**
 * Relatórios só da versão atual (pedido 86, 15/10): ao abrir uma versão nova, o que a versão anterior gravou (conversa,
 * medições, ações) vai para uma pasta de arquivo, e os relatórios passam a ler só a pasta atual. Nada é apagado.
 */
object VersionArchive {
    /** Itens do diretório de dados que pertencem a uma versão e são arquivados quando a versão muda. */
    val CURRENT_ITEMS = listOf("audit.jsonl", "diagnostics", "conversas")

    /**
     * Se [current] é diferente de [previous], move [items] de [root] para `root/arquivo/<previous>/` e devolve os nomes movidos.
     * Primeira abertura ([previous] nulo ou vazio): nada a arquivar.
     */
    fun archiveIfNewVersion(root: File, previous: String?, current: String, items: List<String> = CURRENT_ITEMS): List<String> {
        if (previous.isNullOrBlank() || previous == current) return emptyList()
        val target = File(root, "arquivo/${previous.replace(Regex("[^A-Za-z0-9._-]"), "_")}").apply { mkdirs() }
        val moved = mutableListOf<String>()
        for (name in items) {
            val src = File(root, name)
            if (!src.exists()) continue
            val dst = File(target, name)
            if (dst.exists()) dst.deleteRecursively()
            if (src.renameTo(dst)) moved += name
        }
        return moved
    }
}
