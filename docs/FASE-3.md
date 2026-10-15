# Fase 3 — Autonomia e Ferramentas (v0.30 a v0.40)

## v0.30.0 — Memória por Sentido (EmbeddingGemma 2) — 12/10/2026

### 1. Resumo da entrega
- Integração do modelo **EmbeddingGemma 2** (Google DeepMind, Apache 2.0) via LiteRT-LM (`litertlm-android:0.17.1`).
- Módulo puro Kotlin/JVM `:mind:embed` com cálculos vetoriais, normalização L2, truncamento Matryoshka para 256 dimensões, persistência em JSON (`embeddings.json`) e fallback léxico.
- Recuperação semântica (`MemoryRecall`):
  - **Desligada por padrão** (`semanticMemory = false`), mantendo comportamento idêntico à v0.29.1.
  - Quando ativada pelo usuário: busca os top-8 itens mais relevantes semanticamente para o prompt do cérebro (com limite de ~900 caracteres).
- Download em primeiro plano (`EmbeddingDownload` e `EmbeddingDownloadService`, canal `euno_embedding`, ID 4208) com retomada e conferência de integridade SHA-256.
- Interface em Ajustes > "Minha Memória": chave liga/desliga, botão Baixar (~157 MB), Conferir arquivo (SHA-256), Apagar modelo, e indicação de memórias indexadas.
- Diagnóstico (`ReportCollector`): métricas de memória por sentido (status, modelo presente, contagem do índice, tempo de carga e tempo médio de consulta).

### 2. Dados Oficiais do Modelo (Tarefa 0)
- **ID Hugging Face:** `litert-community/embeddinggemma-2-text-270m-litert-lm`
- **Arquivo:** `embeddinggemma-2-text-270m.litertlm`
- **Tamanho:** 164.626.432 bytes (~157 MB)
- **SHA-256:** `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb`
- **Prefixos Oficiais de Tarefa (Search):**
  - Consulta: `task: search result | query: {query}`
  - Documento: `title: none | text: {content}`
- **Dimensões:** 256d (Matryoshka, L2 normalizado).
- **Licença:** Apache 2.0.

## 0.31.0 — "agente que faz, não que diz que faz" (pedido 84, 14/10/2026)

**O que o teste de 13–14/10 mostrou** [DADO, conversa e relatório no Drive]: agenda lida "igual robô" (títulos inteiros com códigos e
números de processo); "entendi, pode apagar futebol" recusado (o motor não tirava "entendi" e o Groq não recebia `agenda_apagar`);
"me acorde às 7h30 com o resumo" não trouxe o resumo (a IA recriou a tarefa em vez de executá-la); "estou preparando o resumo"
várias vezes sem nenhuma ferramenta; 3 ANRs no `TaskAlarmReceiver` (receptor preso até a IA responder).

**O que mudou**
- Motor (`mind/intent`): mais palavras de abertura ("entendi", "agora", "certo", "você vai fazer o seguinte"…); "lembre que" com
  dia/hora vira compromisso; tradução da IA só vale para AÇÃO (resposta pronta, como a lista de recursos, fica com a IA);
  `Replies.agenda` fala o total, agrupa o "dia todo" e diz os horários; `Speak.title` encurta títulos; `Reply.detail` (lista
  completa só na tela) e `Reply.facts` (dados para a IA reescrever). Novos: `day.briefing` (agenda + clima + e-mails),
  `weather.now`, `sms.send` (pergunta antes), `gmail.info` (pergunta "de hoje, resumo ou antigos?"), `gmail.period`,
  `task.morning` ("me acorde … com o resumo" → tarefa que toca e fala). `Briefing` também é usado pelas tarefas.
- Voz (`mind/persona/SpokenProse`): datas, horas, intervalos, traços, etiquetas e códigos longos falados como gente.
- Conversa: a IA online reescreve as respostas do motor em até 8 s, sem inventar (se falhar, fica o texto do motor; o celular
  não entra); o prompt recebe o **retrato sincronizado**; honestidade corrige "estou preparando/já ajustei" sem ferramenta até
  2 vezes e depois responde com franqueza que não fez.
- Android: `sync/EunoSync.kt` + `SyncReceiver` (a cada ~30 min, alarme inexato; agenda, clima e Gmail; pede sincronização das
  agendas Google); ferramentas `clima` e `sms_enviar` (`tools/LifeTools.kt`, `system/resources/WeatherReport.kt`);
  `agenda_salvar` diz se gravou na do Google; tarefas em `TaskRunService` (dataSync), som de despertar e voz.
- Ajustes: Meu Euno → Memória → "Sincronização contínua", "Sincronizar agora", "Respostas naturais".

**Limites honestos**: Gmail só funciona depois de conectado pelo usuário; WhatsApp continua abrindo a conversa e tocando em
Enviar pela acessibilidade (não existe envio oficial sem abrir); parâmetros do Open-Meteo conferidos de memória, não na página
(bloqueada aqui) [HIPÓTESE até o teste]; SMS e localização pedem permissão na primeira vez.

## 0.32.0 — conversa limpa, prompt enxuto, relatórios da versão atual (pedido 86, 15/10/2026)

**Por que o prompt estava grande** [DADO, relatório 14/10]: cada pedido mandava ~46 mil caracteres (70 ferramentas com
esquemas, instruções, catálogo de skills, memórias, caminhos). O histórico da conversa é limitado (20 mensagens), então não é
ele o problema. Em uma resposta com ferramentas, o mesmo prompt é reenviado a cada rodada (até 10). O Groq grátis aceita
~8 mil tokens por minuto: 413 (75 vezes) e 429 (254). Quando o provedor recusa, cai para outro cérebro e a resposta demora.

**O que mudou**
- Prompt enxuto para todos os provedores, menos o Gemini: no máximo 12 ferramentas escolhidas pelo pedido.
- Nova conversa: sessão nova, fila, pergunta pendente, cérebros fracos e tarefa programada zerados. Caminhos aprendidos
  ficam fora do primeiro turno. Com "começar com resumo", entra só um resumo de até 600 caracteres.
- Relatórios: ao abrir uma versão nova, `audit.jsonl`, `diagnostics/` e `conversas/` vão para `arquivo/<versão anterior>/`
  (`system/resources/VersionArchive.kt`, com testes). O relatório deixou de mostrar "versões anteriores".

**Pendências conhecidas**: Gemini sem cota (pesquisa na internet continua falhando até haver outra fonte de busca);
controle da tela caindo ("NÃO conectado"); clima sem cidade; memória por sentido ainda sem medição.

