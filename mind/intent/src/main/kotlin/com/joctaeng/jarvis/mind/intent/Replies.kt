package com.joctaeng.jarvis.mind.intent

/** Frases para falar o que as ferramentas devolveram, com as palavras do Euno. */
internal object Replies {
    /** "Hoje você tem 3 compromissos: às 8h, X; às 14h, Y e dia inteiro: Z." */
    fun agenda(p: AgendaParse, whenText: String, limit: Int = 6): String {
        val w = whenText.ifBlank { p.label }
        if (p.empty) return "Você não tem nenhum compromisso ${w.ifBlank { "nesse período" }}."
        val shown = p.items.take(limit)
        val total = p.items.size + p.more
        val multi = shown.map { it.day }.distinct().size > 1
        val head = "${Speak.capitalize(w.ifBlank { "No período" })} você tem ${Speak.plural(total, "compromisso", "compromissos")}"
        if (multi) {
            val byDay = shown.groupBy { it.day.orEmpty() }.entries.joinToString("; ") { (day, items) ->
                (day.substringBefore(',').ifBlank { day }) + (day.substringAfter(',', "").trim().takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": " +
                    Speak.list(items.map { itemText(it, withAt = false) })
            }
            return "$head. $byDay" + if (total > shown.size) ". E mais ${total - shown.size}." else "."
        }
        return "$head: " + Speak.list(shown.map { itemText(it) }) + if (total > shown.size) ". E mais ${total - shown.size}." else "."
    }

    fun itemText(i: AgendaItem, withAt: Boolean = true): String {
        val place = i.place?.takeIf { it.isNotBlank() }?.let { ", em $it" }.orEmpty()
        val start = i.start
        return when {
            i.allDay -> "o dia inteiro: ${i.title}$place"
            start != null -> (if (withAt) Speak.at(start.first, start.second) else Speak.hm(start.first, start.second)) + ", ${i.title}$place"
            else -> i.title + place
        }
    }

    /** Notificações: "Chegaram 7: 3 do WhatsApp (Maria, João), 2 do Gmail." */
    fun notifications(list: List<ToolText.Notif>, appFilter: String? = null): String {
        if (list.isEmpty()) return "Nenhuma notificação nova${appFilter?.let { " de $it" } ?: ""}."
        if (list.size <= 3) {
            return (if (list.size > 1) "Chegaram ${list.size}" else "Chegou 1") + ": " + Speak.list(list.map { "no ${it.app}, ${it.title}: ${it.text.take(90).trim()}" }) + "."
        }
        val groups = list.groupBy { it.app }.entries.sortedByDescending { it.value.size }
        val parts = groups.take(5).map { (app, items) ->
            val who = items.map { it.title }.filter { it.isNotBlank() }.distinct().take(3)
            "${items.size} ${if (appFilter == null) "do $app" else ""}".trim() + if (who.isNotEmpty() && app.contains("whats", true)) " (${Speak.list(who)})" else ""
        }
        return "Chegaram ${list.size} notificações: ${Speak.list(parts)}."
    }

    /** E-mails: "Você tem 3 e-mails: de A, 'assunto'; ..." */
    fun mails(list: List<ToolText.MailItem>, intro: String): String {
        if (list.isEmpty()) return "Nenhum e-mail encontrado."
        val items = list.take(5).map { "de ${it.from.ifBlank { "remetente desconhecido" }}, \"${it.subject.take(70)}\"" }
        return "$intro ${Speak.list(items)}" + if (list.size > 5) ". E mais ${list.size - 5}." else "."
    }
}
