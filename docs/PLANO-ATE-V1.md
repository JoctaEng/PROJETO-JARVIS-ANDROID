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

## v0.27 — Biblioteca de skills (pedido 65c)
- Dezenas de habilidades prontas, acionadas por comando curto, cada uma com passos, ferramentas e confirmações. Exemplos de partida:
  - **Professor**: preparar aula, plano de aula, lista de exercícios, gabarito, corrigir questão por foto, chamada/lembrete de turma, prova da semana, resumo de conteúdo.
  - **EduMath**: abrir o EduMath em uma tela/tópico, gerar exercício, conferir resolução (ponte MCP).
  - **Agenda**: marcar compromisso, "o que tenho hoje/amanhã", remarcar, lembrar 1 h antes, achar horário livre.
  - **Dia a dia**: modo aula (silencioso + agenda + EduMath), modo foco, modo dirigir, modo dormir, lista de compras, anotação rápida, despesas do dia.
  - **Comunicação**: mandar mensagem a contato, ler mensagens não lidas, resumo de grupos.
  - **Informação**: clima, notícias, resumo de link/PDF, tradução, cálculo/conversão de unidades.
- Tela "Minhas skills": ligar/desligar, editar o comando e **ensinar uma nova** ("quando eu disser X, faça Y e Z"). Este é o pedido antigo de rotinas ensinadas.
- A lista final das skills é montada **com o usuário**, pelos gostos e atividades dele.

## v0.28 — Tarefas programadas (pedido 65d)
- Rotinas que rodam **sem ser chamadas**, como as do Claude:
  - por horário: todo dia, dias úteis, uma vez;
  - por condição: chegou notificação de X, bateria baixa, carregando, Wi-Fi de casa, início/fim de compromisso.
- Agendador do Android (WorkManager/AlarmManager). Ao terminar, avisa por notificação ou fala, conforme a escolha.
- Ações de risco (enviar, pagar, apagar) **nunca rodam sozinhas**: pedem confirmação. Há histórico de execuções e botão "parar todas".

## v0.29 — Fase 3 "Ele cresce comigo"
- Memória por sentido (embeddings).
- Backup e restauração no Drive.
- EduMath e outros conectores (MCP) testados no aparelho e adicionados sem recompilar.
- Pacotes de skills para importar e exportar.

## v0.30 — Fase 4 "Organize meu dia"
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
