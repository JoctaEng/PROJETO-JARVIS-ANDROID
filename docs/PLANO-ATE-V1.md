# Plano até a v1 comercial (proposta de 10/10/2026, após o teste da 0.24.0)

> **Status: só planejado. Nada aqui começa sem ordem do usuário.** A ordem pode mudar a pedido dele.
> Origem: pedidos 39–65 (`docs/PEDIDOS-DO-USUARIO.md`), ROTEIRO §12 (fases 2–4) e o teste da 0.24.0 (`docs/TESTE-V0.14.0.md`).

## v0.25 — "Age direito" (correções do teste da 0.24 + jeito de agir) — **FEITA na 0.25.0 (aguarda teste)**
**Avatar (pedido 65a)**
- Com o 3D ligado, **nunca mostrar o 2D**: portal de entrada, "tchau" e troca de personagem passam a usar a animação do próprio 3D (ou um fade), sem o "boneco da boca lascada" ao fundo.
- Cabeçalho do chat, tela inicial e sessão de toque mostram uma **miniatura do 3D escolhido**, não o 2D.
- **Guardião 3D** (v0.24.1, sessão local; ADR 0013) como mais um avatar, com busto fechado no rosto (pedido 66).
- Ao trocar de avatar, ele muda em **todos os lugares** (tela, chat, tela inicial, sessão de toque) (pedido 66).

**Agente (pedido 65b)**
- **Se minimizar antes de agir**: ao usar qualquer `tela_*`, ir para o modo agindo (canto, legenda mínima) e só então ler ou tocar.
- **Esperar o app carregar**: depois de `abrir_app` ou de um toque que muda de tela, esperar a tela ficar estável (eventos da acessibilidade + prazo máximo), em vez de ler 80 ms depois.
- **Rolagem humana e persistente**: gesto de ~0,4 s com pausa entre rolagens, nas 4 direções; continua até achar o alvo ou detectar o fim da lista.
- **Visão da tela por print** (`takeScreenshot` da acessibilidade, Android 11+) enviada ao Gemini multimodal junto com a árvore da tela. Assim ele entende o espaço e toca no **item clicável** certo, não no texto (caso "15 de outubro").
  - Atenção: essa permissão nova faz o Android desligar o serviço uma vez, e o aviso da tela inicial já cobre isso.
- **Tentativas em silêncio**: as tentativas intermediárias não são faladas nem mostradas, e no fim ele diz só o resultado ("Pronto, marquei para dia 15"). Os detalhes ficam no relatório.
- **Conferir as próprias capacidades**: regra e ferramenta "o que eu sei fazer" (lista viva das ferramentas). Antes de dizer "não consigo", ele confere essa lista e tenta.
- **Fluidez**: prompt menor (mandar só as ferramentas prováveis para o pedido), o que reduz tempo e o 429 do Groq. Se o Groq voltar vazio em turno com ferramentas, ele passa direto ao próximo cérebro sem gastar outra tentativa.
- Guia "manter o Euno vivo no HyperOS" (bateria sem restrições, início automático, travar nos recentes): o OneKeyClean encerrou o app 4×.

## v0.26 — "Ele vê e acompanha seu dia" (Etapa C)
- Câmera: "o que é isto?" (Gemini multimodal). Tela: "o que tem na tela?" (reaproveita o print da 0.25).
- Notificações: "o que chegou?", com resumo. Ele nunca responde sozinho.
- "Bom dia" e "Fechar o dia" automáticos em horário escolhido.

## v0.27 — Biblioteca de skills (pedido 65c) — 1ª leva entregue na 0.27.0 (27 skills + ensinar + Minhas skills)
- Dezenas de habilidades prontas, acionadas por comando curto, cada uma com passos, ferramentas e confirmações. Exemplos de partida:
  - **Professor**: preparar aula, plano de aula, lista de exercícios, gabarito, corrigir questão por foto, chamada/lembrete de turma, prova da semana, resumo de conteúdo.
  - **EduMath**: abrir o EduMath em uma tela/tópico, gerar exercício, conferir resolução (ponte MCP).
  - **Agenda**: marcar compromisso, "o que tenho hoje/amanhã", remarcar, lembrar 1 h antes, achar horário livre.
  - **Dia a dia**: modo aula (silencioso + agenda + EduMath), modo foco, modo dirigir, modo dormir, lista de compras, anotação rápida, despesas do dia.
  - **Comunicação**: mandar mensagem a contato, ler mensagens não lidas, resumo de grupos.
  - **Informação**: clima, notícias, resumo de link/PDF, tradução, cálculo/conversão de unidades.
- Tela "Minhas skills": ligar/desligar, editar o comando e **ensinar uma nova** ("quando eu disser X, faça Y e Z"). Este é o pedido antigo de rotinas ensinadas.
- A lista final das skills é montada **com o usuário**, pelos gostos e atividades dele.

## v0.28 — Tarefas programadas (pedido 65d) — por horário entregue na 0.28.0; faltam as por condição
- Rotinas que rodam **sem ser chamadas**, como as do Claude:
  - por horário: todo dia, dias úteis, uma vez;
  - por condição: chegou notificação de X, bateria baixa, carregando, Wi-Fi de casa, início/fim de compromisso.
