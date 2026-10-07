# ADR 0011 — Ferramentas e MCP no celular (Euno controla o Android e os apps do usuário)

**Status:** aceita (Fase 1, antecipa o "cliente MCP" da Fase 3) · **Data:** 2026-10-07

## Contexto
O usuário quer que o personagem **aja** no celular e controle os próprios apps (EduMath primeiro), **também offline**
e com a IA do celular (Qwen3-4B), não só com o Gemini. O roteiro previa o cliente MCP na Fase 3; o pedido o antecipa.

## Decisões
1. **Chamada de ferramentas por texto** (`<tool_call>{"name","arguments"}</tool_call>`, formato que o Qwen3 aprendeu),
   igual para todos os cérebros. Um caminho só, testável; o pedido some do texto e da voz (`ToolCallFilter`).
   Laço: gera → executa → `<tool_response>` → gera de novo (máx. 4 rodadas, `AgentRunner`).
2. **Toda ação passa pelo Tool Gateway** já existente: Policy Engine (autonomia × risco), confirmação na tela e
   Histórico de ações em arquivo. Padrão: **Operador** (consulta sozinho; pede confirmação para alterar). Crítico
   sempre confirma.
3. **Ferramentas nativas da Fase 1** (roteiro 9.4): abrir/listar apps, estado do celular, alarme, timer, lanterna,
   compartilhar, link, pesquisa, mapa.
4. **MCP no próprio celular pelo Binder**: apps exportam um serviço com a ação `com.joctaeng.euno.action.MCP_SERVER`
   (interface `IEunoMcpServer.aidl`), por onde passam as mensagens JSON-RPC do MCP (initialize, tools/list,
   tools/call). Funciona offline, sem portas abertas (porta localhost seria acessível a qualquer app). O Euno
   descobre esses apps sozinho; o app verifica que quem chama é o pacote do Euno.
5. **MCP pela rede** (Streamable HTTP) para servidores no PC/Tailscale, cadastrados em Meu Euno.
6. **EduMath** (branch `claude/euno-mcp` do repositório edumath-codigomaker): reaproveita as ferramentas do Mathie
   (motor oficial de notas) e acrescenta `perguntar` e `lancar_nota` com as regras do EduMath (nunca AVA/SAF; nota
   existente só com pedido expresso; fila offline). Com o app fechado o serviço responde -32001 e o Euno o abre.

## Consequências
- A IA do celular lê a lista de ferramentas no prompt; por isso a seção fica antes da hora (cache de prefixo).
- Robôs do AVA/SAF e outras rotas só do PC continuam no PC; pela rede, via MCP HTTP, quando houver servidor.
- O EduMath precisa ser recompilado pelo professor (Windows) para ganhar a porta.
