package com.joctaeng.jarvis.mind.intent

/** EduMath (porta MCP `edumath`): turmas, perguntas ao Mathie, lançar nota (com confirmação) e sincronizar. */
internal object CatalogEdu {
    private const val ASK = "edumath_perguntar"
    private const val NOTE = "edumath_lancar_nota"

    private fun speakable(text: String, max: Int = 700): String {
        val t = ToolText.plain(text).replace(Regex("""[*_#`]+"""), "").replace(Regex("""\n{2,}"""), "\n").trim()
        return if (t.length <= max) t else t.take(max).substringBeforeLast(' ') + "… (o resto está no EduMath)"
    }

    private val fieldWords = Regex("""(?i)\b(p\s?\d|prova\s?\d?|av\s?\d|np\s?\d|ac\s?\d|a\d|seminario|seminário|trabalho|atividade|exercicio|exercício|participacao|participação|reposicao|reposição|recuperacao|recuperação)\b""")
    private val skip = setOf("lanca", "lance", "lancar", "registra", "registre", "registrar", "coloca", "coloque", "poe", "ponha", "bota", "anota", "anote", "da", "de", "do", "dar", "nota", "notas",
        "na", "no", "para", "pra", "o", "a", "aluno", "aluna", "como", "igual", "valor", "um", "uma", "edumath", "edu", "math", "dele", "dela", "turma", "uma", "ao", "em")

