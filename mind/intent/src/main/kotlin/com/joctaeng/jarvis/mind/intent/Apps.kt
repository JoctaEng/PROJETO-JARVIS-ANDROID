package com.joctaeng.jarvis.mind.intent

import com.joctaeng.jarvis.system.resources.AppNameMatch

/** Acha o app pelo nome falado, com apelidos ("zap", "insta") e tolerância a erro de voz ("intagram"). */
internal object Apps {
    private val aliases: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf("zap", "zapzap", "whats", "watsapp", "uatsapp", "whatsap", "wpp"),
        "whatsapp business" to listOf("zap business", "whats business", "whatsapp comercial", "zap comercial"),
        "instagram" to listOf("insta", "ig", "instagran", "intagram", "instagrama"),
        "gmail" to listOf("email", "e mail", "correio", "g mail", "jimeio", "gimail"),
        "youtube" to listOf("yt", "iutube", "you tube", "iutiube"),
        "maps" to listOf("mapas", "google maps", "mapa", "gps"),
        "calculadora" to listOf("calc", "calculadora do celular"),
        "relogio" to listOf("despertador", "relogio do celular"),
        "camera" to listOf("camara", "tirar foto"),
        "galeria" to listOf("fotos", "galeria de fotos"),
        "configuracoes" to listOf("ajustes", "config", "configuracao", "settings"),
        "joca financas" to listOf("joca", "financas", "joca financeiro", "app de financas", "jocafinancas"),
        "edumath" to listOf("edu math", "edumat", "edu mat", "matematica do professor"),
        "chrome" to listOf("navegador", "google chrome", "internet"),
        "telegram" to listOf("telegran", "tele gram"),
        "spotify" to listOf("espotifai", "spotifai", "musica"),
    )

    private val strip = Regex("^(?:(?:o|a|os|as|um|uma|app|aplicativo|do|da|de|no|na|pelo|pela|meu|minha)\\s+)+")

    fun clean(raw: String): String = PtText.fold(raw).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().replace(strip, "").trim()

    /** (rótulo, pacote) do app mais provável, ou null. */
    fun resolve(raw: String, ctx: Ctx): Pair<String, String>? {
        val wanted = clean(raw)
        if (wanted.length < 2 || ctx.apps.isEmpty()) return null
        val norm = ctx.apps.map { PtText.fold(it.first).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() }
        // 1) igual
        norm.indexOfFirst { it == wanted }.takeIf { it >= 0 }?.let { return pick(ctx, wanted, it) }
        // 2) apelido → nome oficial
        for ((official, list) in aliases) {
            if (wanted == official || wanted in list) {
                val i = norm.indexOfFirst { it == official || it.startsWith(official) || official in it }
                if (i >= 0) return pick(ctx, wanted, i)
            }
        }
        // 3) o nome do app dentro do que foi dito, ou o contrário (com cuidado com palavras curtas)
        if (wanted.length >= 4) {
            val i = AppNameMatch.bestApp(wanted, ctx.apps)
            if (i >= 0) return pick(ctx, wanted, i)
        }
        // 4) erro de voz/digitação
        var best = -1
        var bestD = Int.MAX_VALUE
        for ((i, n) in norm.withIndex()) {
            if (n.length < 4) continue
            val d = Fuzzy.distance(wanted, n)
            val limit = if (n.length >= 9) 2 else 1
            if (d <= limit && d < bestD) { best = i; bestD = d }
        }
        for ((official, list) in aliases) {
            for (alias in list + official) {
                if (alias.length < 5) continue
                if (Fuzzy.distance(wanted, alias) <= 1) {
                    val i = norm.indexOfFirst { it == official || it.startsWith(official) || official in it }
                    if (i >= 0 && bestD > 1) { best = i; bestD = 1 }
                }
            }
        }
        return if (best >= 0) pick(ctx, wanted, best) else null
    }

    /** Se há dois apps com o mesmo nome (Agenda do Google e da Xiaomi), escolhe pelo fabricante dito. */
    private fun pick(ctx: Ctx, wanted: String, i: Int): Pair<String, String> {
        val idx = AppNameMatch.bestApp(wanted, ctx.apps)
        val sameName = ctx.apps.indices.filter { PtText.fold(ctx.apps[it].first) == PtText.fold(ctx.apps[i].first) }
        return if (sameName.size > 1 && idx in sameName) ctx.apps[idx] else ctx.apps[i]
    }

    /** Nome para mandar a `abrir_app`: com o fabricante quando há dois iguais ("Agenda (Google)"). */
    fun toolName(app: Pair<String, String>, ctx: Ctx): String {
        val names = AppNameMatch.displayNames(ctx.apps)
        val i = ctx.apps.indexOf(app)
        return if (i >= 0) names[i] else app.first
    }

    fun find(ctx: Ctx, vararg words: String): Pair<String, String>? {
        for (w in words) resolve(w, ctx)?.let { return it }
        return null
    }
}

/** Distância de edição (com troca de letras vizinhas), para perdoar erro de voz e de digitação. */
internal object Fuzzy {
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
