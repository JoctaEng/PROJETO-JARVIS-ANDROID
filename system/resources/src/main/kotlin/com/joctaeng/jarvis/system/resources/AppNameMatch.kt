package com.joctaeng.jarvis.system.resources

/** Escolhe o app pelo nome falado ("Google Agenda" → o app chamado "Agenda"). Puro, para testar. */
object AppNameMatch {
    private val fillers = listOf("aplicativo do ", "aplicativo ", "app do ", "app da ", "app de ", "app ", "google ")

    private fun strip(n: String): String {
        var s = n
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) if (s.startsWith(f) && s.length > f.length) { s = s.removePrefix(f); changed = true }
        }
        return s
    }

    /** [labels] são os nomes dos apps; devolve o índice do melhor, ou -1. */
    fun best(wantedRaw: String, labels: List<String>): Int {
        val wanted = ScreenText.normalize(wantedRaw)
        if (wanted.isBlank()) return -1
        val short = strip(wanted)
        val norm = labels.map { ScreenText.normalize(it) }
        val stripped = norm.map { strip(it) }
        val rules: List<(Int) -> Boolean> = listOf(
            { i -> norm[i] == wanted },
            { i -> stripped[i] == short },
            { i -> norm[i].startsWith(wanted) },
            { i -> stripped[i].startsWith(short) },
            { i -> wanted in norm[i] },
            // o nome do app está dentro do que foi dito ("google agenda" contém "agenda")
            { i -> stripped[i].length >= 3 && " $short ".contains(" ${stripped[i]} ") },
        )
        for (rule in rules) {
            val hit = norm.indices.filter(rule).minByOrNull { norm[it].length }
            if (hit != null) return hit
        }
        return -1
    }
}
