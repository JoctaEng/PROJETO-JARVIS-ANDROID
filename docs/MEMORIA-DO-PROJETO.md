# MEMÓRIA DO PROJETO — Euno ("Seu segundo eu digital")

> **Leia este arquivo primeiro, sempre**, antes de `HANDOFF.md`. Ele guarda o que o usuário pediu, o que já foi feito, o que mudou do plano original e como trabalhar com ele. **Atualize-o a cada fase e a cada pedido novo** (uma linha na seção 8 basta). Não contém dados pessoais: o repositório é público.

## 1. A ideia, em uma frase
Um **personagem digital pessoal que vive no celular** (não "um chatbot com um boneco"): flutua sobre qualquer app, ouve, fala, lembra, usa cérebro online ou local, e age no celular e nos apps do usuário, sempre com permissão e sem mentir sobre o que fez. Manifesto original: *"Não é uma IA dentro do celular. É um personagem que vive dentro dele."*

Origem: conversa do usuário com outro assistente (ChatGPT) que gerou a especificação de 66 itens e a lista de nomes (Soul Myself, SecondMe, Euno…). O plano técnico derivado está em `docs/ROTEIRO.md`. O nome provisório "JARVIS" foi trocado por **Euno** (ADR 0008); o pacote interno continua `com.joctaeng.jarvis`.

## 2. Princípios do plano original que continuam valendo
1. **Personagem desacoplado do cérebro** (personagem + personalidade + memória + modelo + ferramentas são peças trocáveis). Chat → Orquestrador → Provedor, nunca Chat → Gemini direto.
2. **Offline é expansão, não dependência**: núcleo local; internet amplia.
3. **Não mentir sobre ações**: se não executou, não diz que executou; se falhou, diz; se faltou permissão, diz.
4. **Permissões e autonomia em níveis**; toda ação passa pelo `ToolGateway` (confirmação, histórico em `audit.jsonl`); ações de risco (enviar, pagar, apagar) pedem confirmação.
5. **Minha Memória** visível e editável; "Esquecer isto"; **Modo Privado**; log de atividades.
6. **Câmera/microfone sob demanda** (câmera nunca fica ligada sozinha).
7. **Arquitetura modular e escalável**: novos modelos, conectores, vozes, habilidades sem reescrever o núcleo.
8. **Primeiro a arquitetura, depois a interface bonita**; entregar em MVPs.

## 3. Como trabalhar com o usuário (valem sempre)
- Português do Brasil, **mensagens curtas, diretas e honestas**. Dizer o que foi verificado e o que não foi. **CI verde não é "funciona no aparelho"**: só o usuário valida no celular.
- **Analítico e conferir as fontes**; nunca inventar conceito, valor ou causa. Marcar [DADO] × [HIPÓTESE] quando a causa não está confirmada.
- **Só começar o que ele mandar começar.** Quando ele diz "não comece nada", só registrar. Antes de uma etapa, **dizer o que vai fazer e o que vem depois**.
- **Registrar cada fase e cada pedido novo** nos arquivos (seção 9) na hora; ele cobra isso.
- **Economizar os limites dele (dia e semana)**: não refazer o que está feito, não escrever análises que ninguém pediu, preferir mudanças pequenas e testadas. Agentes/IAs mais baratas só quando o custo de passar o contexto compensa (até agora nenhum agente foi usado).
- **Nunca exigir desinstalar o app**: a assinatura do APK tem de ficar idêntica (`tools/assinatura/impressao_esperada.txt`; segredo `EUNO_SIGNING_SEED` só no GitHub).
- **Repositório público: nunca commitar** chaves, senhas, tokens, nome completo, nome de familiares, telefones, empregador ou qualquer dado pessoal do usuário — **nem em testes, exemplos, docs ou mensagens de commit**. Use nomes e números fictícios (ex.: "Maria Souza", (91) 91234-5678). *(Falha já ocorrida em 09/10/2026: nome da esposa, telefone e empregador entraram em docs/testes; foram removidos, mas continuam no histórico do git até alguém reescrevê-lo.)*
- **"O que o usuário vê"**: entregar sempre link do APK e um roteiro curto de teste; avisar quando o CI terminar (agendar conferência).
- Quando o usuário disser que uma ferramenta/ambiente não alcança algo (YouTube, ChatGPT, vídeo grande), dizer o limite e pedir o formato que eu leio (texto colado, prints, documento no Drive).
- Modelo de IA: ele escolhe qual modelo usar na sessão; não colocar identificador de modelo em commits, docs ou código.

## 4. Mapa de fases e estado (atualizar)
| Fase (ROTEIRO §12) | Meta | Estado em 09/10/2026 |
|---|---|---|
| **0** Fundação/PoCs | overlay, voz, LLM local, câmera, fps | **Feita** e validada no aparelho |
| **1** MVP 1 "Ele mora no meu celular" | personagem flutuante, chat, voz, memória, cérebro online+local, configurações, personalidade, ferramentas | **Feita** (v0.2–v0.6.x); arte 3D só de poucos personagens |
| **2** MVP 2 "Ele me conhece e me ajuda de verdade" | agenda, contatos, skills, contexto, notificações, e-mail, automações | **Em andamento** (v0.7.0 → v0.13.1), ver §5 |
| **3** MVP 3 "Ele cresce comigo" | MCP, múltiplos modelos, pacotes, biblioteca de personagens, sincronização, aprender habilidades | **Parcial adiantado**: MCP e download de modelos já existem; falta o resto |
| **4** Avançada | "organize meu dia" ponta a ponta | Não iniciada |

