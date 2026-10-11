package com.joctaeng.jarvis.mind.intent

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Data, hora, feriados, contagem de dias e contas: o Euno responde sozinho, pelo relógio do celular. */
internal object CatalogTime {
    private fun fullDate(d: LocalDate) = "${Speak.weekday(d)}, ${d.dayOfMonth} de ${Speak.month(d)} de ${d.year}"

    private val named: Map<String, (Int) -> LocalDate> = mapOf(
        "natal" to { y -> LocalDate.of(y, 12, 25) }, "ano novo" to { y -> LocalDate.of(y + 1, 1, 1) }, "reveillon" to { y -> LocalDate.of(y, 12, 31) },
        "tiradentes" to { y -> LocalDate.of(y, 4, 21) }, "dia do trabalho" to { y -> LocalDate.of(y, 5, 1) }, "independencia" to { y -> LocalDate.of(y, 9, 7) },
        "aparecida" to { y -> LocalDate.of(y, 10, 12) }, "finados" to { y -> LocalDate.of(y, 11, 2) }, "proclamacao da republica" to { y -> LocalDate.of(y, 11, 15) },
        "consciencia negra" to { y -> LocalDate.of(y, 11, 20) }, "pascoa" to { y -> BrHolidays.easter(y) }, "carnaval" to { y -> BrHolidays.easter(y).minusDays(47) },
        "dia das maes" to { y -> nthSunday(y, 5, 2) }, "dia dos pais" to { y -> nthSunday(y, 8, 2) }, "dia do professor" to { y -> LocalDate.of(y, 10, 15) },
        "dia das criancas" to { y -> LocalDate.of(y, 10, 12) }, "sexta feira santa" to { y -> BrHolidays.easter(y).minusDays(2) },
    )

