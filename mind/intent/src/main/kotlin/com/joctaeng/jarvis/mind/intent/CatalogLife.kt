package com.joctaeng.jarvis.mind.intent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 0.31 (pedido de 14/10): clima, SMS em segundo plano e o resumo do dia completo (agenda + clima + e-mails), que também
 * é o que as tarefas programadas "me acorde com o resumo" executam de verdade.
 */
object Briefing {
    data class Parts(val agenda: Boolean = true, val weather: Boolean = true, val mail: Boolean = true, val tomorrow: Boolean = false)

    /** Uma tarefa programada pede resumo? Null = não (a tarefa segue pelo caminho normal). */
    fun wants(instruction: String): Parts? {
        val t = PtText.fold(instruction)
        val mail = Regex("""\b(e ?mails?|gmail|correio|caixa de entrada)\b""").containsMatchIn(t)
        val agenda = Regex("""\b(agenda|compromissos?|calendario|o que (eu )?tenho|programacao)\b""").containsMatchIn(t)
        val weather = Regex("""\b(clima|tempo|temperatura|chuva|previsao)\b""").containsMatchIn(t)
        val summary = Regex("""\b(resumo|resuma|bom dia|meu dia|do dia|me acorde|me acorda|acordar|briefing|novidades)\b""").containsMatchIn(t)
        if (!mail && !agenda && !weather && !summary) return null
        // Ações que não são leitura ("mande", "apague", "pague") não viram resumo.
        if (Regex("""\b(mande|manda|envie|envia|apague|apaga|pague|paga|lance|lanca|ligue|liga|abra|abre)\b""").containsMatchIn(t) &&
            !Regex("""\b(resumo|resuma|me acord)""").containsMatchIn(t)) return null
        val all = summary && !mail && !agenda && !weather
        return Parts(agenda = agenda || all || summary, weather = weather || all || summary, mail = mail || all, tomorrow = Regex("""\bamanha\b""").containsMatchIn(t))
    }

    fun greeting(ctx: Ctx): String {
        val h = ctx.now.hour
        val hi = when { h < 5 -> "Boa noite"; h < 12 -> "Bom dia"; h < 18 -> "Boa tarde"; else -> "Boa noite" }
        val name = ctx.userName.trim().split(' ').firstOrNull()?.takeIf { it.isNotBlank() }
        return if (name != null) "$hi, $name!" else "$hi!"
    }

