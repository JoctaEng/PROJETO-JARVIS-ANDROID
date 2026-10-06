# PROJETO JARVIS — Roteiro de Criação

**Personal AI Character para Android — "Seu segundo eu digital"**
Versão 1.0 · Documento de roteiro técnico e de produto · Dispositivo de referência: Redmi Note 13 Pro+ (12 GB RAM / 512 GB)

> Este roteiro parte da especificação conceitual (66 itens) já produzida e faz três coisas que ela ainda não fazia:
> 1. **confronta cada requisito com as limitações reais do Android** (o que é possível, o que exige permissão especial e o que não é possível);
> 2. **escolhe tecnologias concretas**, com justificativa e fonte;
> 3. **ordena o trabalho em fases com critérios de saída mensuráveis**, para que cada etapa possa ser entregue a um agente de desenvolvimento (Codex, Antigravity, AI Studio, Claude Code) sem ambiguidade.
>
> Convenção usada no documento:
> - ✅ **Confirmado** — verificado em documentação oficial ou fonte confiável (listadas na seção 14).
> - ⚠️ **Validar na Fase 0** — plausível, mas depende do aparelho/ROM (HyperOS) e precisa de prova de conceito.
> - ❌ **Não viável como descrito** — o Android não permite; há alternativa indicada.

---

## Sumário

