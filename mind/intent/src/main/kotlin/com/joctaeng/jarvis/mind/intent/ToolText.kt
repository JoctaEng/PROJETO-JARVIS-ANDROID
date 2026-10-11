package com.joctaeng.jarvis.mind.intent

/** Um compromisso lido da saída da ferramenta de agenda. [time] "08:00–09:00" (ou null se o dia inteiro). */
data class AgendaItem(val day: String?, val time: String?, val title: String, val place: String?, val id: Long?) {
    val allDay: Boolean get() = time == null
    /** Hora de início "08:00" → (8, 0). */
    val start: Pair<Int, Int>? get() = time?.substringBefore('–')?.split(':')?.let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    val end: Pair<Int, Int>? get() = time?.substringAfter('–', "")?.takeIf { it.isNotEmpty() }?.split(':')?.let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
}

data class AgendaParse(val label: String, val items: List<AgendaItem>, val more: Int) {
    val empty: Boolean get() = items.isEmpty()
}

/** Lê o texto que as ferramentas devolvem (agenda, e-mails, notificações, Joca Finanças) para o Euno falar com as próprias palavras. */
object ToolText {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Objeto JSON de uma ferramenta (ex.: estado do celular), ou null se a saída é texto comum. */
    fun obj(raw: String): kotlinx.serialization.json.JsonObject? {
        val t = raw.trim()
        if (!t.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(t) as? kotlinx.serialization.json.JsonObject }.getOrNull()
    }

    fun str(o: kotlinx.serialization.json.JsonObject?, key: String): String? =
        (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content

    /** O texto da ferramenta: se veio num envelope JSON com campo de texto, só o texto. */
    fun plain(raw: String): String {
        val o = obj(raw) ?: return raw.trim()
        return str(o, "texto") ?: str(o, "text") ?: raw.trim()
    }

    fun agenda(text: String): AgendaParse {
        val lines = plain(text).lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        var label = ""
        var day: String? = null
        var more = 0
        val items = mutableListOf<AgendaItem>()
        for (l in lines) {
            when {
                l.startsWith("Agenda para ") -> label = l.removePrefix("Agenda para ").removeSuffix(":")
                l.startsWith("Nenhum compromisso") -> return AgendaParse(l.substringAfter("para ", "").removeSuffix("."), emptyList(), 0)
                l.startsWith("…e mais ") -> more = Regex("""\d+""").find(l)?.value?.toInt() ?: 0
                l.startsWith("- ") -> {
                    val body = l.removePrefix("- ")
                    val time = Regex("""^(\d{1,2}:\d{2}(?:–\d{1,2}:\d{2})?)\s+""").find(body)?.groupValues?.get(1)
                    var rest = if (time != null) body.removePrefix(time).trim() else body.removePrefix("dia inteiro").trim()
                    val id = Regex("""\[id (\d+)]\s*$""").find(rest)?.groupValues?.get(1)?.toLong()
                    if (id != null) rest = rest.replace(Regex("""\s*\[id \d+]\s*$"""), "")
                    val place = Regex("""\(local: ([^)]*)\)\s*$""").find(rest)?.groupValues?.get(1)
                    if (place != null) rest = rest.replace(Regex("""\s*\(local: [^)]*\)\s*$"""), "")
                    items += AgendaItem(day, time, rest.trim(), place, id)
                }
                l.endsWith(":") -> day = l.removeSuffix(":")
            }
        }
        return AgendaParse(label, items, more)
    }

    data class MailItem(val id: String, val date: String, val from: String, val subject: String, val snippet: String)

    /** "- [id X] data · remetente · assunto — trecho" */
    fun mails(text: String): List<MailItem> = plain(text).lines().mapNotNull { l ->
        val m = Regex("""^- \[id ([^\]]+)] (.*?) · (.*?) · (.*?)(?: — (.*))?$""").find(l.trim()) ?: return@mapNotNull null
        MailItem(m.groupValues[1], m.groupValues[2], m.groupValues[3].substringBefore('<').trim().trim('"'), m.groupValues[4], m.groupValues[5])
    }

    data class Notif(val time: String, val app: String, val title: String, val text: String)

    /** "- 14:30 · WhatsApp · Maria: oi, tudo bem?" */
    fun notifications(text: String): List<Notif> = plain(text).lines().mapNotNull { l ->
        val m = Regex("""^- (\d{1,2}:\d{2}) · (.*?) · (.*?): (.*)$""").find(l.trim()) ?: return@mapNotNull null
        Notif(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
    }

    /** "R$ 2.100,77" → 210077 (centavos); aceita sinal. */
    fun cents(s: String): Long? {
        val m = Regex("""(-?)R\$\s*(-?)(\d{1,3}(?:\.\d{3})*|\d+),(\d{2})""").find(s) ?: return null
        val neg = m.groupValues[1] == "-" || m.groupValues[2] == "-"
        val v = m.groupValues[3].replace(".", "").toLong() * 100 + m.groupValues[4].toLong()
        return if (neg) -v else v
    }

    /** O primeiro valor em reais depois de [label] ("Total em contas: R$ 2.100,77"). */
    fun moneyAfter(text: String, label: String): Long? {
        val i = text.indexOf(label, ignoreCase = true)
        if (i < 0) return null
        return cents(text.substring(i + label.length))
    }
}
