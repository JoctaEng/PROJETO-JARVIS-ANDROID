package com.joctaeng.jarvis.mind.orchestrator

/**
 * Reserva automática do Gemini (pedido 78): no plano grátis, cada MODELO tem a sua cota diária. Quando o modelo principal
 * bate o limite, outro modelo de texto do Gemini, com a mesma chave, responde com o mesmo nível (visão e ferramentas).
 * Escolhe pela lista real de modelos da conta (GET /models), sem chutar nomes.
 */
object BrainReserve {
    private val excluded = listOf("tts", "image", "audio", "live", "embedding", "embed", "aqa", "imagen", "veo", "learnlm", "gemma", "robotics", "computer-use", "native")

    private fun version(model: String): Double =
        Regex("""gemini-(\d+(?:\.\d+)?)""").find(model)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /** Melhor modelo de reserva diferente do [primary], ou null. Prefere "flash" (não lite), depois a versão mais nova, e estável a preview. */
    fun pickGemini(models: List<String>, primary: String): String? {
        val candidates = models.map { it.removePrefix("models/") }
            .filter { it.startsWith("gemini-") && it != primary.removePrefix("models/") }
            .filter { m -> excluded.none { it in m } }
            .filter { "flash" in it || "pro" in it }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareByDescending<String> { "flash" in it && "lite" !in it }
                .thenByDescending { version(it) }
                .thenBy { if ("preview" in it || "exp" in it) 1 else 0 }
                .thenBy { if ("latest" in it) 0 else 1 }
                .thenBy { it.length },
        ).first()
    }
}

/**
 * Ferramentas que vão para um cérebro com pouca cota por minuto (Groq grátis: ~8 mil tokens/min, pedido 78): em vez das
 * ~56 ferramentas (~8–10 mil tokens), só as básicas + as que combinam com o pedido. Puro, para testar.
 */
object ToolSelector {
    val core = listOf(
        "abrir_app", "tela_ler", "tela_tocar", "tela_navegar", "tela_rolar", "tela_digitar",
        "agenda_consultar", "agenda_salvar", "pesquisar_e_resumir", "memoria_guardar",
    )

    private val byTopic: List<Pair<List<String>, List<String>>> = listOf(
        listOf("agenda", "compromisso", "reuniao", "marca", "marcar", "remarca", "dia ", "amanha", "hoje", "semana") to
            listOf("agenda_consultar", "agenda_salvar", "agenda_editar", "agenda_apagar", "agenda_abrir", "resumo_do_dia"),
        listOf("whats", "mensagem", "manda", "envia", "zap") to listOf("whatsapp_mensagem", "contatos_buscar"),
        listOf("contato", "telefone", "numero") to listOf("contatos_buscar", "contato_criar"),
        listOf("email", "e mail", "gmail") to listOf("gmail_buscar", "gmail_ler"),
        listOf("notifica", "chegou", "novidade") to listOf("notificacoes_ler"),
        listOf("arquivo", "salva", "lista", "nota", "anota", "documento") to listOf("arquivo_salvar", "arquivo_ler", "arquivo_listar"),
        listOf("pesquisa", "procura", "noticia", "clima", "tempo", "preco", "link", "site", "pagina") to
            listOf("pesquisar_e_resumir", "ler_pagina", "abrir_link", "pesquisar_na_web"),
        listOf("tela", "ve ", "veja", "olha", "imagem", "foto", "print", "toca", "clica", "botao") to
            listOf("tela_ver", "tela_tocar_ponto", "tela_deslizar", "tela_pesquisar"),
        listOf("alarme", "desperta", "timer", "cronometro", "lembra") to listOf("criar_alarme", "criar_timer"),
        listOf("lanterna", "bateria", "celular") to listOf("lanterna", "estado_do_celular"),
        listOf("lembra", "memoria", "esquece") to listOf("memoria_buscar", "memoria_esquecer"),
        listOf("aula", "foco", "dormir", "silencio", "perturbe") to listOf("nao_perturbe", "criar_timer"),
        listOf("skill", "habilidade", "rotina", "aprenda") to listOf("skill_listar", "skill_ensinar", "skill_apagar"),
        listOf("saldo", "gastei", "fatura", "dinheiro", "financ", "lanca") to listOf("joca_financas_"),
        listOf("tarefa", "programa", "todo dia", "todos os dias", "agendar rotina") to listOf("tarefa_"),
    )

    /**
     * Para o cérebro do celular (janela de 4.096 tokens, teste da 0.29.0 estourou com 69 ferramentas): primeiro as do
     * assunto do pedido, depois as básicas, no máximo [max]. Sem isso o prompt chegava a 38 mil caracteres.
     */
    fun forLocal(available: List<String>, request: String, extraText: String = "", max: Int = 8): List<String> {
        val topics = select(available, request, extraText, max = available.size).filter { it !in core }
        return (topics + core.filter { it in available }).distinct().take(max)
    }

    /** Nomes escolhidos entre [available], na ordem: básicas, as do assunto, as citadas pela skill ativa ([extraText]). */
    fun select(available: List<String>, request: String, extraText: String = "", max: Int = 18): List<String> {
        val said = " " + java.text.Normalizer.normalize(request.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ") + " "
        val wanted = LinkedHashSet<String>()
        core.filter { it in available }.forEach { wanted += it }
        byTopic.filter { (words, _) -> words.any { it in said } }.forEach { (_, tools) ->
            tools.forEach { t -> if (t.endsWith("_")) available.filter { it.startsWith(t) }.forEach { wanted += it } else if (t in available) wanted += t }
        }
        available.filter { it in extraText }.forEach { wanted += it }
        return wanted.take(max)
    }
}