    /**
     * O plano: agenda (essencial) e depois, cada um no seu passo (se um falhar, os outros continuam), clima e e-mails.
     * Quem faltar (sem permissão, Gmail desconectado, sem internet) entra como uma frase curta e honesta.
     */
    fun plan(intentId: String, ctx: Ctx, parts: Parts = Parts()): Outcome {
        val said = mutableListOf<String>()
        val facts = mutableListOf<String>()
        val detail = mutableListOf<String>()
        val today = ctx.today
        said += greeting(ctx) + " Hoje é ${Speak.weekday(today)}, ${today.dayOfMonth} de ${Speak.month(today)}."

        fun finish(): Next = Finish(
            Reply(said.joinToString(" "), "happy", detail = detail.joinToString("\n\n").ifBlank { null }, facts = facts.joinToString("\n\n")),
        )

        fun mailStep(): Next {
            if (!parts.mail) return finish()
            if (!ctx.has("gmail_buscar")) return finish()
            if (!ctx.gmailConnected) {
                said += "Os e-mails eu ainda não consigo ler: o Gmail não está conectado em Meu Euno."
                return finish()
            }
            return More(
                Plan(listOf(Step("gmail_buscar", mapOf("consulta" to "is:unread in:inbox newer_than:2d", "quantos" to 8)))) { r ->
                    val res = r.first()
                    if (!res.ok) said += "Não consegui ler os e-mails agora."
                    else {
                        val list = ToolText.mails(res.text)
                        facts += "E-mails não lidos:\n" + ToolText.plain(res.text)
                        said += if (list.isEmpty()) "Nenhum e-mail novo na caixa de entrada."
                        else Mail.spoken(list, "Na caixa de entrada, ${Speak.plural(list.size, "e-mail novo", "e-mails novos")}")
                    }
                    finish()
                },
            )
        }

        fun weatherStep(): Next {
            if (!parts.weather || !ctx.has("clima") || !ctx.online) return mailStep()
            return More(
                Plan(listOf(Step("clima", mapOf("dias" to 2)))) { r ->
                    val res = r.first()
                    if (res.ok) {
                        Weather.parse(res.text)?.let { w ->
                            facts += "Clima: " + ToolText.plain(res.text)
                            said += Weather.brief(w)
                        }
                    }
                    mailStep()
                },
            )
        }

        if (!parts.agenda || !ctx.has("agenda_consultar")) {
            // Sem agenda, começa direto pelo clima.
            return Run(Plan(emptyList()) { weatherStep() }, intentId)
        }
        val steps = listOf(Step("agenda_consultar", mapOf("periodo" to "hoje"))) + if (parts.tomorrow) listOf(Step("agenda_consultar", mapOf("periodo" to "amanha"))) else emptyList()
        return Run(
            Plan(steps) { r ->
                val first = r.first()
                if (!first.ok) said += Kit.humanFailure(first.text)
                else {
                    val p = ToolText.agenda(first.text)
                    said += Replies.agenda(p, "hoje")
                    facts += "Agenda de hoje:\n" + ToolText.plain(first.text)
                    Replies.agendaDetail(p)?.let { detail += "**Hoje**\n$it" }
                }
                r.getOrNull(1)?.takeIf { it.ok }?.let {
                    val p = ToolText.agenda(it.text)
                    said += Replies.agenda(p, "amanhã")
                    facts += "Agenda de amanhã:\n" + ToolText.plain(it.text)
                }
                weatherStep()
            },
            intentId,
        )
    }
}

/** O que a ferramenta "clima" devolve (Open-Meteo, montado no app). */
object Weather {
    data class Day(val date: String, val min: Int?, val max: Int?, val rain: Int?, val desc: String)
    data class Now(val city: String, val temp: Int?, val feels: Int?, val desc: String, val days: List<Day>)