## 5. Fase 2 — o que entrou em cada versão
Histórico completo e atualizado em `docs/FASE-2.md`. Resumo:
0.7 agenda, contatos, "tchau"/"parar de ouvir" · 0.8 fila de mensagens, paciência, "pera aí" · 0.9 Nova conversa + Resumos, seção Conversa, cérebro local compacto · 0.10 modo legenda + "Oi Joca" · 0.11 detector de erros completo, Teste completo, toque longo → fechar por completo · 0.12 cérebro local só se escolhido, voz do Gemini mais econômica, WhatsApp/compromisso/bom dia–fechar o dia · 0.13 controle por acessibilidade + memória completa (**0.13.0 não instalou no aparelho do usuário; 0.13.1 é o teste sem o serviço**).

## 6. Decisões que mudaram em relação ao plano original (ajustes nossos)
- **Nome**: JARVIS → **Euno** (marca de terceiros).
- **Cérebro local**: llama.cpp + Qwen3-4B (ADR 0009) em vez de MediaPipe. **Rebaixado a opcional**: muito lento no aparelho; só entra se o usuário escolher "local primeiro/somente" ou sem internet. Cérebro padrão: **online primeiro** (Gemini via API do usuário).
- **Voz**: Gemini TTS (natural) → Kokoro offline (3–8× mais lento que o tempo real neste aparelho, desligado no Automático) → voz do Android. Conta Google no **Tier 1: 100 pedidos/dia no modelo de voz** (conferir limite real em ai.dev/rate-limit; decisão de subir de camada é do usuário).
- **Sem janela de chat**: modo legenda (balão) em vez de abrir o chat inteiro.
- **Acessibilidade**: o plano dizia "só quando realmente necessário"; foi implementada para controlar a tela (ler, tocar, digitar, rolar, navegar), com senhas nunca lidas e confirmação em ações de risco.
- **Habilidades**: "fechar o dia" virou a ferramenta `resumo_do_dia`; **ainda falta o usuário ensinar rotinas próprias** (item 28 do plano).
- **Relatório de erros**: virou o detector completo (resumo de todos os erros, escuta passo a passo, logcat).
- **Mic/escuta**: um reconhecedor por vez (conversa > comandos enquanto fala > "Oi Joca"); continuação reaproveita o reconhecedor.
- **Bip do microfone**: só silenciável com acesso a "Não perturbe" (o usuário concordou em dar).
- **Aparelho de referência**: Redmi Note 13 Pro+ 5G (12 GB, HyperOS/Android 16). O HyperOS (OneKeyClean) encerra o app: exige bateria "Sem restrições", início automático e app travado nos recentes (ação do usuário).

## 7. O que ainda falta (ordem prevista)
1. **Resolver a instalação da v0.13.x** (isolar o serviço de acessibilidade) e então devolver o controle do celular.
2. **Etapa C**: visão ("o que é isto?" com câmera/tela); reconhecer a voz do usuário no "Oi Joca" (pesquisa, pode não valer a pena); "Bom dia" e "Fechar o dia" automáticos em horário escolhido; e-mail e notificações; rotinas ensinadas pelo usuário.
3. Pendências conhecidas: voz natural do Gemini depende da cota diária; arte dos personagens restantes; chave de assinatura fixa já ativa (conferida no CI); EduMath (ponte MCP) ainda sem teste no aparelho.
4. **Ideias do usuário ainda não implementadas**: cadastro/continuação de pedidos futuros vão na seção 8.

## 8. Pedidos do usuário (diário curto; acrescente no fim)
Lista completa e com situação: `docs/PEDIDOS-DO-USUARIO.md`. Diário de sessões: `docs/HANDOFF.md` §11.
- 09/10/2026: pediu que esta memória em .md exista para nunca esquecer fases e ajustes; colou a conversa original (conceito + lista de nomes) para ser lembrada.

## 9. Onde está cada coisa
- Regras curtas: `CLAUDE.md`, `AGENTS.md` · Contexto técnico: `docs/HANDOFF.md` · Plano mestre: `docs/ROTEIRO.md` · Decisões: `docs/ADR/` (0001–0011)
- Pedidos: `docs/PEDIDOS-DO-USUARIO.md` · Histórico por versão: `docs/FASE-2.md` · Testes e relatórios: `docs/TESTE-V0.10.0.md`, `docs/TESTE-V0.11.0.md`, `docs/RELATORIO-DE-ERROS.md`
- Versão/fase do app: `gradle.properties` (`euno.phase`, `euno.version`). APK: aba Actions → artefato `Euno-fase<N>-v<versão>-build<run>.apk`.
- Testes de lógica: `./gradlew -Pjarvis.jvmOnly=true test` (o app Android só compila no CI).
