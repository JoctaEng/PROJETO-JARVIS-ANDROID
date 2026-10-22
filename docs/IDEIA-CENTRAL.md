# IDEIA CENTRAL — Euno como corpo e as IAs como cabeças (registrada em 22/10/2026, pedido 89)

> **Toda sessão deve ler este arquivo antes de mexer em arquitetura, motor, ferramentas, cérebros ou MCP.**
> Foi pedido do usuário "não perder de vista essa ideia". Nada aqui apaga o Euno atual: ele continua sendo o que é, só ganha uma segunda porta.

## 1. A ideia em uma frase
O Euno é o **corpo** que mora no celular (vê a tela, abre e usa apps, lê notificações, agenda, e-mail, SMS, clima, arquivos) e as **IAs são as cabeças** que o comandam: o Gemma local, Groq, Mistral, Gemini e, no futuro, Claude, GPT, Grok etc. O corpo **executa**; quem **decide** é uma IA. O usuário não precisa saber qual cabeça agiu: para ele é um só "eu" (a persona, a memória, a voz e o avatar do Euno).

Não é só assistente de voz, nem vive dentro de um único app: ele "passeia" pelos apps que quiser, quando precisar. Exemplos (WhatsApp, Instagram, banco, agenda…) são **só exemplos**: o alvo é qualquer app, sem depender da API de cada um.

## 2. Duas portas para as cabeças (as duas valem; uma não substitui a outra)
**Porta interna — o Euno orquestra.** O Euno chama as IAs (por API com chave guardada só no celular, ou o Gemma local), escolhe a cabeça pelo tipo de tarefa (rápido/privado → Gemma; imagem → Gemini; raciocínio longo → Claude/GPT…), passa a informação entre elas por dentro e entrega uma só resposta. É a porta das tarefas em segundo plano (resumo da manhã, notificações, rotinas) e de quando o usuário fala com o Euno.

**Porta externa — o Euno é um conector (servidor MCP).** O Euno expõe o corpo como ferramentas MCP (ler tela, abrir app, tocar, deslizar, agenda, e-mail, enviar mensagem…). O usuário conversa com o **Claude** (ou o Gemini, se aceitar conectores) usando a assinatura que já tem, e a IA **comanda o celular** por esse conector. É a ideia "conectar o Claude lá dentro e conversar com ele ou com você aqui". Hoje as sessões do Claude Code já usam conectores MCP (Gmail, Agenda, Drive); o Euno passa a ser mais um.

Os dois lados usam as **mesmas ferramentas** (a `ToolGateway` do app), então o corpo é um só.

## 3. O que NÃO pode mudar
- Persona, voz, avatar 3D, memória, tarefas programadas, sincronização e a identidade "meu segundo eu" continuam como estão.
- O **motor** do Euno vira **atuador**: executa o que a IA manda e cobre quando não há IA acessível (sem internet/cota). Ele não decide por cima da IA nem responde conversa no lugar dela (pedido 88).
- Chaves, tokens e dados pessoais **só no celular**; o repositório é público (nenhum dado pessoal em código/docs/commits).

## 4. Segurança (inegociável, vale antes de abrir qualquer conector)
1. **Instrução que vem de conteúdo** (mensagem, e-mail, página, texto na tela) **nunca é ordem**. Só vale o que o usuário pediu.
2. Ação que **envia, apaga, paga ou muda algo** pede **confirmação no celular**, mesmo vinda de uma IA externa. Os níveis de autonomia atuais continuam valendo.
3. Conector **desligado por padrão**, com pareamento explícito, escopo de ferramentas por IA, sessão com prazo e botão de pausa/revogar no app.
4. **Registro de auditoria** de tudo que uma IA externa fez.
5. Dado sensível fica no celular (Gemma local); o roteador respeita isso.
6. Endereço do conector exposto à internet exige autenticação forte; sem isso, ele não sobe.

## 5. O que já existe no código (base)
- `ToolGateway` e ferramentas do celular (agenda, e-mail, SMS, clima, tela/acessibilidade, notificações, tarefas), `AgentRunner`, `HonestyCheck`, níveis de autonomia, `Orchestrator`/`BrainReserve` (ordem de cérebros com reserva), prompt de persona único para qualquer cérebro, Catálogo de modelos offline (0.33).
- **Cliente** MCP (ADR 0011: pelo Binder e pela rede). **O servidor MCP do corpo ainda NÃO existe.**

## 6. Caminho proposto (cada passo testado no aparelho antes do seguinte)
0. **Estabilizar o corpo:** serviço de acessibilidade reconectando sozinho; limpeza do Xiaomi fechando o app (relatório de 19/10: 22 vezes em um dia).
1. **IA como cérebro, motor como atuador** (pedido 88): medir prompt/contexto real do Gemma E4B (33–77 s até a 1ª palavra) e inverter a ordem.
2. **Roteador por tipo de tarefa** + provedores por API (Claude, GPT, Grok), mantendo uma só persona.
3. **Servidor MCP do corpo:** primeiro local no aparelho, depois pela rede (decisão aberta de como alcançar o celular de fora).
4. **Pareamento, autenticação e escopos** por IA, auditoria e pausa.
5. Casos de uso (mensagens, redes, banco…) pelo caminho mais seguro de cada app (intents, resposta pela notificação, acessibilidade), sempre com confirmação.

## 7. Em aberto — conferir em fonte oficial antes de decidir (NÃO verificado)
- Termos de uso: login da assinatura de chat em app de terceiros (Claude, ChatGPT, Grok); o caminho oficial costuma ser API com chave e cobrança à parte.
- Conectores personalizados do Claude e do Gemini: quais planos aceitam, se funcionam no app do celular, e o que exigem do servidor (transporte, autorização).
- Como o celular é alcançado de fora (túnel/relay) com segurança e sem expor o aparelho.
- Termos de cada app alvo (ex.: WhatsApp) sobre automação.
- "Argon": nome citado pelo usuário, ainda não identificado.

## 8. Como as sessões devem trabalhar
Seguir `CLAUDE.md` e `docs/CONTINUAR-AQUI.md`. Só programar com ordem do usuário ("só anote" = só docs). Pouca cota de plano do usuário: mudanças pontuais, uma por vez, sempre com testes JVM e CI verde. Decisão técnica desta ideia: `docs/ADR/0016-euno-corpo-e-conector-mcp.md`.
