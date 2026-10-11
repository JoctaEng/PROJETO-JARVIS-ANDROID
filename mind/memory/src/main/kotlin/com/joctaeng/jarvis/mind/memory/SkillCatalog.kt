package com.joctaeng.jarvis.mind.memory

/**
 * Skills prontas da v0.27 (docs/PLANO-ATE-V1.md). Primeira leva, montada pelas atividades do usuário (professor, EduMath,
 * agenda cheia, Joca Finanças); ele liga/desliga e ensina novas em Meu Euno → Minhas skills.
 * Os passos citam só ferramentas que existem; se uma não estiver disponível no momento, o cérebro pula ou avisa.
 */
object SkillCatalog {
    private fun s(id: String, name: String, category: String, triggers: List<String>, steps: String) =
        Skill(id, name, category, triggers, steps, builtIn = true)

    val builtIn: List<Skill> = listOf(
        // ---------------- Professor
        s(
            "aula-preparar", "Preparar aula", "Professor",
            listOf("preparar aula", "preparar uma aula", "prepara uma aula", "prepara a aula", "montar aula", "montar uma aula"),
            "1) Se não foi dito, pergunte o tema, a turma/série e a duração. 2) pesquisar_e_resumir sobre o tema (fontes confiáveis; " +
                "nunca invente conceito nem valor). 3) Monte: objetivos, conteúdo em tópicos, exemplos resolvidos, 3 a 5 exercícios de " +
                "fixação e fechamento. 4) arquivo_salvar com nome \"Aula - <tema> - <data>\" e formato md. 5) Diga onde salvou e resuma em 3 linhas.",
        ),
        s(
            "aula-plano", "Plano de aula", "Professor",
            listOf("plano de aula", "fazer um plano de aula", "faz um plano de aula"),
            "1) Pergunte o que faltar: tema, série, duração, número de aulas. 2) pesquisar_e_resumir para conferir o conteúdo e, se for " +
                "pedir BNCC, a habilidade (cite o código só se a pesquisa confirmar). 3) Estrutura: identificação, objetivos, habilidades, " +
                "conteúdo, metodologia passo a passo, recursos, avaliação, referências. 4) arquivo_salvar \"Plano de aula - <tema>\" (md). 5) Diga onde salvou.",
        ),
        s(
            "aula-lista", "Lista de exercícios", "Professor",
            listOf("lista de exercicios", "lista de questoes", "fazer uma lista de exercicios", "gera uma lista de exercicios"),
            "1) Pergunte tema, série, quantidade e nível, se faltar. 2) Escreva as questões numeradas, do mais fácil ao mais difícil. " +
                "3) Resolva cada uma você mesmo e confira as contas antes de escrever o gabarito. 4) arquivo_salvar \"Lista - <tema>\" (md) " +
                "com as questões e, separado no fim, o gabarito. 5) Diga onde salvou.",
        ),
        s(
            "aula-corrigir", "Corrigir questão da tela", "Professor",
            listOf("corrigir questao", "corrige essa questao", "corrigir essa questao", "resolve essa questao", "resolver essa questao"),
            "1) tela_ver para ler a questão que está na tela (se não houver, peça uma foto ou o texto). 2) Resolva passo a passo e confira " +
                "o resultado por outro caminho. 3) Se houver uma resposta do aluno na tela, diga se está certa e onde errou. 4) Não invente dados que não aparecem.",
        ),
        s(
            "aula-resumo", "Resumo de conteúdo", "Professor",
            listOf("resumo de conteudo", "resumir o conteudo", "resume o conteudo", "resumo do conteudo"),
            "1) Se o conteúdo for um link, ler_pagina; se estiver na tela, tela_ler; se for um tema, pesquisar_e_resumir. 2) Resumo em " +
                "tópicos curtos com os conceitos-chave e um exemplo. 3) Se pedirem para guardar, arquivo_salvar \"Resumo - <tema>\" (md).",
        ),
        s(
            "aula-provas", "Provas da semana", "Professor",
            listOf("provas da semana", "prova da semana", "quais provas tenho", "minhas provas"),
            "1) agenda_consultar com dias=7. 2) Separe os compromissos com prova, avaliação, simulado ou entrega de notas. 3) Diga dia, horário e turma de cada um.",
        ),
        // ---------------- EduMath
        s(
            "edumath-exercicio", "Exercício no EduMath", "EduMath",
            listOf("exercicio no edumath", "abrir o edumath em", "edumath exercicio", "gera um exercicio no edumath"),
            "1) Se houver ferramentas edumath_* na lista, use-as (gerar exercício, abrir tópico, conferir resolução). " +
                "2) Se não houver, abrir_app \"EduMath\" e, com tela_ler/tela_tocar, vá ao tópico pedido.",
        ),
        // ---------------- Agenda
        s(
            "agenda-hoje", "Como está meu dia", "Agenda",
            listOf("como esta meu dia", "o que tenho hoje", "minha agenda de hoje", "compromissos de hoje"),
            "1) agenda_consultar periodo=hoje. 2) Diga em ordem de horário, primeiro o que ainda vai acontecer. 3) Avise conflitos de horário.",
        ),
        s(
            "agenda-amanha", "Agenda de amanhã", "Agenda",
            listOf("o que tenho amanha", "minha agenda de amanha", "compromissos de amanha", "como esta meu dia amanha"),
            "1) agenda_consultar periodo=amanha. 2) Diga em ordem de horário e qual é o primeiro compromisso (para a hora de acordar).",
        ),
        s(
            "agenda-remarcar", "Remarcar compromisso", "Agenda",
            listOf("remarcar", "remarca", "mudar o horario do", "adiar o"),
            "1) agenda_consultar no período do compromisso para achar o id certo. 2) Se houver mais de um parecido, pergunte qual. " +
                "3) agenda_editar só com o que muda. 4) Confirme o novo dia e hora pelo resultado.",
        ),
        s(
            "agenda-livre", "Achar horário livre", "Agenda",
            listOf("horario livre", "horarios livres", "quando estou livre", "tenho tempo livre"),
            "1) agenda_consultar no dia ou semana pedidos. 2) Entre 7h e 22h, liste os intervalos sem compromisso de 30 min ou mais. " +
                "3) Se pedirem para marcar algo num deles, agenda_salvar.",
        ),
        s(
            "agenda-lembrar-antes", "Lembrar antes", "Agenda",
            listOf("me lembra uma hora antes", "me avisa antes", "lembrar antes do", "me lembre antes"),
            "1) agenda_consultar para achar o horário do compromisso. 2) criar_alarme no horário menos o tempo pedido (padrão 1 hora), " +
                "com rótulo igual ao compromisso. 3) Diga a hora do alarme.",
        ),
        // ---------------- Dia a dia
        s(
            "modo-aula", "Modo aula", "Dia a dia",
            listOf("modo aula", "vou dar aula", "entrar em aula", "entrando em sala"),
            "1) nao_perturbe ligar=true. 2) agenda_consultar periodo=hoje e diga qual é a próxima aula/compromisso e o horário. " +
                "3) Diga que ficou em silêncio e que é só pedir \"fim da aula\" para voltar.",
        ),
        s(
            "modo-aula-fim", "Fim da aula", "Dia a dia",
            listOf("fim da aula", "sair do modo aula", "acabou a aula", "terminei a aula"),
            "1) nao_perturbe ligar=false. 2) notificacoes_ler e resuma o que chegou durante a aula, agrupado por app, sem ler mensagens privadas inteiras. 3) Diga o próximo compromisso do dia.",
        ),
        s(
            "modo-foco", "Modo foco", "Dia a dia",
            listOf("modo foco", "preciso me concentrar", "modo concentracao"),
            "1) nao_perturbe ligar=true. 2) criar_timer com o tempo pedido (padrão 25 minutos) e rótulo \"Foco\". 3) Diga até que horas vai o foco.",
        ),
        s(
            "modo-dormir", "Modo dormir", "Dia a dia",
            listOf("modo dormir", "vou dormir", "hora de dormir", "boa noite euno"),
            "1) agenda_consultar periodo=amanha e diga o primeiro compromisso. 2) Pergunte se quer alarme e a hora (sugira 1 h antes do primeiro compromisso); " +
                "só com a resposta, criar_alarme. 3) nao_perturbe ligar=true.",
        ),
        s(
            "lista-compras", "Lista de compras", "Dia a dia",
            listOf("lista de compras", "coloca na lista de compras", "adiciona na lista de compras", "o que falta comprar"),
            "Para ACRESCENTAR itens: arquivo_salvar nome=\"Lista de compras\" formato=md modo=acrescentar, um item por linha com \"- \". " +
                "Para VER a lista: arquivo_ler \"Lista de compras\". Para LIMPAR: arquivo_salvar modo=substituir com \"(vazia)\" só se o usuário confirmar.",
        ),
        s(
            "nota-rapida", "Anotação rápida", "Dia a dia",
            listOf("anotacao rapida", "nota rapida", "anota rapidinho"),
            "1) arquivo_salvar nome=\"Notas rápidas\" formato=md modo=acrescentar com a data/hora e o texto. 2) Confirme em uma frase. " +
                "(Compromisso com data/hora vai para a agenda, não para notas.)",
        ),
        // ---------------- Comunicação
        s(
            "notificacoes", "O que chegou", "Comunicação",
            listOf("o que chegou", "ler notificacoes", "minhas notificacoes", "tem mensagem nova", "alguma novidade no celular"),
            "1) notificacoes_ler (quantas=20). 2) Agrupe por app e diga quantas de cada; destaque banco, trabalho e família. 3) Leia o texto completo só se pedirem.",
        ),
        s(
            "emails-importantes", "E-mails importantes", "Comunicação",
            listOf("emails importantes", "e mails importantes", "chegou algum email", "chegou algum e mail", "meus emails", "meus e mails"),
            "1) Se gmail_buscar existir: gmail_buscar consulta=\"is:unread newer_than:2d\" quantos=10. Se o Gmail não estiver conectado, diga como conectar. " +
                "2) Separe o importante (bancos, concursos, trabalho, prazos) das promoções. 3) Abra um com gmail_ler só se pedirem.",
        ),
        // ---------------- Informação
        s(
            "noticias", "Notícias do dia", "Informação",
            listOf("noticias de hoje", "principais noticias", "me conta as noticias", "resumo das noticias"),
            "1) pesquisar_e_resumir \"principais notícias de hoje no Brasil\" (ou do tema pedido). 2) 5 itens curtos, cada um com a fonte. 3) Nada de opinião.",
        ),
        s(
            "clima", "Clima", "Informação",
            listOf("previsao do tempo", "como esta o tempo", "vai chover", "clima de hoje", "clima amanha"),
            "1) Cidade: a pedida; senão memoria_buscar \"cidade\"; senão pergunte. 2) pesquisar_e_resumir \"previsão do tempo <dia> em <cidade>\". " +
                "3) Diga mínima, máxima e chance de chuva, com a fonte.",
        ),
        s(
            "resumir-link", "Resumir link", "Informação",
            listOf("resume esse link", "resumir esse link", "resume essa pagina", "resumir essa pagina"),
            "1) Pegue o link do pedido ou da tela (tela_ler). 2) ler_pagina. 3) Resumo em tópicos com o essencial e, se for notícia, a data.",
        ),
        // ---------------- Finanças (Joca Finanças)
        s(
            "financas-resumo", "Meu dinheiro", "Finanças",
            listOf("quanto tenho de saldo", "meu saldo", "quanto gastei", "como estao minhas financas", "resumo financeiro"),
            "1) Se existir joca_financas_resumo, use-o (e joca_financas_lancamentos para detalhar gastos). Se não existir, diga que o Joca Finanças " +
                "precisa estar instalado e com o acesso ao Euno ligado. 2) Valores só do resultado, em reais.",
        ),
        s(
            "financas-documento", "Conferir fatura/documento", "Finanças",
            listOf("conferir fatura", "confere essa fatura", "lancar essa fatura", "conferir documento", "lancar esse extrato"),
            "1) Leia os itens do documento (tela_ver/tela_ler, arquivo_ler ou gmail_ler). 2) joca_financas_conferir_documento com TODOS os itens. " +
                "3) Diga quantos são novos, quais já estavam cadastrados e quais são possíveis duplicados. 4) Pergunte e lance só os novos com joca_financas_lancar.",
        ),
        // ---------------- Fechar o dia
        s(
            "fechar-dia", "Fechar o dia", "Dia a dia",
            listOf("fechar o dia", "fecha o dia", "resumo do dia", "como foi meu dia"),
            "1) resumo_do_dia tipo=noite. 2) agenda_consultar periodo=amanha e diga o primeiro compromisso. 3) Pergunte se quer anotar algo pendente " +
                "(agenda_salvar se tiver data/hora; senão arquivo_salvar \"Notas rápidas\" modo=acrescentar).",
        ),
    )
}