    private fun nthSunday(y: Int, month: Int, n: Int): LocalDate {
        var d = LocalDate.of(y, month, 1)
        while (d.dayOfWeek != java.time.DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d.plusWeeks((n - 1).toLong())
    }

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "time.now", "Relógio", "diz a hora de agora",
            templates = listOf(
                "(que|qual) (e a|a)? (hora|horas) (e|sao|eh)? (agora|ai|mesmo|certa)?",
                "(me )?(diz|diga|fala|fale|informa|informe|mostra|mostre) (a|as|que) (hora|horas) (sao|e)?",
                "(tem|sabe|sabia) (as )?(hora|horas)",
                "(hora|horas) (agora|atual|certa)?",
            ),
        ) { m ->
            val n = m.ctx.now
            val h = n.hour
            val mi = n.minute
            val text = when {
                h == 12 && mi == 0 -> "É meio-dia."
                h == 0 && mi == 0 -> "É meia-noite."
                h == 1 -> "É 1h" + if (mi == 0) "." else "%02d.".format(mi)
                else -> "São ${h}h" + if (mi == 0) "." else "%02d.".format(mi)
            }
            Kit.say(m, text)
        },
        IntentDef(
            "time.date", "Relógio", "diz a data de hoje, o dia da semana ou do mês",
            slots = listOf(SlotSpec("date", "o dia (AAAA-MM-DD); vazio = hoje")),
            templates = listOf(
                "(que|qual) dia (da semana|do mes)? (e|eh|foi|sera|cai)? <D?>",
                "(que|qual) (e a )?data (de|do)? <D?>",
                "<D> (e|eh|sera|foi) (que|qual) dia (da semana)?",
                "(em que|que) dia (estamos|a gente esta|estou|nos estamos)",
                "<D> cai em (que|qual) dia (da semana)?",
                "(que|qual) dia (da semana)? (cai|vai cair|vai ser|sera) <D>",
                "(me )?(diz|diga|fala|fale) (a )?(data|dia) (de )?<D?>",
                "dia do mes",
            ),
        ) { m ->
            val d = m.date?.start ?: m.today
            val sk = m.ex.skeleton
            val text = when {
                "semana" in sk -> "${Speak.date(d, m.today).replaceFirstChar { it.uppercase() }} ${if (d == m.today) "é" else if (d.isBefore(m.today)) "foi" else "será"} ${Speak.weekday(d)}."
                "do mes" in sk || sk == "dia do mes" -> "Hoje é dia ${m.today.dayOfMonth} de ${Speak.month(m.today)}."
                d == m.today -> "Hoje é ${fullDate(d)}."
                else -> "${Speak.date(d, m.today).replaceFirstChar { it.uppercase() }} ${if (d.isBefore(m.today)) "foi" else "é"} ${fullDate(d)}."
            }
            Kit.say(m, text)
        },
        IntentDef(
            "time.holiday", "Relógio", "diz se é feriado nacional (hoje, amanhã, um dia, a semana, o mês) e qual é o próximo",
            slots = listOf(SlotSpec("date", "o dia (AAAA-MM-DD); vazio = hoje")),
            templates = listOf(
                "... (feriado|feriados) ...",
                "(quando|qual) (e|sera|tem|vai ter) (o )?proximo feriado",
            ),
        ) { m ->
            val sk = m.ex.skeleton
            val today = m.today
            if ("proximo" in sk || "quando" in sk) {
                val (d, name) = BrHolidays.next(today.plusDays(if (m.date == null) 0 else 0))
                val diff = ChronoUnit.DAYS.between(today, d)
                Kit.say(m, "O próximo feriado nacional é $name, ${Speak.date(d, today).let { if (diff in 0..2) it else fullDate(d) }}" + if (diff > 1) " (daqui a $diff dias)." else ".")
            } else {
                val slot = m.date
                if (slot == null || slot.days == 1) {
                    val d = slot?.start ?: today
                    val h = BrHolidays.nameOn(d)
                    val opt = BrHolidays.optionalOn(d)
                    val whenText = Speak.date(d, today)
                    val text = when {
                        h != null -> "Sim, ${whenText.let { if (it == "hoje") "hoje" else it }} é feriado nacional: $h."
                        opt != null -> "${whenText.replaceFirstChar { it.uppercase() }} é ponto facultativo ($opt), mas não é feriado nacional."
                        else -> "${whenText.replaceFirstChar { it.uppercase() }} não é feriado nacional. Feriados estaduais e municipais eu não sei."
                    }
                    Kit.say(m, text)
                } else {
                    val list = BrHolidays.between(slot.start, slot.start.plusDays(slot.days.toLong()))
                    val label = slot.spoken.ifBlank { "nesse período" }
                    Kit.say(
                        m,
                        if (list.isEmpty()) "Não há feriado nacional $label."
                        else "Feriados nacionais $label: " + Speak.list(list.map { "${it.second} (${Speak.weekday(it.first)}, dia ${it.first.dayOfMonth})" }) + ".",
                    )
                }
            }
        },
        IntentDef(
            "time.countdown", "Relógio", "diz quantos dias faltam para uma data ou feriado",
            slots = listOf(SlotSpec("date", "a data (AAAA-MM-DD)", true)),
            templates = listOf(
                "(quantos|quanto) (dias|tempo) (faltam|falta|resta|restam) (para|pra|pro|ate) <D>",
                "(faltam|falta) quantos dias (para|pra|pro|ate) <D>",
                "(quantos|quanto) (dias|tempo) (faltam|falta) (para|pra|pro|ate) (o|a|as|os)? {evento}",
                "(faltam|falta) quantos dias (para|pra|pro|ate) (o|a|as|os)? {evento}",
                "quanto tempo (falta|faltam) (para|pra|pro) <D>",
            ),
        ) { m ->
            val today = m.today
            var target: LocalDate? = m.date?.start
            if (target == null) {
                val ev = m["evento"]?.let { PtText.fold(it).replace(Regex("[^a-z ]"), "").trim() } ?: return@IntentDef NoMatch
                val key = named.keys.firstOrNull { ev == it || ev.startsWith(it) || it.startsWith(ev) } ?: return@IntentDef NoMatch
                var d = named.getValue(key)(today.year)
                if (d.isBefore(today)) d = named.getValue(key)(today.year + 1)
                target = d
            }
            val n = ChronoUnit.DAYS.between(today, target)
            Kit.say(
                m,
                when {
                    n == 0L -> "É hoje!"
                    n == 1L -> "Falta 1 dia: é amanhã, ${fullDate(target)}."
                    n > 1 -> "Faltam $n dias para ${fullDate(target)}."
                    else -> "Esse dia já passou: foi há ${-n} ${if (n == -1L) "dia" else "dias"}, em ${fullDate(target)}."
                },
            )
        },
        IntentDef(
            "calc", "Calculadora", "faz contas (soma, subtração, vezes, dividido, por cento, raiz, potência)",
            slots = listOf(SlotSpec("text", "a conta, como foi dita", true)),
            templates = listOf(
                "(quanto|qual) (e|da|fica|vale|seria|da isso|e que) {conta}",
                "(calcula|calcule|calcular|conta|faz a conta de|faca a conta de|resultado de|resolve|resolva) {conta}",
                "(me )?(diz|diga|fala|fale) (quanto e|o resultado de) {conta}",
                "{conta}",
            ),
            priority = -6,
        ) { m ->
            val expr = PtText.fold(m["conta"] ?: m["text"] ?: return@IntentDef NoMatch).replace(Regex("""[?!]"""), "").trim()
            val r = Calc.solve(expr) ?: return@IntentDef NoMatch
            Kit.say(m, "Dá $r.")
        },
    )
}