- Agendador do Android (WorkManager/AlarmManager). Ao terminar, avisa por notificação ou fala, conforme a escolha.
- Ações de risco (enviar, pagar, apagar) **nunca rodam sozinhas**: pedem confirmação. Há histórico de execuções e botão "parar todas".

## v0.29 — "Euno primeiro, IA depois" (pedido 81) — ENTREGUE na 0.29.0 (aguarda teste); faltam tarefas por condição e visão local (restos da 0.28)
- **Motor próprio de pedidos** na frente do cérebro: reconhece o pedido (frases + sinônimos, data/hora/nome/app), extrai os dados e chama a ferramenta direto; responde com frases prontas. Sem IA, sem gasto, igual online e offline.
- **IA só como tradutora**: se o motor não entende, um prompt curto (sem a lista de ferramentas) pede à IA um comando normalizado (intenção + dados); o Euno executa. Se ainda assim não der, diz o que entendeu e o que faltou (nada de "todos os cérebros falharam").
- **Catálogo por app**, nesta ordem de prioridade: Google Agenda, agenda local (Xiaomi), Gmail, Joca Finanças, EduMath, WhatsApp, Instagram; depois os demais mais usados. Modelos de frase × verbos × dados geram centenas/milhares de variações, **cada uma coberta por teste**.
- **Conversa continua com a IA**: papo, dúvidas e raciocínio seguem fluidos (o motor só assume o que é comando claro). O caminho atual com o Gemini fica intacto como reserva.
- **Medir**: o relatório mostra quantos pedidos foram resolvidos pelo motor, quantos pela IA-tradutora e quantos pelo caminho antigo, e o tempo de cada um.
- Meta de qualidade: pedidos de ferramenta atendidos rápido, com certeza; vale para a v1 comercial.

## v0.30 — Fase 3 "Ele cresce comigo" (v0.30.0 ENTREGUE, pedido 82)
- Memória por sentido (embeddings) — **EmbeddingGemma 2 integrado na v0.30.0**: módulo `:mind:embed` puro JVM, `EmbeddingEngine` via LiteRT-LM 0.17.1 em CPU, 256d com L2, download próprio (~157 MB), prefixos oficiais, desligado por padrão, conferência SHA-256 e top-8 memórias no prompt (~900 chars).
- Backup e restauração no Drive.
- EduMath e outros conectores (MCP) testados no aparelho e adicionados sem recompilar.
- Pacotes de skills para importar e exportar.

## v0.31 — Fase 4 "Organize meu dia" (era v0.30)
- Planejar o dia ponta a ponta: agenda, tarefas, mensagens e lembretes, com revisão do usuário antes de executar.
- Rotinas que se ajustam ao uso.

## v0.9x → v1.0 comercial
- **Estabilidade**: aparelhos de outras marcas (Samsung/Motorola), sem quedas, consumo de bateria e memória medido, relatório de falhas.
- **Primeiro uso** sem ajuda: assistente que configura voz, cérebro e permissões. Opção sem chave própria, com servidor do Euno pago por assinatura, que exige backend e cobrança.
- **Loja e lei**:
  - Google Play restringe o uso do serviço de acessibilidade. É preciso declaração, vídeo e justificativa, com risco de recusa, e a alternativa é distribuir fora da Play.
  - Política de privacidade e LGPD.
  - Termos de uso.
  - Licenças dos avatares: os VRoid CC0 podem ser usados; o exemplo da pixiv tem licença própria e precisa ser conferido para uso comercial.
- **Marca**: nome/registro "Euno", ícone, loja, site e preço.
- **Personagem (bônus, por último)**: avatares próprios do usuário em 3D com boca e expressões (sessão local ou serviço como Avaturn/Rodin + rig).

## Projeto — Vozes do personagem (pedido 85, registrado em 15/10/2026, não iniciado)
- **Objetivo:** cada personagem com uma voz própria. O Guardião precisa de voz imponente, grave e encorpada, sem soar infantil.
- **Escopo previsto:**
  - Ajuste de **tom** (grave/agudo), **timbre** (equalização: graves, médios, brilho), **velocidade** e **dramaticidade** (reverberação leve e pausas), aplicado no próprio app sobre o áudio de qualquer voz (Piper, Kokoro, Azure, Gemini ou Android).
  - Controles deslizantes em Meu Euno, por personagem, com botão **Ouvir amostra** para testar antes de salvar.
  - Predefinições (ex.: "Guardião grave", "neutro") que o usuário pode editar.
- **Pré-requisito:** o usuário escolhe a voz base após ouvir as amostras (Gemini TTS: Algieba, Charon, Fenrir, Orus; Azure: Antonio, Fabio, Donato; Piper: faber, cadu, jeff, a confirmar).
- **Fontes conferidas:** vozes e gêneros apenas parcialmente confirmados (ver a conversa de 15/10). O timbre "grave" de cada voz não foi verificado por áudio.
- **Pendente antes de começar:** decisão do usuário sobre a voz base; teste de qualidade no celular; checagem de cota se a voz base for online.

