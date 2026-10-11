package com.joctaeng.jarvis.mind.intent

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Agenda (Google e a do celular): ver, achar o próximo, horário livre, marcar, remarcar, apagar. */
internal object CatalogAgenda {
    private val verbsCreate = "marca|marque|marcar|agenda|agende|agendar|cria|crie|criar|coloca|coloque|colocar|poe|ponha|por|adiciona|adicione|adicionar|registra|registre|registrar|salva|salve|salvar|bota|insere|insira|inclui|inclua|anota|anote|anotar|lembra|lembre"
    private val verbsDelete = "apaga|apague|apagar|cancela|cancele|cancelar|remove|remova|remover|exclui|exclua|excluir|tira|tire|tirar|desmarca|desmarque|desmarcar|deleta|delete|deletar"
    private val verbsMove = "remarca|remarque|remarcar|muda|mude|mudar|move|mova|mover|adia|adie|adiar|passa|passe|passar|transfere|transfira|transferir|altera|altere|alterar|antecipa|antecipe|antecipar|troca|troque|trocar"
    private val eventWords = "compromisso|evento|reuniao|consulta|aula|lembrete|encontro|prova|atendimento|visita"

    // ---------------------------------------------------------------- datas e textos

    fun queryArgs(d: DateSlot?, today: LocalDate): Map<String, Any?> = when {
        d == null || (d.start == today && d.days == 1) -> mapOf("periodo" to "hoje")
        d.start == today.plusDays(1) && d.days == 1 -> mapOf("periodo" to "amanha")
        else -> buildMap { put("data", d.start.toString()); if (d.days > 1) put("dias", d.days.coerceIn(1, 31)) }
    }

    fun whenText(d: DateSlot?, today: LocalDate): String {
        if (d == null) return "hoje"
        return when (d.kind) {
            "lastweek" -> "na semana passada"
            "lastmonth" -> "no mês passado"
            "week" -> "nesta semana"
            "nextweek" -> "na semana que vem"
            "weekend" -> "no fim de semana"
            "month" -> "neste mês"
            "nextmonth" -> "no mês que vem"
            else -> {
                val t = Speak.date(d.start, today)
                when {
                    t in setOf("hoje", "amanhã", "depois de amanhã", "ontem") -> t
                    t.startsWith("sábado") || t.startsWith("domingo") -> "no $t"
                    t.startsWith("dia ") -> "no $t"
                    else -> "na $t"
                }
            }
        }
    }

    private fun partText(part: String?) = when (part) { "manha" -> " de manhã"; "tarde" -> " à tarde"; "noite" -> " à noite"; "madrugada" -> " de madrugada"; else -> "" }

    private fun partFilter(items: List<AgendaItem>, part: String?): List<AgendaItem> = when (part) {
        null -> items
        else -> items.filter { i ->
            val h = i.start?.first ?: return@filter true
            when (part) { "manha" -> h < 12; "tarde" -> h in 12..17; "noite" -> h >= 18; else -> h < 6 }
        }
    }