0. [Visão em uma página](#0-visão-em-uma-página)
1. [Análise crítica da especificação anterior](#1-análise-crítica-da-especificação-anterior)
2. [Realidade do Android: o que pode e o que não pode](#2-realidade-do-android-o-que-pode-e-o-que-não-pode)
3. [Orçamento de hardware (8 GB e 12 GB)](#3-orçamento-de-hardware-8-gb-e-12-gb)
4. [Decisões de tecnologia (stack)](#4-decisões-de-tecnologia-stack)
5. [Arquitetura](#5-arquitetura)
6. [O personagem: criação visual e animação](#6-o-personagem-criação-visual-e-animação)
7. [Cérebro: local, API própria e nuvem](#7-cérebro-local-api-própria-e-nuvem)
8. [Memória](#8-memória)
9. [Conectores, MCP e Skills](#9-conectores-mcp-e-skills)
10. [Segurança, permissões e autonomia](#10-segurança-permissões-e-autonomia)
11. [Sistema de pacotes (estilo jogo)](#11-sistema-de-pacotes-estilo-jogo)
12. [Roteiro por fases](#12-roteiro-por-fases)
13. [Como trabalhar com agentes de desenvolvimento](#13-como-trabalhar-com-agentes-de-desenvolvimento)
14. [Fontes verificadas](#14-fontes-verificadas)
15. [Riscos e decisões em aberto](#15-riscos-e-decisões-em-aberto)

---

## 0. Visão em uma página

**O que é:** um personagem 3D fofo (uma caricatura original do Joctã, estética de animação cinematográfica) que **flutua sobre qualquer app** do celular, **acorda ao toque**, conversa por **voz (modo live)** ou por **balão de chat**, **enxerga pela câmera sob demanda**, **lembra** de você, e **age** no celular através de conectores autorizados.

**O que não é:** um chatbot com um boneco. O princípio de design permanece: *um personagem digital que possui uma IA* — o cérebro é trocável, o personagem é permanente.

**Três decisões que estruturam tudo:**

| # | Decisão | Por quê |
|---|---------|---------|
| 1 | **App pessoal, distribuído por sideload (APK próprio), não pela Play Store — pelo menos até o MVP 3** | As políticas da Play restringem justamente o que torna o JARVIS especial (AccessibilityService para automação, por exemplo). Para uso pessoal, instalar o próprio APK elimina esse bloqueio. ✅ |
| 2 | **Personagem, cérebro, memória, ferramentas e personalidade são módulos separados com interfaces** | Permite trocar o modelo de IA (local → API própria → API externa) sem tocar no personagem. |
| 3 | **Um único modelo pesado carregado por vez, com carregamento sob demanda** | É isso que permite rodar liso em 8 GB. Nunca STT + LLM + visão grandes simultâneos na RAM. |

---

## 1. Análise crítica da especificação anterior

A especificação gerada anteriormente é **boa como visão de produto** e deve ser mantida como documento de requisitos. Os pontos abaixo precisam de ajuste antes de virar código:

| Item da especificação | Situação | Ajuste |
|---|---|---|
| "Personagem percebe que está sobre um botão e se move" (item 3) | ⚠️ | Um overlay comum **não enxerga** o conteúdo dos outros apps. Para saber onde estão botões é preciso **AccessibilityService** (lê a árvore de elementos da tela) ou **captura de tela** (MediaProjection, que pede consentimento a cada sessão). No MVP: heurísticas (app em primeiro plano + posição preferida aprendida por app + bordas). Consciência espacial real: MVP 2, via AccessibilityService. |
| "Tocar ativa câmera e microfone" (itens 4, 20, 21) | ⚠️ | No Android 14+, um serviço em primeiro plano que usa câmera/microfone **não pode ser iniciado com o app em segundo plano** ✅. Solução de projeto: o toque abre uma **Activity transparente/compacta** (o app passa a estar em primeiro plano legitimamente) que inicia a sessão de câmera/microfone. Precisa de prova de conceito no HyperOS. |
| "Contexto do dispositivo: qual app está aberto" (item 44) | ✅ com permissão especial | `PACKAGE_USAGE_STATS` (acesso a uso) ou AccessibilityService. Ambos são ativados manualmente pelo usuário nas configurações. |
| "Ler notificações" | ✅ com permissão especial | `NotificationListenerService`, ativado manualmente. |
| "Operar aplicativos" | ✅ só via AccessibilityService | Pela política da Play, automação geral via acessibilidade **não é permitida** para apps publicados ✅. Em APK pessoal é tecnicamente possível. Reforça a Decisão 1. |
| Tamanhos de pacote (item 40: "base 200–500 MB") | Ajustar | O APK base pode e deve ser **bem menor (meta: < 80 MB)**. Modelos, vozes e personagens 3D vêm depois, como pacotes. |
| "MediaPipe / LiteRT / llama.cpp / ONNX" (item 10) | Atualizar | O caminho atual do Google para LLM no aparelho é o **LiteRT-LM** ✅, com modelos Gemma (3n e 4 "E2B/E4B") já empacotados. llama.cpp continua como alternativa para modelos GGUF de outras famílias. |
| "IA offline de bilhões de parâmetros" | ✅ com ressalva | Viável na faixa **2B–4B parâmetros efetivos** em quantização de 4 bits ou mista. Modelos de 7B–8B cabem em 12 GB, mas com velocidade e temperatura piores — ficam como "modo avançado", não como padrão. |
| Nome "JARVIS" | Atenção | É marca da Marvel/Disney. Sem problema para uso pessoal; se um dia for publicado, trocar o nome. |
| Comparação com Jolly (Meta Muse) e Dots (OpenAI) | ✅ | A tendência é real: a Meta lançou o Muse com a mascote Jolly em set/2026 e houve crítica pública (ONG Fairplay) por o visual ser infantil num produto 18+ ✅. **Lição para o JARVIS:** fofura sem manipulação — o personagem nunca usa a aparência ou "emoções" para pressionar o usuário (ver seção 10.4). |

---

## 2. Realidade do Android: o que pode e o que não pode

### 2.1 Mapa de permissões necessárias

| Funcionalidade | Mecanismo Android | Tipo de permissão | Fase |
|---|---|---|---|
| Personagem flutuante sobre apps | `WindowManager` + `TYPE_APPLICATION_OVERLAY` | `SYSTEM_ALERT_WINDOW` (especial — "Exibir sobre outros apps") | 1 |
| Manter o personagem vivo | Foreground Service com tipo declarado (Android 14+ exige tipo) ✅ | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` (ou outro tipo adequado) + notificação persistente | 1 |
| Microfone | `AudioRecord` / `SpeechRecognizer` | `RECORD_AUDIO` (+ `FOREGROUND_SERVICE_MICROPHONE`) | 1 |
| Câmera | CameraX | `CAMERA` (+ `FOREGROUND_SERVICE_CAMERA`) | 1–2 |
| Agenda | `CalendarContract` (provedor local — não precisa de OAuth) | `READ_CALENDAR` / `WRITE_CALENDAR` | 2 |
| Contatos | `ContactsContract` | `READ_CONTACTS` | 2 |
| Arquivos | Storage Access Framework (usuário escolhe pastas) | Concessão por pasta | 2 |
| Google Drive / Gmail | APIs REST do Google com OAuth | Escopos OAuth por serviço | 2 |
| App em primeiro plano | `UsageStatsManager` | `PACKAGE_USAGE_STATS` (especial) | 2 |
| Notificações | `NotificationListenerService` | Acesso a notificações (especial) | 2 |
| Ler a tela / operar apps | `AccessibilityService` | Acessibilidade (especial) | 2–3 |
| Captura de tela para visão | `MediaProjection` | Consentimento a cada sessão | 3 |
| Ser o "assistente padrão" do sistema | `VoiceInteractionService` / papel `ROLE_ASSISTANT` | Escolha nas configurações | 3 (opcional) |

### 2.2 Riscos específicos do HyperOS/MIUI (Xiaomi) ⚠️

A ROM da Xiaomi é conhecida por matar serviços em segundo plano para economizar bateria. O roteiro prevê, já na Fase 0:
- tela de onboarding que guia o usuário a ativar **"Inicialização automática"** e **"Sem restrições"** de bateria para o app;
- teste de permanência do overlay por 24 h com tela apagada/acesa;
- teste de câmera/microfone iniciados a partir do toque no overlay.

### 2.3 Indicadores de privacidade

O Android mostra um indicador verde quando câmera ou microfone estão em uso. Isso é **bom** para o projeto (transparência) e não deve ser contornado. A câmera nunca fica ligada de forma contínua (item 21 da especificação está correto).

---

## 3. Orçamento de hardware (8 GB e 12 GB)

### 3.1 Aparelho de referência ✅
- **SoC:** MediaTek Dimensity 7200-Ultra (4 nm) — 2× Cortex-A715 @ 2,8 GHz + 6× Cortex-A510 @ 2,0 GHz
- **GPU:** Mali-G610 MC4
- **RAM:** 12 GB · **Armazenamento:** 512 GB · **Bateria:** 5.000 mAh
- **NPU (APU MediaTek):** suporte pelo LiteRT para este chip específico ⚠️ não confirmado — planejar **GPU (OpenCL) e CPU** como backends principais; NPU é bônus se funcionar.

### 3.2 Orçamento de RAM (regra de projeto)

O Android e os outros apps usam boa parte da memória. Orçamento **máximo** do JARVIS:

| Componente | 8 GB | 12 GB |
|---|---|---|
| App + overlay + personagem 2.5D/3D | ~250 MB | ~350 MB |
| LLM local (carregado sob demanda) | ~1,5–2 GB (classe E2B) | ~3 GB (classe E4B) |
| STT offline (carrega só durante a fala) | ~200–500 MB | ~200–500 MB |
| TTS offline | ~100 MB | ~100 MB |
| Memória/embeddings | ~200–300 MB | ~200–300 MB |
| **Teto total do processo** | **≈ 3 GB** | **≈ 4,5 GB** |

Referências medidas: Gemma 3n E2B ≈ 2 GB e E4B ≈ 3 GB de RAM ✅; Gemma 4 E2B no LiteRT-LM com pesos de texto a partir de ~0,8 GB em memória, com visão e áudio carregados só quando usados ✅. **Os valores da tabela são metas de projeto**, não medições neste aparelho — a Fase 0 mede de verdade.

### 3.3 Regras do gerenciador de recursos
1. **Um modelo pesado por vez.** STT termina → descarrega (ou mantém, se houver folga) → LLM gera.
2. **`onTrimMemory`** do Android dispara descarga imediata do LLM.
3. **Temperatura e bateria** (`PowerManager.getThermalHeadroom`, `BatteryManager`) entram na decisão do orquestrador.
4. **Personagem em repouso consome quase nada:** animação idle a 30 fps no máximo, 0 fps quando minimizado.

---

## 4. Decisões de tecnologia (stack)

| Camada | Escolha principal | Alternativa | Justificativa |
|---|---|---|---|
| Linguagem / UI | **Kotlin + Jetpack Compose** | — | Padrão oficial Android. |
| Arquitetura | **Multi-módulo Gradle**, Clean Architecture, injeção com **Hilt** (ou Koin) | — | Cumpre a "regra de ouro" (tudo substituível). |
| Overlay | `WindowManager` + `ComposeView` em serviço | — | Compose funciona em overlay com `LifecycleOwner` próprio. |
| Personagem (MVP) | **Rive** (2.5D vetorial com *state machine*) | Lottie | Leve (poucos MB), animação por estados nativa, ideal para "fofinho". Permite lançar o MVP antes do 3D ficar pronto. |
| Personagem (3D) | **Filament** (Google) via **SceneView** com glTF/GLB | Unity as a Library | Filament é leve e feito para mobile; Unity aumenta muito o APK e a RAM. |
| Modelagem 3D | **Blender** (rig + *blendshapes* faciais), exporta GLB | — | Gratuito, padrão da indústria para glTF. |
| LLM local | **LiteRT-LM** com Gemma (E2B/E4B) | **llama.cpp** (GGUF: Qwen, Llama, Phi etc.) | LiteRT-LM tem aceleração GPU/NPU no Android e modelos prontos ✅. llama.cpp amplia a escolha de modelos. |
| STT offline | **sherpa-onnx** (Whisper / modelos PT-BR) | `SpeechRecognizer` on-device do Android | sherpa-onnx roda totalmente offline em Android. ⚠️ qualidade em PT-BR a medir. |
| TTS offline | **sherpa-onnx com vozes Piper PT-BR** | `TextToSpeech` do sistema | Voz neural offline e leve. |
| Voz online (modo live premium) | API realtime do provedor escolhido (WebSocket/WebRTC) | — | Latência baixa; opcional. |
| Visão | Modelo multimodal (Gemma 3n/4 com visão no LiteRT-LM, local) ou API online | ML Kit (OCR, rosto, objetos) | ML Kit resolve OCR e detecção leve sem LLM. |
| Banco | **Room** (SQLite) + **SQLCipher** (criptografia) | — | Padrão; criptografia exigida pela seção de segurança. |
| Busca vetorial (memória) | **ObjectBox** (tem busca vetorial) ou extensão vetorial no SQLite | Tabela simples + similaridade em Kotlin (MVP) | No MVP o volume é pequeno; não é preciso infraestrutura pesada. |
| Embeddings locais | Modelo de embedding pequeno (classe ~300M parâmetros) no LiteRT | Embeddings via API | ⚠️ escolher na Fase 2. |
| Preferências | **DataStore** | — | — |
| Segredos | **Android Keystore** + armazenamento criptografado | — | Nunca chaves de API no APK. |
| MCP | **SDK oficial MCP para Kotlin** (`io.modelcontextprotocol:kotlin-sdk`) ✅ | — | Mantido com a JetBrains; cliente e servidor; transportes stdio/SSE/WebSocket/HTTP. |
| Downloads de pacotes | **WorkManager** + verificação SHA-256 | Play Asset Delivery (se for para a Play) | Downloads retomáveis em segundo plano. |
| Sincronização | Google Drive (pasta de app) ou servidor próprio | — | Aproveita os 6 TB de nuvem. |
| Servidor próprio ("API minha") | PC/servidor com **Ollama** ou **vLLM** exposto com autenticação (ex.: via Tailscale) | — | Permite modelos grandes (30B+) no seu PC atendendo o celular. |

---

## 5. Arquitetura

### 5.1 Visão em camadas

```text
┌──────────────────────────────────────────────────────────────┐
│  CAMADA DE PRESENÇA (o personagem)                           │
│  Overlay · Renderer (Rive/Filament) · Gestos · Balão de chat │
│  Posicionamento inteligente · Expressões                     │
└───────────────▲───────────────────────────┬──────────────────┘
                │ eventos de estado          │ eventos do usuário
┌───────────────┴───────────────────────────▼──────────────────┐
│  CAMADA DE MENTE                                             │
│  Session Manager (live / chat / visão)                       │
│  Emotional State Engine  ·  Persona Engine (7 camadas)       │
│  AI Orchestrator  ──►  Provider Interface                    │
│  Memory Service (curto prazo, episódica, semântica, prefs)   │
└───────────────▲───────────────────────────┬──────────────────┘
                │                           │ chamadas de ferramenta
┌───────────────┴───────────────────────────▼──────────────────┐
│  CAMADA DE AÇÃO                                              │
│  Tool Gateway  ──►  Policy Engine (permissões + autonomia)   │
│      ├─ Conectores nativos (Agenda, Contatos, Arquivos…)     │
│      ├─ Clientes MCP (servidores locais e remotos)           │
│      ├─ Skills (rotinas declarativas)                        │
│      └─ Atuadores Android (intents, acessibilidade)          │
│  Audit Log (tudo que foi feito, quando e por quê)            │
└───────────────▲───────────────────────────┬──────────────────┘
                │                           │
┌───────────────┴───────────────────────────▼──────────────────┐
│  CAMADA DE SISTEMA                                           │
│  Sensores · Resource Manager (RAM/bateria/temperatura/rede)  │
│  Package Manager (download de modelos/vozes/personagens)     │
│  Segurança (Keystore, criptografia) · Sync                   │
└──────────────────────────────────────────────────────────────┘
```

### 5.2 Módulos Gradle

```text
:app                    → montagem, navegação, onboarding
:core:model             → tipos compartilhados (Message, ToolCall, Emotion…)
:core:common            → utilidades, coroutines, logging
:core:security          → Keystore, criptografia, cofre de segredos
:core:database          → Room + SQLCipher

:presence:overlay       → serviço de overlay, WindowManager, gestos
:presence:character     → interface CharacterRenderer + implementação Rive
:presence:character-3d  → implementação Filament (entra no MVP 2/3)
:presence:placement     → posicionamento inteligente
:presence:chat-bubble   → balão de conversa

:mind:orchestrator      → AI Orchestrator, roteamento, prompts
:mind:providers-api     → interface LlmProvider (contrato)
:mind:provider-local    → LiteRT-LM / llama.cpp
:mind:provider-cloud    → APIs externas e API própria
:mind:persona           → camadas de personalidade + modos especialista
:mind:emotion           → Emotional State Engine
:mind:memory            → memória + "Minha Memória"
:mind:voice             → STT/TTS (local e online)
:mind:vision            → câmera + visão

:action:gateway         → Tool Gateway + Policy Engine + Audit Log
:action:connectors-*    → um módulo por conector (calendar, drive, files…)
:action:mcp             → cliente MCP
:action:skills          → motor de skills
:action:automation      → acessibilidade/intents (MVP 2+)

:system:resources       → monitor de RAM/bateria/temperatura/rede
:system:packages        → download e gerência de pacotes
:system:sync            → backup/sincronização
:feature:settings       → telas "Meu JARVIS", permissões, armazenamento
```

### 5.3 Contratos centrais (o que garante a troca de peças)

```kotlin
interface LlmProvider {
    val id: String
    val capabilities: Set<Capability>        // TEXT, VISION, AUDIO, TOOLS, STREAMING
    suspend fun isAvailable(ctx: DeviceContext): Boolean
    fun generate(request: LlmRequest): Flow<LlmChunk> // texto, tool calls, fim
}

interface CharacterRenderer {
    fun setEmotion(emotion: Emotion, intensity: Float)
    fun play(state: AnimState)               // IDLE, LISTENING, THINKING, SPEAKING…
    fun lookAt(x: Float, y: Float)
    fun setMouthOpen(level: Float)           // sincronia labial com o áudio
}

interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonSchema
    val risk: RiskLevel                      // READ, WRITE, CRITICAL
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
```

**Regra:** o personagem só conhece `CharacterRenderer` e eventos; a mente só conhece `LlmProvider` e `Tool`. Nenhum módulo de presença importa nada de provedor de IA.

### 5.4 Fluxo do toque (caso principal)

```text
Toque no personagem
  → Overlay: animação "acordar" (imediata, < 100 ms, sem IA)
  → Abre Activity compacta (garante primeiro plano p/ câmera/mic)
  → Session Manager decide o modo: LIVE (voz) | CHAT (balão) | VISÃO
  → LIVE: microfone → STT → Orchestrator → LLM (streaming)
          → TTS → boca sincronizada + expressão vinda do Emotion Engine
  → Ferramenta necessária? → Tool Gateway → Policy Engine
          → (se exigir) balão de confirmação [Cancelar] [Confirmar]
          → executa → Audit Log → resposta honesta sobre o resultado
  → Fim da sessão: câmera/mic desligados, modelo descarregado se preciso,
    personagem volta ao IDLE
```

---

## 6. O personagem: criação visual e animação

### 6.1 Pipeline de criação do "Joctã digital"

| Etapa | Ferramenta | Entregável |
|---|---|---|
| 1. Referências | Fotos suas (frente, perfil, 3/4, expressões) | Pasta de referência |
| 2. Conceito | Geração de imagem por IA ou ilustrador: 6–10 variações de caricatura fofa | Folha de conceito aprovada |
| 3. *Turnaround* | Frente / lado / costas do conceito escolhido | Guia para modelagem |
| 4. Modelagem 3D | Blender (ou geradores imagem→3D como ponto de partida, depois limpeza manual) | Malha **≤ 15–20 mil triângulos** (meta para mobile) |
| 5. Rig + rosto | Blender: esqueleto simples + **blendshapes** (sorriso, piscar, sobrancelhas, vogais A/E/I/O/U para fala) | GLB com animações |
| 6. Versão 2.5D (MVP) | Rive: o mesmo personagem em vetor com *state machine* | Arquivo `.riv` (poucos MB) |

**Por que começar em 2.5D (Rive)?** Porque permite testar *toda a experiência* (overlay, toque, voz, expressões) em semanas, enquanto o modelo 3D é produzido em paralelo. A interface `CharacterRenderer` garante que trocar Rive por Filament não altera mais nada.

### 6.2 Estados de animação (mínimo do MVP)

`IDLE` · `SLEEPING` · `WAKING` · `LISTENING` · `THINKING` · `SPEAKING` · `HAPPY` · `SURPRISED` · `CONCERNED` · `CELEBRATING` · `CONFUSED` · `ERROR` · `DRAGGED` (reação ao ser arrastado) · `SHY` (minimizando)

### 6.3 Emotional State Engine

Entrada: sinais da conversa (o LLM devolve, junto da resposta, uma etiqueta estruturada `{"emotion":"happy","intensity":0.7}`), resultado de ferramentas (sucesso/erro), horário, modo descanso.
Saída: emoção com **decaimento no tempo** (volta ao neutro suavemente) → renderer + parâmetros de voz (velocidade, entonação).
É uma **simulação de comunicação**, explicitada nas configurações (item 43 da especificação está correto).

### 6.4 Posicionamento inteligente (evolução)

| Fase | Como decide onde ficar |
|---|---|
| MVP 1 | Encaixe magnético nas bordas; posição e tamanho **memorizados por app** em primeiro plano; minimiza automaticamente em apps de vídeo/jogos da lista. |
| MVP 2 | AccessibilityService lê os limites dos elementos clicáveis da tela e o personagem desvia deles; detecta teclado aberto. |
| MVP 3 | Aprende com suas correções ("toda vez que ele fica aqui, o usuário o arrasta para lá"). |

---

## 7. Cérebro: local, API própria e nuvem

### 7.1 Os três cérebros

| Cérebro | Exemplo | Quando usar |
|---|---|---|
| **Local pequeno** | Gemma classe E2B (LiteRT-LM) | Offline, comandos, classificação de intenção, respostas rápidas, privacidade máxima. |
| **Local avançado** | Gemma classe E4B / outros 3–4B quantizados | Conversa mais rica offline, quando RAM/bateria/temperatura permitirem. |
| **API própria** | Seu PC/servidor com Ollama/vLLM | Modelos grandes sob seu controle, sem custo por token. |
| **APIs externas** | Claude, Gemini, OpenAI etc. com **suas chaves** | Raciocínio de ponta, visão avançada, pesquisa, voz realtime. |

### 7.2 Regras do AI Orchestrator (ordem de decisão)

1. **Modo Privado ativo** → somente local.
2. **Dado marcado como sensível** (finanças, saúde, documentos pessoais) → preferência local ou API própria; nuvem externa só com permissão explícita.
3. **Sem internet** → local (e o personagem **avisa** que está em modo offline).
4. **Tarefa simples** (classificador local decide) → local.
5. **Tarefa complexa / visão / documento longo** → API própria ou externa conforme preferência.
6. **Bateria < limiar ou aparelho quente** → modelo menor ou nuvem.
7. **Falha de um provedor** → próximo da lista, informando o usuário.

### 7.3 Identidade única entre cérebros

O mesmo *prompt de sistema* montado pelo **Persona Engine** (identidade + personalidade + preferências + memórias relevantes + contexto + objetivos + habilidades) é enviado a qualquer cérebro. Assim o JARVIS "soa" como ele mesmo, seja qual for o modelo. Modelos locais recebem uma versão **compactada** do prompt (janela de contexto menor).

### 7.4 Conectar-se às suas outras IAs

Duas formas, ambas pela mesma interface:
- **Como provedor:** "peça para o Claude analisar este documento" → o orquestrador envia ao provedor indicado e o personagem explica o resultado.
- **Como fonte de aprendizado:** importar exportações de memória/preferências de outras IAs (arquivos JSON/Markdown) para "Minha Memória", **sempre com revisão sua antes de salvar**.

---

## 8. Memória

### 8.1 Tipos (mantidos da especificação) e implementação

| Tipo | Armazenamento | Exemplo |
|---|---|---|
| Imediata | RAM (janela da conversa) | Últimas mensagens |
| Episódica | Room + embeddings | "Em 05/10 terminamos o relatório do DETRAN" |
| Semântica | Room + embeddings | "Joctã é professor de matemática/física" |
| Preferências | Room (estruturado) | "Prefere respostas com fontes" |
| Habilidades | Arquivos de skill | "Fechar o dia" |
| Contextual | RAM com expiração | "Está no app de agenda agora" |

### 8.2 Ciclo de memória
1. **Extração:** ao fim de cada sessão, um passo do LLM propõe fatos candidatos.
2. **Filtro:** regras + suas configurações ("nunca memorizar dados de saúde", por exemplo).
3. **Gravação** com metadados: origem, data, motivo, nível de confiança.
4. **Recuperação:** busca por similaridade + recência + importância, limitada para caber no contexto.
5. **"Minha Memória":** ver, editar, apagar, bloquear; comando de voz **"Esquecer isto"**.

### 8.3 Backup
Exportação criptografada para o Google Drive (pasta do app) ou nuvem própria, com chave derivada de senha sua. Sem nuvem, tudo continua funcionando.

---

## 9. Conectores, MCP e Skills

### 9.1 Três formas de dar "braços" ao JARVIS

| Forma | Para quê | Exemplo |
|---|---|---|
| **Conector nativo** (módulo Kotlin implementando `Tool`) | Recursos do próprio Android, mais rápidos e offline | Agenda (CalendarContract), Contatos, Arquivos, Alarmes, Lanterna, Abrir app |
| **Cliente MCP** | Qualquer serviço que exponha um servidor MCP — **é o que torna o JARVIS expansível sem recompilar** | Seus servidores MCP no PC; serviços remotos que publicam MCP via HTTP |
| **Skill** (rotina declarativa) | Encadear ferramentas que já existem | "Fechar o dia", "Preparar aula de amanhã" |

### 9.2 Seus próprios apps (Joca Finanças, EduMath etc.)

Recomendação: **cada app seu expõe um pequeno servidor MCP** (local no PC/servidor, ou embutido no app Android). O JARVIS descobre as ferramentas automaticamente (`list_tools`) e o Policy Engine aplica as permissões. Assim, conectar um app novo = cadastrar a URL do servidor MCP + autorizar.

### 9.3 Formato de uma Skill (exemplo)

```yaml
id: fechar_o_dia
gatilhos: ["fechar o dia", "encerrar o dia"]
autonomia_minima: 2          # Operador
passos:
  - tool: calendar.list_events
    args: { periodo: "amanha" }
  - tool: tasks.list_pending
  - tool: mcp.joca_financas.resumo_do_dia
  - llm: "Resuma o dia e proponha prioridades para amanhã"
saida: fala_e_balao
```

Skills são arquivos versionados, criados por você **ou propostos pelo próprio JARVIS** ("percebi que você sempre faz isso; quer que eu crie uma rotina?") — mas só são salvas após sua aprovação.

### 9.4 Catálogo inicial de conectores por fase

| MVP 1 | MVP 2 | MVP 3 |
|---|---|---|
| Abrir apps (intents), alarmes/timers, lanterna, compartilhar texto, bateria/rede | Agenda, Contatos, Arquivos (SAF), Google Drive, Gmail (leitura), notificações, app em primeiro plano | Cliente MCP genérico, Joca Finanças, EduMath, operação de apps via acessibilidade, captura de tela |

---

## 10. Segurança, permissões e autonomia

### 10.1 Policy Engine
Toda chamada de ferramenta passa por ele. Ele verifica, nesta ordem: **permissão Android concedida → permissão do JARVIS concedida (central "O que JARVIS pode fazer?") → nível de autonomia → risco da ação → confirmação, se necessária**.

### 10.2 Matriz risco × autonomia

| Risco da ação | Nível 0 Observador | 1 Assistente | 2 Operador | 3 Autônomo controlado | 4 Agente pessoal |
|---|---|---|---|---|---|
| Leitura | ✗ | confirma | ✓ | ✓ | ✓ |
| Escrita reversível | ✗ | confirma | confirma | ✓ (se em skill aprovada) | ✓ |
| Crítica (excluir, enviar mensagem, financeiro) | ✗ | confirma | confirma | confirma | confirma |

Ações críticas **sempre** pedem confirmação, em qualquer nível (mantém o item 31 da especificação).

### 10.3 Itens obrigatórios
- Chaves de API: só no Android Keystore, nunca no código/APK.
- Banco de dados criptografado (SQLCipher).
- **Audit Log** imutável e visível ao usuário.
- **Modo Privado** (item 49): corta câmera, microfone, nuvem e persistência de memória.
- Prompt injection: conteúdo lido de e-mails, páginas, documentos e notificações é tratado como **dado, nunca como instrução**; ações sugeridas por esse conteúdo sempre exigem confirmação.

### 10.4 Princípios de caráter (complementam os itens 50–51)
- **Nunca mentir sobre ações** (item 51 mantido integralmente).
- **Fofura sem manipulação:** o personagem não usa tristeza, ciúme ou "carência" simulados para aumentar o tempo de uso ou pressionar decisões.
- **Transparência emocional:** emoções são declaradamente simuladas.

---

## 11. Sistema de pacotes (estilo jogo)

| Pacote | Tamanho estimado | Obrigatório? |
|---|---|---|
| **APK base** (overlay, personagem 2.5D, chat, orquestrador, conectores nativos, cliente de API) | **meta < 80 MB** | Sim |
| Voz offline PT-BR (STT + TTS) | ~150–500 MB | Não (há fallback online/sistema) |
| Cérebro local pequeno (classe E2B) | ~1,5–3 GB | Não (recomendado) |
| Cérebro local avançado (classe E4B) | ~3–5 GB | Não |
| Personagem 3D (Filament + texturas + animações) | ~50–300 MB | Não |
| Visão (multimodal local) | incluído ou ~0,5–2 GB extra | Não |
| Modelos experimentais (7–8B) | ~4–6 GB cada | Não |

> Os tamanhos são **estimativas de planejamento** baseadas nos arquivos de modelo publicados; o valor real é exibido pelo app **antes** de cada download (item 40 da especificação mantido).

Implementação: manifesto JSON com nome, versão, tamanho, SHA-256 e requisitos mínimos (RAM, armazenamento); downloads com **WorkManager**, retomáveis; origem configurável (Hugging Face, sua nuvem, seu servidor). Tela **"Meu JARVIS → Armazenamento"** (item 41) mostra, baixa, remove e troca pacotes.

---

## 12. Roteiro por fases

Cada fase tem **objetivo, entregáveis e critério de saída**. Não se passa de fase sem cumprir o critério. Não há datas: o ritmo depende do tempo disponível e dos agentes usados.

### Fase 0 — Fundação e provas de conceito (antes de qualquer UI bonita)

**Objetivo:** eliminar os riscos técnicos que podem derrubar o projeto.

| PoC | O que provar | Critério de saída |
|---|---|---|
| 0.1 Overlay persistente | Bolinha flutuante arrastável sobre qualquer app, sobrevivendo no HyperOS | Fica viva 24 h com uso normal e após bloquear/desbloquear |
| 0.2 Toque → câmera/mic | Toque no overlay abre sessão com microfone e câmera | Funciona em ≥ 95% de 20 tentativas, com o app em segundo plano antes do toque |
| 0.3 LLM local | LiteRT-LM com modelo classe E2B gerando em PT-BR | Medir: tempo até 1º token, tokens/s, RAM de pico, temperatura após 5 min. Registrar em `docs/benchmarks.md` |
| 0.4 STT/TTS offline | Reconhecer e falar PT-BR offline | Medir taxa de erro em 20 frases suas e latência |
| 0.5 Renderer | Personagem Rive provisório no overlay | Idle a ≤ 30 fps sem impacto perceptível na bateria em 1 h |

**Entregáveis:** repositório com módulos vazios + contratos (`LlmProvider`, `CharacterRenderer`, `Tool`), CI de build, `docs/benchmarks.md`, `docs/ADR/` (registro de decisões de arquitetura).

### Fase 1 — MVP 1: "Ele mora no meu celular"

**Funcionalidades:** personagem 2.5D (Rive) flutuante · arrastar, redimensionar (pinça), encaixar nas bordas, minimizar · toque para acordar · **modo chat** com balão · **modo live** por voz · IA online (provedor externo **ou** API própria) com streaming · IA local pequena (offline) · seleção automática local/online básica · persona base + 3 modos (amigável, objetivo, professor) · memória básica (preferências + fatos, com tela "Minha Memória") · conectores nativos simples · Policy Engine + Audit Log + confirmações · Modo Privado · onboarding de permissões (incluindo bateria do HyperOS).

**Critério de saída:**
- Do toque à primeira reação visual: < 100 ms.
- Do fim da fala à primeira palavra falada pelo JARVIS: online < 2 s; offline < 4 s (metas; ajustar com os dados da Fase 0).
- Uso diário por 7 dias seguidos sem travar ou sumir.
- Nenhuma ação executada sem registro no Audit Log.

### Fase 2 — MVP 2: "Ele me conhece e me ajuda de verdade"

**Funcionalidades:** visão (câmera sob demanda + OCR via ML Kit + multimodal) · agenda, contatos, arquivos, Drive, Gmail (leitura) · notificações e app em primeiro plano (contexto) · sistema de **skills** · memória completa (episódica + semântica com embeddings) · **AccessibilityService** para posicionamento inteligente · TTS neural offline · Emotional State Engine completo · personagem 3D (Filament) como opção, se o modelo estiver pronto.

**Critério de saída:**
- "Como está meu dia?" responde com dados reais da agenda.
- "Fechar o dia" executa como skill.
- O personagem desvia de botões e do teclado em pelo menos 5 apps que você mais usa.

### Fase 3 — MVP 3: "Ele cresce comigo"

**Funcionalidades:** **cliente MCP** genérico (cadastrar servidores por URL) · Joca Finanças e EduMath via MCP · múltiplos modelos locais com troca automática · **sistema de pacotes** completo e tela de armazenamento · biblioteca de personagens/roupas · sincronização e backup na nuvem · aprendizado de skills proposto pelo JARVIS · autonomia níveis 3–4 · operação de apps via acessibilidade.

**Critério de saída:**
- Adicionar um conector novo **sem recompilar** o app (apenas cadastrando um servidor MCP).
- Restaurar o JARVIS completo (memória, skills, configurações) em outro aparelho a partir do backup.

### Fase 4 — Versão avançada

"Joca, organize meu dia" ponta a ponta (agenda → tarefas → projetos → prioridades → plano conversado) · voz realtime premium · personagem que reconhece você pela câmera (local, opcional) · avaliar publicação (troca de nome, adequação às políticas da Play, versão sem automação por acessibilidade).

---

## 13. Como trabalhar com agentes de desenvolvimento

### 13.1 Regras para entregar o projeto a um agente
1. **Uma fase (ou um PoC) por vez.** Nunca pedir "construa o JARVIS".
2. Sempre anexar: este roteiro + a especificação conceitual + os contratos da seção 5.3.
3. Exigir em cada entrega: código compilando, testes, atualização do `docs/ADR/` e instruções de teste no aparelho.
4. Você testa no Redmi real antes de aprovar — emulador não reproduz HyperOS, GPU Mali nem limites de memória reais.

### 13.2 Prompt de partida sugerido (Fase 0)

```text
Você é o engenheiro Android do Projeto JARVIS. Leia docs/ROTEIRO.md e a
especificação conceitual. Execute SOMENTE a Fase 0:
1) Crie o projeto Kotlin multi-módulo conforme a seção 5.2 (módulos podem
   estar vazios), com Hilt, Compose e CI de build.
2) Implemente os contratos da seção 5.3.
3) Implemente as PoCs 0.1 a 0.5, cada uma isolada e testável.
4) Gere docs/benchmarks.md com o roteiro de medições a fazer no aparelho.
Não implemente UI final. Não coloque chaves de API no código.
Registre cada decisão técnica em docs/ADR/.
```

### 13.3 Estrutura de documentação do repositório

```text
docs/
├── ROTEIRO.md            ← este documento
├── ESPECIFICACAO.md      ← especificação conceitual (66 itens) — colar o texto original aqui
├── benchmarks.md         ← medições reais no aparelho (Fase 0)
└── ADR/                  ← uma decisão por arquivo (0001-overlay.md…)
```

---

## 14. Fontes verificadas

Consultadas em 06/10/2026.

**Android (documentação oficial)**
- Mudanças em foreground services (tipos obrigatórios no Android 14; restrição de câmera/microfone iniciados em segundo plano; exceção de overlay visível no Android 15): https://developer.android.com/develop/background-work/services/fgs/changes
- Restrições de início de foreground service em segundo plano (Android 12+): https://developer.android.com/about/versions/12/foreground-services

**Política da Google Play**
- Uso da API AccessibilityService (automação e assistentes não são considerados ferramentas de acessibilidade): https://support.google.com/googleplay/android-developer/answer/10964491

**IA no aparelho**
- LiteRT-LM (Google Developers Blog — Gemma 4 E2B, ~52 tokens/s via GPU em Android de referência; pesos de texto a partir de ~0,8 GB; visão/áudio sob demanda): https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/
- Visão geral do LiteRT-LM: https://developers.google.com/edge/litert-lm/overview
- Gemma 3n (Google DeepMind — E2B ≈ 2 GB, E4B ≈ 3 GB de RAM): https://deepmind.google/models/gemma/gemma-3n/
- Modelo Gemma 3n E4B empacotado para LiteRT-LM: https://huggingface.co/google/gemma-3n-E4B-it-litert-lm
- Modelo Gemma 4 E2B para LiteRT-LM (comunidade LiteRT): https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

**MCP**
- SDK oficial do Model Context Protocol para Kotlin: https://kotlin.sdk.modelcontextprotocol.io

**Aparelho de referência**
- Especificações do Redmi Note 13 Pro+ (Dimensity 7200-Ultra, Mali-G610 MC4, 12/16 GB): https://english.onlinekhabar.com/redmi-note-13-pro.html

**Contexto de mercado (mascotes de IA)**
- Axios — Meta lança mascote para o Muse: https://axios.com/2026/09/25/ai-doom-meta-muse-mascot
- Fortune — Mascotes Jolly (Meta) e Dots (OpenAI) e a questão da confiança: https://fortune.com/2026/10/02/meta-openai-ai-agent-mascots-jolly-dots-trust/

> Observação: a fonte citada no pedido original (dailyjournal.news) não foi verificada diretamente; as informações sobre Jolly/Muse foram confirmadas pelas fontes Axios e Fortune acima. Benchmarks de velocidade citados referem-se aos aparelhos usados pelo Google, **não** ao Redmi Note 13 Pro+ — por isso a Fase 0 mede no aparelho real.

---

## 15. Riscos e decisões em aberto

| # | Risco / decisão | Impacto | Mitigação |
|---|---|---|---|
| R1 | HyperOS mata o serviço do overlay | Alto | Onboarding de bateria/autostart; PoC 0.1 |
| R2 | Câmera/mic não iniciam a partir do overlay | Alto | Activity compacta no toque; PoC 0.2 |
| R3 | LLM local lento ou esquentando no Dimensity 7200 | Médio | Modelo menor; nuvem/API própria como padrão; PoC 0.3 |
| R4 | STT offline fraco em PT-BR | Médio | Alternar para STT online quando houver rede; PoC 0.4 |
| R5 | Produção do personagem 3D demorar | Médio | MVP com Rive 2.5D; 3D em paralelo |
| R6 | Prompt injection via conteúdo lido | Alto | Seção 10.3; confirmação obrigatória |
| R7 | Custo de APIs externas | Baixo/Médio | API própria (PC) + limites de uso configuráveis |
| D1 | **Provedor online principal** | — | Decidir na Fase 1 (pode ser trocado depois, por projeto) |
| D2 | **Nome definitivo do personagem** | — | Antes de qualquer publicação |
| D3 | **Onde roda a "API minha"** (PC em casa, VPS) | — | Antes da Fase 1 |
| D4 | **Estilo final do personagem** (caricatura fiel × mascote inspirado) | — | Após a folha de conceito (6.1, etapa 2) |

---

> **Manifesto (mantido):** *"Não é uma IA dentro do celular. É um personagem que vive dentro dele."*