    private fun num(o: JsonObject?, k: String): Int? = (o?.get(k) as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { Math.round(it).toInt() }
    private fun str(o: JsonObject?, k: String): String = (o?.get(k) as? JsonPrimitive)?.content.orEmpty()

    fun parse(raw: String): Now? {
        val o = ToolText.obj(raw) ?: return null
        val now = o["agora"] as? JsonObject
        val days = (o["dias"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { d ->
            Day(str(d, "data"), num(d, "min"), num(d, "max"), num(d, "chuva"), str(d, "descricao"))
        }.orEmpty()
        return Now(str(o, "cidade"), num(now, "temp"), num(now, "sensacao"), str(now, "descricao"), days)
    }

    private fun graus(n: Int) = if (n == 1 || n == -1) "$n grau" else "$n graus"

    /** "Agora faz 31 graus em Belém, parcialmente nublado. Hoje máxima de 33, mínima de 24 e 60% de chance de chuva." */
    fun brief(w: Now): String {
        val where = w.city.takeIf { it.isNotBlank() }?.let { " em $it" }.orEmpty()
        val now = w.temp?.let { t ->
            "Agora faz ${graus(t)}$where" + (w.desc.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()) +
                (w.feels?.takeIf { kotlin.math.abs(it - t) >= 3 }?.let { ", com sensação de ${graus(it)}" }.orEmpty()) + "."
        }.orEmpty()
        val today = w.days.firstOrNull()?.let { " " + day(it, "Hoje") }.orEmpty()
        return (now + today).trim()
    }

    fun day(d: Day, label: String): String {
        val parts = mutableListOf<String>()
        if (d.max != null) parts += "máxima de ${graus(d.max)}"
        if (d.min != null) parts += "mínima de ${graus(d.min)}"
        val rain = d.rain?.let { r -> if (r >= 15) "$r% de chance de chuva" else "pouca chance de chuva" }
        if (rain != null) parts += rain
        val desc = d.desc.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
        return "$label$desc: ${Speak.list(parts)}."
    }
}

/** Frases dos e-mails para falar (sem endereço, sem "<>", assunto curto). */
internal object Mail {
    fun sender(raw: String): String = raw.substringBefore('<').trim().trim('"').ifBlank { raw.substringAfter('<').substringBefore('@') }.ifBlank { "remetente desconhecido" }

    fun spoken(list: List<ToolText.MailItem>, intro: String, max: Int = 4): String {
        val items = list.take(max).map { "de ${sender(it.from)}, sobre ${Speak.title(it.subject, 60).ifBlank { "assunto vazio" }}" }
        val more = if (list.size > max) " E mais ${list.size - max}." else ""
        return "$intro: ${Speak.list(items)}.$more"
    }

    fun detail(list: List<ToolText.MailItem>): String? =
        if (list.isEmpty()) null else list.joinToString("\n") { "• ${it.date} — **${sender(it.from)}**: ${it.subject}" + (it.snippet.takeIf { s -> s.isNotBlank() }?.let { s -> "\n  _${s.take(140)}_" }.orEmpty()) }
}

internal object CatalogLife {
    private val sendVerbs = "manda|mande|mandar|envia|envie|enviar|escreve|escreva|escrever|dispara|dispare"

    private val dayCodes = listOf("seg", "ter", "qua", "qui", "sex", "sab", "dom")

    /**
     * "Me acorde todo dia às 7h30 com o resumo do dia e dos e-mails" (teste de 14/10: virou só um alarme/lembrete, sem resumo).
     * Vira TAREFA PROGRAMADA que, no horário, toca o som de despertar, busca agenda/clima/e-mails e fala o resumo.
     */
    private fun morningTask(m: M): Outcome {
        if (!m.has("tarefa_criar")) return NoMatch
        val t = m.time ?: return NoMatch
        val pedido = m["pedido"]?.trim().orEmpty()
        val wants = Briefing.wants("resumo $pedido") ?: return NoMatch
        val hour = if (t.ambiguous && t.hour in 1..11 && m.part in setOf("tarde", "noite")) t.hour + 12 else t.hour
        val rec = m.ex.rec
        val what = buildList { if (wants.agenda) add("a agenda"); if (wants.weather) add("o clima"); if (wants.mail) add("os e-mails") }
        val args = buildMap<String, Any?> {
            put("nome", "Despertar com resumo " + "%02d:%02d".format(hour, t.minute))
            put("pedido", "me acorde com o resumo do dia: " + Speak.list(what))
            put("hora", hour); put("minuto", t.minute); put("falar", true)
            when {
                rec == null -> put("repetir", "uma_vez")
                rec.days.size == 7 -> put("repetir", "todo_dia")
                rec.days == (1..5).toSet() -> put("repetir", "dias_uteis")
                else -> { put("repetir", "dias"); put("dias", rec.days.sorted().map { dayCodes[it - 1] }) }
            }
        }
        val whenSaid = when {
            rec == null -> "na próxima vez que for ${Speak.hm(hour, t.minute)}"
            rec.days.size == 7 -> "todo dia ${Speak.at(hour, t.minute)}"
            rec.days == (1..5).toSet() -> "de segunda a sexta ${Speak.at(hour, t.minute)}"
            else -> rec.spoken.ifBlank { "nos dias pedidos" } + " " + Speak.at(hour, t.minute)
        }
        return Kit.run(m, Step("tarefa_criar", args)) { r ->
            Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
            Reply("${Kit.ok(m)}! Vou te acordar $whenSaid com som de despertador e falar ${Speak.list(what)} do dia." +
                if (wants.mail && !m.ctx.gmailConnected) " Só que o Gmail ainda não está conectado: conecte em Meu Euno para eu ler os e-mails." else "", "happy")
        }
    }

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "task.morning", "Tarefas", "me acorda num horário (e todo dia) falando o resumo do dia (agenda, clima, e-mails)",
            slots = listOf(SlotSpec("time", "hora HH:mm", true), SlotSpec("pedido", "o que dizer: resumo, agenda, e-mails, clima")),
            templates = listOf(
                "(me )?(acorda|acorde|acordar|desperta|desperte) <R?> (as|pras|pelas|umas)? <T> <R?> (com|me dando|me dizendo|me falando|dizendo|falando|e me de|e me da|e me diga|e me fale|e fale|e diga|e faca|e me faca|e leia|lendo) {pedido}",
                "<R> (as|pras|pelas)? <T> (me )?(acorda|acorde|desperta|desperte) (com|me dando|me dizendo|dizendo|falando|e me de|e me diga|e fale|e diga|e leia|lendo) {pedido}",
                "<R> (de manha )?(as|pras|pelas)? <T> (me )?(faz|faca|da|de|diz|diga|fala|fale|traz|traga|le|leia|manda|mande) (um |o |meu )?(resumo|briefing|panorama) {pedido?}",
            ),
            priority = 7,
        ) { m -> morningTask(m) },
        IntentDef(
            "day.briefing", "Resumo", "resumo do dia: agenda de hoje, clima e e-mails novos",
            templates = listOf(
                "(resumo|balanco|panorama) (do |de |da )?(meu )?dia (de)? <D?>",
                "(como esta|como ta|como vai|como e|qual) (e )?(o )?meu dia (de)? <D?>",
                "(me )?(faz|faca|da|de|passa|passe|fala|fale|conta|traz|traga) (um |o |meu )?(resumo|balanco|panorama|briefing) (do dia|do meu dia|da manha|de)? <D?>",
                "(o que|que) (tem|temos|tenho|ha) (de novo|de novidade) (pra|para)? <D?>",
                "(quais|que) (sao )?(as )?novidades (do dia|de|pra|para)? <D?>",
                "(me )?(atualiza|atualize|situa|situe) (sobre o dia|do dia|de tudo|sobre tudo)?",
            ),
            priority = 6,
        ) { m ->
            if (!m.has("agenda_consultar") && !m.has("clima")) return@IntentDef NoMatch
            Briefing.plan(m.intentId, m.ctx, Briefing.Parts(agenda = true, weather = true, mail = true, tomorrow = false))
        },
        IntentDef(
            "weather.now", "Clima", "diz o tempo agora e a previsão (hoje, amanhã), com chance de chuva",
            slots = listOf(SlotSpec("cidade", "cidade (vazio = onde o usuário está)")),
            templates = listOf(
                "(como esta|como ta|como vai|como fica|como vai ficar|qual) (e )?(o |a )?(tempo|clima|temperatura|previsao) (do tempo|la fora|agora)?",
                "(como esta|como ta|como vai|como fica|como vai ficar|qual) (e )?(o |a )?(tempo|clima|temperatura|previsao) (do tempo)? <D>",
                "(como esta|como ta|como vai|como fica|como vai ficar|qual) (e )?(o |a )?(tempo|clima|temperatura|previsao) (do tempo|agora|la fora)? (em|no|na) {cidade}",
                "(como esta|como ta|como vai|como fica|como vai ficar|qual) (e )?(o |a )?(tempo|clima|temperatura|previsao) (do tempo)? <D> (em|no|na) {cidade}",
                "(vai|sera que vai|vai ter|tem previsao de|tem chance de) (chover|chuva|fazer sol|fazer calor|fazer frio|esfriar|esquentar) <D?> (em|no|na) {cidade}",
                "(vai|sera que vai|vai ter|tem previsao de|tem chance de) (chover|chuva|fazer sol|fazer calor|fazer frio|esfriar|esquentar) <D?>",
                "(esta|ta|vai estar|vai ficar) (fazendo )?(calor|frio|chovendo|quente|abafado) <D?>",
                "(quantos graus|que temperatura) (esta fazendo|faz|vai fazer|ta fazendo)? (agora|hoje)? <D?>",
                "(previsao do tempo|clima|tempo) (de|para|pra)? <D>",
                "(previsao do tempo|previsao) (para|pra|em|no|na) {cidade}",
                "preciso (de |levar )?(guarda chuva|sombrinha|casaco) <D?>",
            ),
            priority = 4,
        ) { m ->
            if (!m.has("clima")) return@IntentDef NoMatch
            if (!m.ctx.online) return@IntentDef Kit.say(m, "Para ver o clima eu preciso de internet, e agora estou sem conexão.", "concerned")
            val city = m["cidade"]?.trim()?.takeIf { it.length >= 3 && PtText.fold(it) !in setOf("hoje", "agora", "amanha", "aqui", "casa") }
            val offset = m.date?.let { java.time.temporal.ChronoUnit.DAYS.between(m.today, it.start).toInt() }?.coerceIn(0, 6) ?: 0
            val args = buildMap<String, Any?> { put("dias", (offset + 1).coerceIn(1, 7)); if (city != null) put("cidade", city) }
            Kit.run(m, Step("clima", args)) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                val w = Weather.parse(r[0].text) ?: return@run Reply(ToolText.plain(r[0].text).take(300), "neutral")
                val rainAsked = Regex("""\b(chov|chuva|guarda chuva|sombrinha)""").containsMatchIn(PtText.fold(m.ex.raw))
                val text = if (offset == 0) Weather.brief(w)
                else w.days.getOrNull(offset)?.let { d ->
                    val label = Speak.capitalize(Speak.date(m.date!!.start, m.today))
                    (if (w.city.isNotBlank()) "Em ${w.city}. " else "") + Weather.day(d, label)
                } ?: "Não tenho a previsão para esse dia."
                val advice = if (rainAsked) {
                    val d = w.days.getOrNull(offset)
                    when {
                        d?.rain == null -> ""
                        d.rain >= 50 -> " Melhor levar guarda-chuva."
                        d.rain >= 25 -> " Pode chover, vale levar guarda-chuva por garantia."
                        else -> " Não deve chover."
                    }
                } else ""
                Reply(text + advice, "happy", facts = "Clima: " + ToolText.plain(r[0].text))
            }
        },
        IntentDef(
            "sms.send", "SMS", "manda SMS em segundo plano para um contato (pergunta antes de enviar)",
            slots = listOf(SlotSpec("contato", "nome do contato", true), SlotSpec("mensagem", "texto, na voz do usuário", true)),
            templates = listOf(
                "($sendVerbs) (um |uma )?(sms|torpedo|mensagem de texto|mensagem sms) (pra|para|pro|ao|a) (a |o )?{contato} (dizendo|falando|avisando|perguntando|que) {mensagem}",
                "($sendVerbs) (um |uma )?(sms|torpedo|mensagem de texto) (pra|para|pro|ao|a) (a |o )?{contato}",
                "($sendVerbs) (pra|para|pro|ao|a) (a |o )?{contato} (por|via|pelo) (sms|torpedo|mensagem de texto) (dizendo|falando|avisando|perguntando|que)? {mensagem}",
                "($sendVerbs) {mensagem} (pra|para|pro|ao|a) (a |o )?{contato} (por|via|pelo) (sms|torpedo)",
            ),
            priority = 5,
        ) { m ->
            if (!m.has("sms_enviar")) return@IntentDef Kit.noTool(m, "o SMS")
            val contact = m["contato"]?.trim()?.trim(',', '.')?.takeIf { it.length >= 2 && it.split(' ').size <= 4 } ?: return@IntentDef NoMatch
            val msg = (m["mensagem+"] ?: m["mensagem"])?.trim()?.trim('"', '“', '”')?.takeIf { it.isNotBlank() }
            fun send(text: String): Outcome {
                val clean = text.replaceFirstChar { it.uppercase() }
                return Kit.plan(m, listOf(Step("sms_enviar", mapOf("contato" to contact, "mensagem" to clean))), confirm = "Mando um SMS para $contact dizendo \"$clean\"? Diga sim para enviar.") { r ->
                    Finish(Kit.firstFailure(r)?.let { Kit.failure(it) } ?: Reply("SMS enviado para $contact.", "happy"))
                }
            }
            if (msg == null) return@IntentDef Kit.ask(m, "O que digo no SMS para $contact?") { ex, _ ->
                ex.raw.trim().takeIf { it.isNotBlank() && Kit.yes(ex) == null }?.let { send(it) }
            }
            send(msg)
        },
    )
}