    val defs: List<IntentDef> = listOf(
        IntentDef(
            "edu.classes", "EduMath", "lista as turmas do professor",
            templates = listOf(
                "(quais|lista|liste|listar|mostra|mostre|me diz|diga|ver|veja|fala|fale) (sao )?(as |minhas |todas as )?(minhas )?(turmas|classes)( do edumath| no edumath)?",
                "(minhas |as )turmas( do edumath)?",
                "(quantas|que) turmas (eu )?(tenho|dou|leciono)",
            ),
            priority = 2,
        ) { m ->
            if (!m.has("edumath_listar_turmas")) return@IntentDef Kit.noTool(m, "o EduMath")
            Kit.run(m, Step("edumath_listar_turmas")) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply("Suas turmas no EduMath: " + speakable(r[0].text, 500), "happy")
            }
        },
        IntentDef(
            "edu.sync_state", "EduMath", "diz se o EduMath está sincronizado",
            templates = listOf(
                "(o )?(edumath|edu math) (esta |ta )?(sincronizado|sincronizando|atualizado|conectado|online)",
                "(tem|tenho|ha) (alteracoes|coisas|mudancas|notas|dados) (pendentes|para sincronizar|pra sincronizar|sem sincronizar) (no|do) (edumath|edu math)",
                "(como esta|qual e|qual o|ver|veja) (a |o )?(sincronizacao|estado da sincronizacao|estado) (do|no) (edumath|edu math)",
            ),
            priority = 3,
        ) { m ->
            if (!m.has("edumath_estado_sincronizacao")) return@IntentDef Kit.noTool(m, "o EduMath")
            Kit.run(m, Step("edumath_estado_sincronizacao")) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(speakable(r[0].text, 400), "neutral")
            }
        },
        IntentDef(
            "edu.sync", "EduMath", "envia ao servidor as alterações feitas no celular",
            templates = listOf(
                "(sincroniza|sincronize|sincronizar|atualiza|atualize|envia|envie|manda|mande|sobe|suba) (o |as |tudo do |tudo no )?(edumath|edu math|alteracoes do edumath|notas do edumath)( agora| para o servidor| pro servidor)?",
                "(sincroniza|sincronize|sincronizar) (agora|tudo)",
            ),
            priority = 3,
        ) { m ->
            if (!m.has("edumath_sincronizar_agora")) return@IntentDef Kit.noTool(m, "o EduMath")
            Kit.run(m, Step("edumath_sincronizar_agora")) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(speakable(r[0].text, 300).ifBlank { "Sincronização enviada." }, "happy")
            }
        },
        IntentDef(
            "edu.grade", "EduMath", "lança uma nota de um aluno no EduMath (confirma antes)",
            slots = listOf(SlotSpec("aluno", "nome do aluno", true), SlotSpec("campo", "avaliação, ex.: P1", true), SlotSpec("valor", "nota, ex.: 8,5", true)),
            templates = listOf(
                "(lanca|lance|lancar|registra|registre|registrar|coloca|coloque|poe|ponha|bota|anota|anote|da|de|dar) (a |uma )?nota ...",
                "(lanca|lance|registra|registre|coloca|coloque|poe|ponha|anota|anote) (no edumath )?(a |o )?(p1|p2|p3|p4|p5|prova|seminario|trabalho|atividade|av1|av2|av3|np1|np2|ac1|ac2) ...",
            ),
            priority = 3,
        ) { m ->
            if (!m.has(NOTE)) return@IntentDef Kit.noTool(m, "o EduMath")
            val text = m.ex.raw
            val value = Regex("""(?<![\w])(\d{1,2}(?:[.,]\d{1,2})?)(?![\w:/])""").findAll(text).lastOrNull()?.groupValues?.get(1) ?: return@IntentDef NoMatch
            val field = fieldWords.find(text)?.value?.replace(" ", "")?.uppercase()?.let { if (it.length <= 3) it else Kit.cap(it.lowercase()) } ?: return@IntentDef NoMatch
            val student = text.replace(value, " ").replace(fieldWords, " ").split(Regex("\\s+")).filter { it.isNotBlank() && PtText.fold(it) !in skip }.joinToString(" ").trim()
            if (student.length < 2) return@IntentDef NoMatch
            val num = value.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return@IntentDef NoMatch
            val name = Kit.cap(student)
            Kit.run(
                m, Step(NOTE, mapOf("aluno" to student, "campo" to field, "valor" to num)),
                confirm = "Lançar ${value.replace('.', ',')} na $field do aluno $name, no EduMath? Diga sim para confirmar.",
            ) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(speakable(r[0].text, 300).ifBlank { "Nota lançada: $name, $field, ${value.replace('.', ',')}." }, "happy")
            }
        },
        IntentDef(
            "edu.ask", "EduMath", "pergunta ao Mathie (calendário, aulas, reposições, alunos, notas, financeiro)",
            slots = listOf(SlotSpec("pergunta", "a pergunta, nas palavras do usuário", true)),
            templates = listOf(
                "(pergunta|pergunte|consulta|consulte|ve|veja|me diz|diga|checa|confere|confira|olha|olhe|procura|procure) (ao |pro |para o |no |pelo |ao |para a |pra |na |do )?(edumath|edu math|mathie|matie|mathi) {pergunta}",
                "(no|do|pelo|segundo o) (edumath|edu math|mathie) {pergunta}",
                "(edumath|edu math|mathie|matie) {pergunta}",
                "{pergunta} (no|do|pelo) (edumath|edu math|mathie)",
            ),
            priority = 2,
        ) { m ->
            if (!m.has(ASK)) return@IntentDef Kit.noTool(m, "o EduMath")
            val q = m["pergunta"]?.trim() ?: return@IntentDef NoMatch
            Kit.run(m, Step(ASK, mapOf("pergunta" to q))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(speakable(r[0].text), "neutral")
            }
        },
        IntentDef(
            "edu.ask_topic", "EduMath", "pergunta sobre turmas, alunos, notas, reposições ou seminários (vai ao Mathie)",
            slots = listOf(SlotSpec("pergunta", "a pergunta", true)),
            templates = listOf(
                "(quando|quais|quantos|quantas|qual|quem|como|onde|que|tem|tenho|ha) ... (turma|turmas|alunos|aluno|aluna|alunas|reposicao|reposicoes|seminario|seminarios|prova|provas|matriz|matrizes|chamada|frequencia|faltas|diario) ...",
                "(turma|turmas|alunos|reposicao|reposicoes|seminario|seminarios|provas) ...",
            ),
            priority = -1,
        ) { m ->
            if (!m.has(ASK)) return@IntentDef NoMatch
            val q = Kit.bareText(m.ex).takeIf { it.length >= 6 } ?: return@IntentDef NoMatch
            // "prova"/"aula" também existem na agenda do celular: com data, a agenda responde melhor.
            if (m.date != null && m.has("agenda_consultar")) return@IntentDef NoMatch
            Kit.run(m, Step(ASK, mapOf("pergunta" to q))) { r ->
                Kit.firstFailure(r)?.let { return@run Kit.failure(it) }
                Reply(speakable(r[0].text), "neutral")
            }
        },
    )
}
