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

    /** Fabricante dito no pedido ("Google Agenda", "agenda da Xiaomi") → pedaço do nome do pacote. */
    private val vendors = linkedMapOf(
        "google" to listOf("com.google."),
        "xiaomi" to listOf("xiaomi", "miui"),
        "miui" to listOf("xiaomi", "miui"),
        "samsung" to listOf("samsung", "sec.android"),
        "motorola" to listOf("motorola"),
    )

    private fun vendorOf(pkg: String): String? = when {
        pkg.startsWith("com.google.") -> "Google"
        "xiaomi" in pkg || "miui" in pkg -> "Xiaomi"
        "samsung" in pkg || "sec.android" in pkg -> "Samsung"
        "motorola" in pkg -> "Motorola"
        pkg.startsWith("com.android.") -> "sistema"
        else -> null
    }

    /**
     * Como [best], mas com o pacote de cada app: quando há dois apps com o MESMO nome (no Xiaomi, "Agenda" do Google e
     * "Agenda" da Xiaomi — teste da 0.26.1), escolhe pelo fabricante dito no pedido. Sem fabricante, fica com o 1º.
     * [apps] = (nome, pacote). Aceita também o nome que [displayNames] mostra ("Agenda (Google)").
     */
    fun bestApp(wantedRaw: String, apps: List<Pair<String, String>>): Int {
        val wanted = ScreenText.normalize(wantedRaw)
        val idx = best(wantedRaw, apps.map { it.first })
        if (idx < 0) return -1
        val same = apps.indices.filter { ScreenText.normalize(apps[it].first) == ScreenText.normalize(apps[idx].first) }
        if (same.size < 2) return idx
        val words = wanted.split(' ').toSet()
        val marks = vendors.entries.firstOrNull { it.key in words }?.value ?: return idx
        return same.firstOrNull { i -> marks.any { it in apps[i].second } } ?: idx
    }

    /** Nomes para mostrar ao cérebro: apps com nome repetido ganham o fabricante ("Agenda (Google)", "Agenda (Xiaomi)"). */
    fun displayNames(apps: List<Pair<String, String>>): List<String> {
        val counts = apps.groupingBy { ScreenText.normalize(it.first) }.eachCount()
        return apps.map { (label, pkg) ->
            if ((counts[ScreenText.normalize(label)] ?: 0) > 1) "$label (${vendorOf(pkg) ?: pkg})" else label
        }
    }
}
