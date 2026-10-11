package com.joctaeng.jarvis.conversation

import android.content.Context

/**
 * Quantos pedidos cada caminho resolveu (v0.29, pedido 81): o motor do Euno sozinho, a IA só traduzindo para o motor,
 * ou o caminho antigo (a IA chamando ferramentas). Aparece no relatório; mostra se o motor está de fato resolvendo.
 */
class EngineStats(context: Context) {
    enum class Path(val label: String) {
        ENGINE("motor do Euno (sem IA)"), TRANSLATOR("IA traduziu → motor executou"), LEGACY("caminho antigo (IA com ferramentas / conversa)"), FAILED("falhou"),
    }

    private val prefs = context.getSharedPreferences("euno_motor", Context.MODE_PRIVATE)

    @Synchronized
    fun note(path: Path, millis: Long) {
        prefs.edit()
            .putInt("n_${path.name}", prefs.getInt("n_${path.name}", 0) + 1)
            .putLong("ms_${path.name}", prefs.getLong("ms_${path.name}", 0) + millis)
            .apply()
    }

    @Synchronized
    fun summary(): String {
        val total = Path.values().sumOf { prefs.getInt("n_${it.name}", 0) }
        if (total == 0) return "ainda nenhum pedido"
        return Path.values().joinToString("; ") { p ->
            val n = prefs.getInt("n_${p.name}", 0)
            val avg = if (n == 0) 0 else prefs.getLong("ms_${p.name}", 0) / n
            "${p.label}: $n (${n * 100 / total}%)" + if (n > 0) ", média $avg ms" else ""
        } + "; total $total"
    }
}
