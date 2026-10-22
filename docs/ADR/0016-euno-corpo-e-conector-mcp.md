# ADR 0016 — Euno como corpo (atuador) das IAs, com porta interna e porta externa MCP

**Status:** proposta aceita pelo usuário como direção (22/10/2026, pedido 89); **não implementada** · Detalhes e segurança: `docs/IDEIA-CENTRAL.md`.

## Contexto
O usuário quer que o Euno more no celular e use qualquer app sem depender de API de cada app, comandado por várias IAs (Gemma, Groq, Mistral, Gemini, Claude, GPT, Grok…), com o motor só como atuador. Relatório de 19/10: o motor decide antes da IA (12% dos pedidos sozinho, 16% só traduzindo), Gemini no teto mensal, Groq com limite de 8 mil tokens/min, Gemma 33–77 s até a 1ª palavra.

## Decisão
1. **Corpo único** (`ToolGateway` + atuadores) servindo duas portas.
2. **Porta interna:** orquestrador do Euno com roteador por tipo de tarefa e persona única; IA decide, motor atua (e cobre sem IA).
3. **Porta externa:** servidor MCP do Euno que expõe as mesmas ferramentas a IAs externas (Claude, e Gemini se aceitar conectores), com pareamento, escopo, confirmação no celular e auditoria.
4. Login da assinatura de chat em app de terceiros **não** é caminho assumido (termos não verificados); o conector MCP é.

## Consequências
- Mantém a essência do Euno atual (persona, voz, avatar, memória, tarefas).
- Exige estabilizar acessibilidade/limpeza do Xiaomi antes (IA nenhuma comanda corpo instável).
- Abrir conector na internet é risco real: só com autenticação forte, escopos e confirmação; injeção de instruções por conteúdo nunca vale como ordem.
- Decisões em aberto listadas em `docs/IDEIA-CENTRAL.md` §7.
