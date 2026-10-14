package com.joctaeng.jarvis.mind.intent

/** Frases para falar o que as ferramentas devolveram, com as palavras do Euno. */
internal object Replies {
    /**
     * Agenda falada como gente (0.31, pedido do teste de 14/10: "fala igual um robô, lê barra, hífen e número de processo").
     * Fala o total, junta os itens do dia todo com títulos curtos e diz os com horário em ordem; o resto fica na tela ([agendaDetail]).
     */
    fun agenda(p: AgendaParse, whenText: String, limit: Int = 6): String {
        val w = whenText.ifBlank { p.label }.ifBlank { "nesse período" }
        if (p.empty) return "Você não tem nenhum compromisso $w."
        val total = p.items.size + p.more
        val head = "${Speak.capitalize(w)} você tem ${Speak.plural(total, "compromisso", "compromissos")}"
        val days = p.items.map { it.day.orEmpty() }.distinct()
        if (days.size > 1) {
            var budget = limit + 2
            val parts = mutableListOf<String>()
            for (day in days) {
                val items = p.items.filter { it.day.orEmpty() == day }
                if (budget <= 0) break
                val said = dayItems(items, budget, sentences = false)
                budget -= items.size
                parts += "${spokenDay(day)}: $said"
            }
            val tail = if (parts.size < days.size) " Os outros dias estão na tela." else if (p.more > 0) " E mais ${p.more}." else ""
            return "$head. " + parts.joinToString(". ") + "." + tail
        }
        return "$head. " + dayItems(p.items, limit, sentences = true) + "." + if (p.more > 0) " E mais ${p.more}." else ""
    }

    /** Um dia: primeiro o que é o dia todo (agrupado), depois os com horário, sem passar de [limit] itens falados. */
    private fun dayItems(items: List<AgendaItem>, limit: Int, sentences: Boolean): String {
        val allDay = items.filter { it.allDay }.groupBy { Speak.title(it.title) }.entries.map { (t, list) -> if (list.size > 1) "$t, ${list.size} vezes" else t }
        val timed = items.filter { !it.allDay }
        val out = mutableListOf<String>()
        var room = limit
        if (allDay.isNotEmpty()) {
            val said = allDay.take(maxOf(2, room / 2))
            room -= said.size
            out += "o dia todo, " + Speak.list(said + if (allDay.size > said.size) listOf("mais ${allDay.size - said.size}") else emptyList())
        }
        if (timed.isNotEmpty()) {
            val said = timed.take(maxOf(2, room))
            val extra = timed.size - said.size
            out += (if (allDay.isNotEmpty()) "com horário, " else "") + Speak.list(said.map { itemText(it) } + if (extra > 0) listOf("mais $extra") else emptyList())
        }
        return if (sentences) out.joinToString(". ") { Speak.capitalize(it) } else out.joinToString("; ")
    }

    /** "segunda-feira, 12/10" → "Segunda-feira, dia 12". */
    fun spokenDay(label: String): String {
        val m = Regex("""^(.*?),?\s*(\d{1,2})/(\d{1,2})""").find(label.trim()) ?: return Speak.capitalize(label)
        val name = m.groupValues[1].trim().trimEnd(',')
        return Speak.capitalize(if (name.isBlank()) "dia ${m.groupValues[2].toInt()}" else "$name, dia ${m.groupValues[2].toInt()}")
    }

    /** A lista completa, para a tela (títulos inteiros, horário de início e fim). */
    fun agendaDetail(p: AgendaParse): String? {
        if (p.empty || p.items.size < 2) return null
        val multi = p.items.map { it.day }.distinct().size > 1
        return buildString {
            var lastDay: String? = null
            for (i in p.items) {
                if (multi && i.day != lastDay) { appendLine("**${spokenDay(i.day.orEmpty())}**"); lastDay = i.day }
                append("• ")
                append(if (i.allDay) "dia todo" else i.time.orEmpty())
                append(" — ${i.title}")
                i.place?.takeIf { it.isNotBlank() }?.let { append(" (${it})") }
                appendLine()
            }
            if (p.more > 0) appendLine("…e mais ${p.more}.")
        }.trimEnd()
    }

    fun itemText(i: AgendaItem, withAt: Boolean = true): String {
        val place = i.place?.takeIf { it.isNotBlank() }?.let { ", em ${Speak.title(it)}" }.orEmpty()
        val start = i.start
        val title = Speak.title(i.title)
        return when {
            i.allDay -> "o dia inteiro: $title$place"
            start != null -> (if (withAt) Speak.at(start.first, start.second) else Speak.hm(start.first, start.second)) + ", $title$place"
            else -> title + place
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