    /** O dia de um compromisso: pelo rótulo "segunda-feira, 12/10", ou hoje se a lista era de um dia só. */
    fun dayOf(i: AgendaItem, today: LocalDate): LocalDate {
        val m = Regex("""(\d{1,2})/(\d{1,2})""").find(i.day.orEmpty()) ?: return today
        val base = runCatching { LocalDate.of(today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() ?: return today
        return if (base.isBefore(today.minusDays(180))) base.plusYears(1) else base
    }

    private fun startOf(i: AgendaItem, today: LocalDate): LocalDateTime =
        dayOf(i, today).atTime(i.start?.first ?: 0, i.start?.second ?: 0)

    /** Hora do dia que o pedido quer, resolvendo "às 5" com manhã/tarde/noite. Null = perguntar. */
    fun hourOf(t: TimeSlot, part: String?): Int? {
        if (!t.ambiguous) return t.hour
        return when {
            part == "tarde" || part == "noite" -> if (t.hour < 12) (t.hour % 12) + 12 else t.hour
            part == "manha" || part == "madrugada" -> t.hour
            t.hour == 12 || t.hour in 7..11 -> t.hour
            else -> null
        }
    }

    private val stopTitle = setOf("o", "a", "os", "as", "um", "uma", "de", "do", "da", "dos", "das", "com", "no", "na", "em", "para", "pra", "meu", "minha", "compromisso", "evento")

    private fun words(s: String): List<String> = PtText.fold(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in stopTitle }

    fun matches(items: List<AgendaItem>, wanted: String, time: TimeSlot?, part: String?): List<AgendaItem> {
        val w = words(wanted)
        return items.filter { i ->
            val tw = words(i.title)
            val titleOk = w.isEmpty() || w.all { x -> tw.any { y -> y == x || (x.length >= 4 && (y.startsWith(x.take(5)) || x.startsWith(y.take(5)))) } }
            val timeOk = time == null || i.start?.let { (h, mi) -> hourOf(time, part)?.let { it == h && time.minute == mi } ?: (time.hour % 12 == h % 12 && time.minute == mi) } ?: false
            titleOk && timeOk
        }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("""(?i)\b(na|no|em)\s+(minha\s+)?(agenda|calend[aá]rio)\b"""), " ")
        t = t.replace(Regex("""(?i)\b(para|pra)\s+mim\b"""), " ")
        t = t.replace(Regex("""(?i)^(um|uma|o|a|meu|minha)\s+"""), "")
        t = t.replace(Regex("""(?i)^(compromisso|evento|lembrete)\s+(de|do|da|com|para|pra)?\s*"""), "")
        t = t.replace(Regex("""(?i)\s+(para|pra|em|no|na|do|da|de|dia|as|às|a|ao)\s*$"""), "")
        t = t.replace(Regex("""\s+"""), " ").trim().trim(',', '.', '-')
        if (Regex("""(?i)^com\s""").containsMatchIn(t)) t = "Reunião $t"
        return Kit.cap(t)
    }

    private fun stamp(d: LocalDate, h: Int, mi: Int) = "%04d-%02d-%02d %02d:%02d".format(d.year, d.monthValue, d.dayOfMonth, h, mi)

    // ---------------------------------------------------------------- criar

    private fun create(m: M, titleIn: String?, dateIn: DateSlot?, timeIn: TimeSlot?, allDayIn: Boolean = false): Outcome {
        if (!m.has("agenda_salvar")) return Kit.noTool(m, "a agenda")
        val title = titleIn?.let { cleanTitle(it) }?.takeIf { it.length >= 2 }
        if (title == null) return Kit.ask(m, "Qual o nome do compromisso?") { ex, c ->
            val t = Kit.bareText(ex).takeIf { it.isNotBlank() } ?: return@ask null
            create(M(Kit.merge(m.ex, ex), m.caps, c, m.intentId), t, dateIn, timeIn, allDayIn)
        }
        val today = m.today
        val dur = m.dur?.takeIf { it.cue.isNotBlank() && !it.cue.startsWith("daqui") && it.cue != "em" && !it.cue.startsWith("dentro") }
        val relative = m.dur?.takeIf { it.cue.startsWith("daqui") || it.cue == "em" || it.cue.startsWith("dentro") }
        if (dateIn == null && timeIn == null && relative == null) {
            return Kit.ask(m, "Para quando? Diga o dia e a hora.") { ex, c ->
                if (ex.date == null && ex.time == null && Kit.bareHour(ex) == null) null
                else create(M(Kit.merge(m.ex, ex), m.caps, c, m.intentId), title, ex.date, ex.time ?: Kit.bareHour(ex))
            }
        }
        val date = dateIn?.start
        if (relative != null && timeIn == null && date == null) {
            val at = m.ctx.now.plusSeconds(relative.seconds.toLong())
            return save(m, title, at.toLocalDate(), at.hour, at.minute, dur, false, "daqui a ${Speak.duration(relative.seconds)}")
        }
        if (timeIn == null) {
            if (allDayIn) return save(m, title, date ?: today, 0, 0, null, true)
            return Kit.ask(m, "Que horas? Se for o dia todo, diga \"o dia todo\".") { ex, c ->
                val allDay = ex.toks.any { it.norm in setOf("inteiro", "todo") } && ex.toks.any { it.norm == "dia" }
                val t = ex.time ?: Kit.bareHour(ex)
                if (!allDay && t == null) null
                else create(M(Kit.merge(m.ex, ex), m.caps, c, m.intentId), title, dateIn, t, allDay)
            }
        }
        val hour = hourOf(timeIn, m.part) ?: return Kit.ask(m, "Às ${timeIn.hour}h${if (timeIn.minute > 0) "%02d".format(timeIn.minute) else ""} da manhã ou da tarde?") { ex, c ->
            val p = ex.part ?: ex.toks.map { it.norm }.firstOrNull { it in setOf("manha", "tarde", "noite") } ?: return@ask null
            create(M(Kit.merge(m.ex, ex.copy(part = p)), m.caps, c, m.intentId), title, dateIn, timeIn, false)
        }
        val day = date ?: if (LocalTime.of(hour, timeIn.minute).isAfter(m.ctx.now.toLocalTime())) today else today.plusDays(1)
        return save(m, title, day, hour, timeIn.minute, dur, false)
    }

    private fun save(m: M, title: String, day: LocalDate, h: Int, mi: Int, dur: DurSlot?, allDay: Boolean, spokenWhen: String? = null): Outcome {
        val args = mutableMapOf<String, Any?>("titulo" to title, "inicio" to stamp(day, h, mi))
        if (allDay) args["dia_inteiro"] = true
        dur?.let { args["duracao_min"] = (it.seconds / 60).coerceIn(5, 1440) }
        return Kit.run(m, Step("agenda_salvar", args)) { r ->
            Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
            val o = ToolText.obj(r[0].text)
            val whenSaid = spokenWhen ?: (Speak.date(day, m.today) + if (allDay) ", o dia todo" else " " + Speak.at(h, mi))
            if (ToolText.str(o, "ja_existia") == "true") Reply("Esse compromisso já estava na agenda: $title, $whenSaid. Não criei outro.")
            else Reply("${Kit.ok(m)}, marquei $title para $whenSaid" + (dur?.let { ", com ${Speak.duration(it.seconds)} de duração" } ?: "") + ".")
        }
    }

    // ---------------------------------------------------------------- achar e escolher

    private fun pickOne(items: List<AgendaItem>, ex: Extracted, today: LocalDate): AgendaItem? {
        val ordinals = mapOf("primeiro" to 1, "primeira" to 1, "segundo" to 2, "segunda" to 2, "terceiro" to 3, "terceira" to 3, "quarto" to 4, "quarta" to 4)
        ex.time?.let { t -> items.singleOrNull { i -> i.start?.let { (h, mi) -> mi == t.minute && (h == t.hour || h % 12 == t.hour % 12) } == true }?.let { return it } }
        ex.toks.firstNotNullOfOrNull { ordinals[it.norm] }?.let { n -> items.getOrNull(n - 1)?.let { return it } }
        ex.numbers.singleOrNull()?.let { n -> if (n in 1..items.size) return items[n - 1] }
        val byTitle = items.filter { i -> words(Kit.bareText(ex)).let { w -> w.isNotEmpty() && w.all { x -> words(i.title).any { y -> y.startsWith(x.take(5)) } } } }
        return byTitle.singleOrNull()
    }

    private fun describeItem(i: AgendaItem, today: LocalDate, withDay: Boolean): String {
        val day = if (withDay) Speak.date(dayOf(i, today), today) + ", " else ""
        return day + Replies.itemText(i)
    }

    /** Lista de opções numeradas para o usuário escolher. */
    private fun choose(m: M, found: List<AgendaItem>, verb: String, then: (AgendaItem, Ctx) -> Outcome): Reply {
        val multi = found.map { dayOf(it, m.today) }.distinct().size > 1
        val list = found.take(5).mapIndexed { n, i -> "${n + 1}) ${describeItem(i, m.today, multi)}" }.joinToString("; ")
        return Reply(
            "Achei ${found.size}: $list. Qual deles você quer $verb? Diga o número ou a hora.", "neutral",
            Ask("Qual deles?", m.intentId) { ex, c -> pickOne(found, ex, m.today)?.let { then(it, c) } },
        )
    }

    private fun lookup(m: M, wanted: String, time: TimeSlot?, date: DateSlot?, verb: String, then: (AgendaItem, Ctx) -> Outcome): Outcome {
        if (!m.has("agenda_consultar")) return Kit.noTool(m, "a agenda")
        val args = if (date != null) queryArgs(date, m.today) else mapOf("data" to m.today.toString(), "dias" to 31)
        return Kit.plan(m, listOf(Step("agenda_consultar", args))) { r ->
            r.firstOrNull { !it.ok }?.let { return@plan Finish(Kit.failure(it)) }
            val parsed = ToolText.agenda(r[0].text)
            val found = matches(parsed.items, wanted, time, m.part)
            when {
                found.isEmpty() -> Finish(
                    Reply(
                        if (parsed.items.isEmpty()) "Não achei compromisso nenhum ${if (date != null) whenText(date, m.today) else "nos próximos 30 dias"}."
                        else "Não achei nenhum compromisso com \"$wanted\"" + (date?.let { " ${whenText(it, m.today)}" } ?: " nos próximos 30 dias") + ".",
                        "concerned",
                    ),
                )
                found.size > 1 -> Finish(choose(m, found, verb) { item, c -> then(item, c) })
                else -> when (val o = then(found.first(), m.ctx)) {
                    is Run -> More(o.plan)
                    is Ask -> Finish(Reply(o.question, "neutral", o))
                    is Say -> Finish(Reply(o.text, o.emotion))
                    else -> Finish(Reply("Não consegui continuar.", "concerned"))
                }
            }
        }
    }

    // ---------------------------------------------------------------- catálogo

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "agenda.query", "Agenda", "diz os compromissos de um dia, da semana ou do mês",
            slots = listOf(SlotSpec("date", "o dia AAAA-MM-DD; vazio = hoje"), SlotSpec("days", "quantos dias a partir da data")),
            templates = listOf(
                "(o que|que|quais|qual) (eu )?(tenho|vou ter|estou com|to com) (marcado|agendado|de compromisso|de compromissos|na agenda|no calendario|pra fazer|para fazer)? <D?>",
                "(o que|que|quais) (tem|ha|existe|vai ter|esta marcado|tem marcado|tem agendado) <D>",
                "(o que|quais|que) (tem|ha) (na|no) (minha )?(agenda|calendario) <D?>",
                "(como esta|como ta|me mostra|mostra|mostre|ver|veja|le|leia|confere|confira|consulta|consulte|olha|olhe|checa|verifica|verifique|me diz|diga|fala|fale|me fala|me conta) (a |minha |meu |o |os |meus )?(minha |meus )?(agenda|calendario|compromissos|programacao) <D?>",
                "(minha |a )?(agenda|calendario|compromissos|programacao) <D>",
                "(minha |a )?(agenda|calendario|compromissos|programacao) (de|para|pra|do|da|dos)? <D?>",
                "(tenho|tem|ha) (algum|alguma|algum outro)? (algo|alguma coisa|coisa|nada|compromisso|compromissos|reuniao|reunioes|aula|aulas|consulta|consultas|evento|eventos) (marcado|marcada|agendado|agendada|na agenda|pra fazer|para fazer)? <D?>",
                "(qual|quais) (e |sao )?(a |as |os )?(minha |minhas |meus )?(agenda|programacao|compromissos) <D?>",
                "<D> (eu )?(tenho|tem) (algo|alguma coisa|compromisso|compromissos|reuniao|aula|aulas)",
                "<D> (como esta|o que tem na|e) (a )?(minha )?agenda",
            ),
            follow = listOf("e <D>", "e <D> (como fica|tem algo|e a agenda|tem compromisso|e la)", "e", "e (la|ai)? <D>"),
        ) { m ->
            if (!m.has("agenda_consultar")) return@IntentDef Kit.noTool(m, "a agenda")
            val d = m.date
            Kit.run(m, Step("agenda_consultar", queryArgs(d, m.today))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val p = ToolText.agenda(r[0].text)
                val filtered = if (m.part != null) AgendaParse(p.label, partFilter(p.items, m.part), 0) else p
                Reply(Replies.agenda(filtered, whenText(d, m.today) + partText(m.part)), "happy")
            }
        },
        IntentDef(
            "agenda.next", "Agenda", "diz o próximo compromisso",
            templates = listOf(
                "(qual|quando) (e|eh|sera|vai ser) (o |a )?(meu |minha )?(proximo|proxima|seguinte) (compromisso|evento|reuniao|aula|consulta)",
                "(qual|quando) (e|eh|sera) (o |a )?(meu |minha )?(proximo|proxima) (coisa )?(na agenda|da agenda|marcado)?",
                "(o que|que) (eu )?(tenho|vem|vai ter|tem) (agora|em seguida|depois|logo mais|a seguir|daqui a pouco|mais tarde)",
                "(qual|quando) (o |a )?(meu |minha )?(proximo|proxima) (compromisso|evento|reuniao|aula|consulta)?",
                "(tenho|tem) (algum|alguma)? (compromisso|reuniao|aula|consulta|evento)? (agora|logo|em breve|mais tarde|daqui a pouco)",
            ),
            priority = 2,
        ) { m ->
            if (!m.has("agenda_consultar")) return@IntentDef Kit.noTool(m, "a agenda")
            Kit.run(m, Step("agenda_consultar", mapOf("data" to m.today.toString(), "dias" to 14))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val now = m.ctx.now.toLocalDateTime()
                val upcoming = ToolText.agenda(r[0].text).items.filter { i -> startOf(i, m.today).let { it.isAfter(now) || (i.allDay && dayOf(i, m.today) > m.today) } }
                    .sortedBy { startOf(it, m.today) }
                val next = upcoming.firstOrNull() ?: return@run Reply("Você não tem mais nenhum compromisso nos próximos 14 dias.")
                val day = dayOf(next, m.today)
                val sameTime = upcoming.filter { startOf(it, m.today) == startOf(next, m.today) }
                val what = Speak.list(sameTime.map { Replies.itemText(it, withAt = false).let { t -> if (next.allDay) t else t.substringAfter(", ") } })
                val whenSaid = if (next.allDay) "${Speak.date(day, m.today)}, o dia todo" else "${Speak.date(day, m.today)} ${Speak.at(next.start!!.first, next.start!!.second)}"
                Reply("Seu próximo compromisso é $whenSaid: $what.", "happy")
            }
        },
        IntentDef(
            "agenda.count", "Agenda", "conta os compromissos de um dia ou período",
            templates = listOf("quantos (compromissos|eventos|reunioes|aulas|consultas) (eu )?(tenho|tem|ha|vou ter)? <D?>", "(tenho|tem) quantos (compromissos|eventos|reunioes|aulas) <D?>"),
            priority = 2,
        ) { m ->
            if (!m.has("agenda_consultar")) return@IntentDef Kit.noTool(m, "a agenda")
            Kit.run(m, Step("agenda_consultar", queryArgs(m.date, m.today))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val p = ToolText.agenda(r[0].text)
                val n = p.items.size + p.more
                val w = whenText(m.date, m.today)
                Reply(if (n == 0) "Você não tem nenhum compromisso $w." else "$w você tem ${Speak.plural(n, "compromisso", "compromissos")}.".replaceFirstChar { it.uppercase() })
            }
        },
        IntentDef(
            "agenda.free", "Agenda", "diz os horários livres de um dia",
            templates = listOf(
                "(estou|to|fico|vou estar|estarei) livre <D?> <T?>",
                "(tenho|tem|ha) (algum )?(horario|tempo|janela|espaco) (livre|vago|sobrando|disponivel)? <D?>",
                "quando (eu )?(estou|to|fico|tenho) (livre|vago|disponivel|um horario livre)? <D?>",
                "(qual|quais) (e |sao )?(o |os )?(meu |meus )?(horario|horarios) (livre|livres|vago|vagos) <D?>",
            ),
            priority = 2,
        ) { m ->
            if (!m.has("agenda_consultar")) return@IntentDef Kit.noTool(m, "a agenda")
            val d = m.date
            Kit.run(m, Step("agenda_consultar", queryArgs(d, m.today))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val items = ToolText.agenda(r[0].text).items.filter { !it.allDay && it.start != null }
                val w = whenText(d, m.today)
                if (items.isEmpty()) return@run Reply("Você está livre $w, sem nenhum compromisso marcado.")
                val busy = items.map { (it.start!!.first * 60 + it.start!!.second) to ((it.end?.let { e -> e.first * 60 + e.second }) ?: (it.start!!.first * 60 + it.start!!.second + 60)) }.sortedBy { it.first }
                val gaps = mutableListOf<Pair<Int, Int>>()
                var cursor = if (d == null || d.start == m.today) maxOf(8 * 60, m.ctx.now.hour * 60 + m.ctx.now.minute) else 8 * 60
                for ((s, e) in busy) {
                    if (s - cursor >= 60) gaps += cursor to s
                    cursor = maxOf(cursor, e)
                }
                if (18 * 60 - cursor >= 60) gaps += cursor to 18 * 60
                val fmt = { x: Int -> Speak.hm(x / 60, x % 60) }
                val busyText = Speak.list(busy.map { "${fmt(it.first)} às ${fmt(it.second)}" })
                Reply(
                    if (gaps.isEmpty()) "Ocupado $w: $busyText. Não sobra uma hora livre entre 8h e 18h."
                    else "Ocupado $w: $busyText. Livre: " + Speak.list(gaps.map { "das ${fmt(it.first)} às ${fmt(it.second)}" }) + ".",
                )
            }
        },
        IntentDef(
            "agenda.summary", "Agenda", "resumo do dia: compromissos de hoje e de amanhã",
            templates = listOf(
                "(resumo|balanco|panorama) (do |de |da )?(meu )?(dia|hoje)",
                "(como esta|como ta|como vai|qual) (e )?(o )?meu dia (hoje)?",
                "meu dia",
                "(me )?(faz|faca|da|de|passa|passe|fala|fale|conta) (um |o )?(resumo|balanco|panorama) (do dia|de hoje|do meu dia)?",
                "(fechar|fecha|feche|encerrar|encerra) (o )?(meu )?dia",
            ),
            priority = 3,
        ) { m ->
            if (!m.has("agenda_consultar")) return@IntentDef Kit.noTool(m, "a agenda")
            Kit.run(m, Step("agenda_consultar", mapOf("periodo" to "hoje")), Step("agenda_consultar", mapOf("periodo" to "amanha"))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val a = Replies.agenda(ToolText.agenda(r[0].text), "hoje")
                val b = Replies.agenda(ToolText.agenda(r[1].text), "amanhã")
                Reply("$a $b", "happy")
            }
        },
        IntentDef(
            "agenda.create", "Agenda", "marca um compromisso (título, dia e hora; duração opcional)",
            slots = listOf(SlotSpec("titulo", "o que é", true), SlotSpec("date", "dia AAAA-MM-DD"), SlotSpec("time", "hora HH:mm"), SlotSpec("duration_min", "duração em minutos")),
            templates = listOf(
                "($verbsCreate) (um|uma|o|a|na minha agenda|na agenda|no calendario|no meu calendario)? {titulo} (para|pra|em|no|na|do|de|dia|as|ate)? <D?> (para|pra|em|no|na|do|de|dia|as|ate)? <T?> <DUR?>",
                "($verbsCreate) (um|uma|o|a|na minha agenda|na agenda|no calendario)? <D> (para|pra|em|no|na|do|de|dia|as|ate)? <T?> (de|com|para|pra|do|da)? {titulo} <DUR?>",
                "($verbsCreate) (um|uma|o|a|na minha agenda|na agenda|no calendario)? <T> (de|com|para|pra|do|da)? {titulo} (para|pra|em|no|na|do|de|dia|as)? <D?>",
                "(me )?(lembra|lembre|avisa|avise) (de|do|da|que tenho|que eu tenho)? {titulo} (para|pra|em|no|na|do|de|dia|as)? <D?> (para|pra|em|no|na|do|de|dia|as)? <T?>",
                "(tenho|vou ter|vai ter|tem) (um|uma)? ($eventWords) (de|com|do|da)? {titulo?} <D> (as|a|para)? <T?> (anota|anote|coloca|coloque|marca|marque|poe|ponha) (na agenda|ai)",
            ),
        ) { m ->
            val titulo = m["titulo"] ?: return@IntentDef NoMatch
            val t = PtText.fold(titulo)
            // "anota"/"coloca" sem data nem hora é anotação, não compromisso: deixa para a memória.
            val soft = m.ex.toks.firstOrNull()?.norm in setOf("anota", "anote", "anotar", "coloca", "coloque", "poe", "ponha", "por", "bota", "lembra", "lembre", "salva", "salve", "registra", "registre")
            if (soft && m.date == null && m.time == null && m.dur == null && !Regex("""\b($eventWords|agenda|calendario)\b""").containsMatchIn(PtText.fold(Kit.bareText(m.ex)))) return@IntentDef NoMatch
            if (t.startsWith("que ") || t in setOf("isso", "aquilo", "ai", "isto") || m.money != null) return@IntentDef NoMatch
            create(m, titulo, m.date, m.time)
        },
        IntentDef(
            "agenda.delete", "Agenda", "apaga um compromisso (achando pelo nome, dia e hora)",
            slots = listOf(SlotSpec("titulo", "nome do compromisso", true), SlotSpec("date", "dia AAAA-MM-DD"), SlotSpec("time", "hora HH:mm")),
            templates = listOf(
                "($verbsDelete) (o|a|meu|minha|um|uma)? ($eventWords)? (de|com|do|da)? {titulo} (da agenda|do calendario|na agenda|no calendario)? <D?> <T?>",
                "($verbsDelete) (o|a|meu|minha|um|uma)? ($eventWords) <D?> <T?>",
                "(nao vou|nao vai|nao posso|nao da pra|nao consigo) (mais )?(ir|na|no|a|ao)? {titulo} <D?> <T?> (apaga|apague|tira|tire|cancela|cancele|desmarca|desmarque)",
            ),
            priority = 2,
        ) { m ->
            val titulo = m["titulo"].orEmpty()
            if (titulo.isBlank() && m.date == null && m.time == null) return@IntentDef NoMatch
            if (PtText.fold(titulo).let { it.startsWith("alarme") || it.startsWith("timer") || it.startsWith("tarefa") || it.startsWith("lembrete de tarefa") || it.startsWith("mensagem") || it.startsWith("email") || it.startsWith("memoria") }) return@IntentDef NoMatch
            if (!m.has("agenda_apagar")) return@IntentDef Kit.noTool(m, "a agenda")
            val askDelete = { item: AgendaItem, c: Ctx ->
                val what = describeItem(item, m.today, true)
                val step = Step("agenda_apagar", mapOf("id" to (item.id ?: 0L)))
                Kit.plan(M(m.ex, m.caps, c, m.intentId), listOf(step), confirm = "Apagar $what? Diga sim para confirmar.") { r ->
                    Finish(Kit.firstFailure(r)?.let { Kit.failure(it) } ?: Reply("Apagado: ${item.title}."))
                }
            }
            lookup(m, titulo, m.time, m.date, "apagar") { item, c ->
                if (item.id == null) Say("Achei \"${item.title}\", mas não consegui o número dele para apagar.", m.intentId) else askDelete(item, c)
            }
        },
        IntentDef(
            "agenda.move", "Agenda", "remarca um compromisso para outro dia ou hora",
            slots = listOf(SlotSpec("titulo", "nome do compromisso", true), SlotSpec("date", "novo dia AAAA-MM-DD"), SlotSpec("time", "nova hora HH:mm")),
            templates = listOf(
                "($verbsMove) (o|a|meu|minha|um|uma)? ($eventWords)? (de|com|do|da)? {titulo} (para|pra|ate|pro|em|no|na|dia|as)? <D?> (para|pra|ate|pro|em|no|na|dia|as)? <T?>",
            ),
            priority = 2,
        ) { m ->
            val titulo = m["titulo"] ?: return@IntentDef NoMatch
            if (m.date == null && m.time == null) return@IntentDef NoMatch
            if (!m.has("agenda_editar")) return@IntentDef Kit.noTool(m, "a agenda")
            val newHour = m.time?.let { hourOf(it, m.part) }
            if (m.time != null && newHour == null) return@IntentDef Kit.ask(m, "Às ${m.time!!.hour}h da manhã ou da tarde?") { ex, c ->
                val p = ex.part ?: return@ask null
                CatalogAgenda.defs.first { it.id == "agenda.move" }.build(M(Kit.merge(m.ex, ex.copy(part = p)), m.caps, c, m.intentId))
            }
            val cleaned = cleanTitle(titulo)
            lookup(m, cleaned, null, null, "remarcar") { item, c ->
                val oldDay = dayOf(item, m.today)
                val day = m.date?.start ?: oldDay
                val h = newHour ?: item.start?.first ?: 9
                val mi = if (m.time != null) m.time!!.minute else item.start?.second ?: 0
                val minutes = item.end?.let { (it.first * 60 + it.second) - ((item.start?.first ?: 0) * 60 + (item.start?.second ?: 0)) }?.takeIf { it in 5..1440 } ?: 60
                if (item.id == null) Say("Achei \"${item.title}\", mas não consegui o número dele para remarcar.", m.intentId)
                else Kit.run(M(m.ex, m.caps, c, m.intentId), Step("agenda_editar", mapOf("id" to item.id, "inicio" to stamp(day, h, mi), "duracao_min" to minutes))) { r ->
                    Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                    Reply("${Kit.ok(m)}, passei \"${item.title}\" para ${Speak.date(day, m.today)} ${Speak.at(h, mi)}.")
                }
            }
        },
        IntentDef(
            "agenda.open", "Agenda", "abre o app da agenda (Google ou do celular) num dia",
            slots = listOf(SlotSpec("date", "dia AAAA-MM-DD"), SlotSpec("app", "google ou celular")),
            templates = listOf(
                "(abre|abra|abrir|mostra|mostre|vai para|va para|me leva para|ver|veja) (a |na |minha )?(minha )?agenda (do|da|de|no|na)? {qual?} <D>",
                "(abre|abra|abrir|mostra|mostre|vai para|va para|me leva para|ver|veja) (a |na |minha )?(minha )?agenda (do|da|de|no|na) {qual}",
                "(abre|abra|abrir|mostra|mostre|vai para|va para|me leva para|ver|veja) (o )?(dia|calendario) <D>",
                "(abre|abra|abrir|mostra|mostre) (o )?(meu )?calendario",
            ),
            priority = 3,
        ) { m ->
            if (!m.has("agenda_abrir")) return@IntentDef NoMatch
            val qual = PtText.fold(m["qual"].orEmpty())
            val app = when {
                "google" in qual -> "google"
                qual.isNotBlank() && qual.split(' ').any { it in setOf("celular", "xiaomi", "telefone", "aparelho", "local", "miui", "hyperos", "sistema", "padrao") } -> "celular"
                qual.isBlank() -> m.ctx.defaultAgenda
                else -> return@IntentDef NoMatch
            }
            val day = m.date?.start ?: m.today
            val args = mutableMapOf<String, Any?>("data" to day.toString())
            if (app.isNotBlank()) args["app"] = app
            Kit.run(m, Step("agenda_abrir", args)) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Pronto, abri a agenda ${if (m.date == null) "de hoje" else "em " + Speak.date(day, m.today)}.")
            }
        },
    )
}
