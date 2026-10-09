# HANDOFF — Euno: contexto completo e próximos passos

> Para qualquer IA (Antigravity, Claude Code, outra) ou pessoa que assuma o projeto. Escrito em 07/10/2026 e atualizado na noite do mesmo dia (builds 20 a 24; ver seção 12).
> **Leia este arquivo inteiro antes de mexer em código.** No fim de cada sessão, acrescente uma linha no **Diário** (seção 11).

## 0. Ordem de leitura
1. Este arquivo. 2. `CLAUDE.md` e `AGENTS.md` (regras curtas). 3. `docs/ROTEIRO.md` (plano mestre, seções 5, 9, 10, 12). 4. `docs/FASE-1.md` (guia do usuário). 5. `docs/ADR/` (decisões, 0001 a 0011). 6. `docs/arte/README.md` (arte dos personagens).

## 1. Quem é o usuário e como trabalhar com ele
- **Prof. Joctã** (JoctaEng no GitHub), professor; fala português do Brasil. Aparelho de teste: **Redmi Note 13 Pro+ 5G** (12 GB de RAM, Dimensity 7200-Ultra, HyperOS/Android 16).
- Preferências (valem em todo o projeto): **mensagens curtas e diretas; honestidade; não inventar conceito nem alterar valores; priorizar fontes confiáveis e conferir antes de afirmar; ser consistente**. Quando algo não foi verificado, dizer que não foi.
- **"CI verde" não é "funciona no aparelho".** Só o usuário valida no celular. Diga sempre o que foi verificado (testes, CI, leitura da fonte oficial) e o que não foi.
- Os créditos do usuário com a IA são limitados: seja objetivo, não refaça o que já está feito, não gere documentos de análise sem ele pedir.
- O usuário não quer ser perguntado de novo o que já decidiu (seção 8). Peça confirmação só para o que é dele decidir (ex.: privacidade, gasto, ação irreversível).

## 2. O projeto em poucas linhas
**Euno — "Seu segundo eu digital"** (nome escolhido no ADR 0008; o projeto nasceu como "JARVIS", marca da Marvel/Disney, por isso o pacote interno continua `com.joctaeng.jarvis`, para as atualizações não perderem dados). É um personagem 2D/3D "fofo" que **flutua sobre qualquer app** do Android, conversa por voz e texto, tem **memória**, usa **cérebro online (API) ou local (IA de ~4 bi de parâmetros)**, e **age no celular** (abrir apps, alarme, timer…) e **nos apps do usuário** (EduMath primeiro) via **MCP**. Foi pensado como "mais que um assistente": um segundo eu, amigo, mascote.
- Repositório: `JoctaEng/PROJETO-JARVIS-ANDROID` (**público**: nunca commitar chaves, senhas, tokens, frases secretas ou dados pessoais). Branch de trabalho: `claude/android-virtual-assistant-mascot-gg76n8`. **Nenhum PR foi aberto** (só abrir se o usuário pedir).
- App irmão: **EduMath** (`JoctaEng/edumath-codigomaker`, **privado**), do mesmo usuário. Branch com a integração: `claude/euno-mcp` (seção 7.6).

## 3. Como chegamos aqui (linha do tempo)
| Quando | O quê |
|---|---|
| Início | Pedido de um roteiro para "um segundo eu" mascote flutuante: ativado ao toque, chat em balão, modo live por voz, cérebro offline + API própria, conectores, expressividade. Resultado: `docs/ROTEIRO.md`. |
| Fase 0 | Provas de conceito (overlay, voz, LLM local, câmera, fps). **Validada no Redmi do usuário**: ouve, fala (voz robótica), flutua. |
| Fase 1 / v0.2.0 (build 7) | Conversa com cérebro real (APIs compatíveis com OpenAI + LiteRT-LM local), 12 personagens, memória, posição livre e pinça. Nome Euno. |
| v0.3.0 / 0.3.1 (builds 11, 12) | Primeira arte 2D (Joctã Casual) recortada do fundo; Joctã Casual vira o padrão; bolinha provisória some da escolha. |
| v0.4.0 (build 14) | IA do celular com **llama.cpp** + Qwen3-4B (mesmo motor e modelo do EduMath). Correção do nome de modelo do Gemini e limpeza da chave colada. |
| v0.5.0 (build 20) | Fórmulas no chat, 10 ferramentas Android, MCP (Binder e HTTP), voz natural (Gemini + Kokoro), chave de assinatura fixa (aguarda o segredo do usuário). |

| 07/10 tarde (builds 21–24) | **Antigravity**, a pedido do usuário (sem créditos do Claude): arte de Luna e Thor, portal dimensional (por inatividade e toque duplo), dossiê "Sobre mim", ícone novo, correção do CI da assinatura e relatório de falhas do teste real. Revisado na seção 12. |

Marcos de teste do usuário: Gemini (chave paga do AI Studio) conversa bem; IA local "lerdinha, mas respondeu bem e deu super certo"; alarme e Maps **abrem e gravam, mas não fecham** depois (seção 7.3).

## 4. Estado atual (v0.5.0, build 20)
**Funciona e foi confirmado pelo usuário:** conversa com Gemini; conversa com a IA local; ferramentas abrir/alarme/Maps (abrem e executam).
**Compila e tem teste automatizado, mas o usuário ainda NÃO confirmou no aparelho:** fórmulas desenhadas e lidas em português; voz do Gemini; voz offline Kokoro; MCP/EduMath; confirmação de ações e Histórico; autonomia por ferramenta.
**Não existe ainda:** comando de voz "tchau" e chamar pelo nome (7.2), encerrar o que a ferramenta abriu (7.3), arte dos 11 personagens restantes (7.4), chave fixa ativa (7.1), Fase 2 inteira.

## 5. Arquitetura e onde está cada coisa
Módulos Gradle (lógica pura em Kotlin/JVM com testes; Android só no `app`, `provider-local`, `provider-llama`):
- `core:model`, `core:contracts` — tipos e interfaces (`LlmProvider`, `LocalLlmProvider`, `Tool`, `CharacterRenderer`).
- `mind:orchestrator` — escolhe o cérebro (online/local, fallback). `mind:provider-cloud` — API compatível com OpenAI (Gemini, OpenAI, OpenRouter, Ollama/vLLM). `mind:provider-local` — LiteRT-LM (`.litertlm`). `mind:provider-llama` — **llama.cpp v0.5.0 + JNI** (`.gguf`), código nativo em `src/main/cpp` (a fonte do llama.cpp é clonada pelo CI, não está no git).
- `mind:persona` — personagens (`CharacterCatalog`), prompt (`Persona.kt`, regra de LaTeX em `MATH_RULE`), `SentenceChunker` (não corta fórmulas), `SpokenText`/`SpokenMath` (LaTeX → fala), `MemoryCommands`, `EmotionTag`.
- `mind:memory` — memória em JSON. `system:resources` — gerenciador de recursos. `presence:placement` — posição/encaixe. `presence:expression` — escolhe a expressão (neutro, feliz, pensativo, falando, ouvindo, surpreso, preocupado, dormindo) por estado/emoção/boca.
- `action:gateway` — `ToolGateway`, `PolicyEngine`, `AuditLog`, **`ToolProtocol`** (`<tool_call>`/`<tool_response>`), `ToolCallFilter`, **`AgentRunner`**. `action:mcp` — `McpClient`, `McpTool`, `HttpMcpTransport`.
- `app` (pacote `com.joctaeng.jarvis`): `JarvisApp.kt` (injeção manual), `overlay/OverlayService.kt` + `OverlayBus` (estado compartilhado), `character/` (`CharacterView.kt` desenha, `CharacterArt.kt` mapeia a arte, `CharacterSync.kt`), `conversation/ConversationController.kt` (coração: prompt → cérebro → voz → ferramentas), `chat/ChatActivity.kt` + `FormattedText.kt` (WebView isolado com `assets/chat`: marked + KaTeX + DOMPurify), `tools/` (`AndroidTools.kt`, `McpHub.kt`, `Toolbox.kt`, `ConfirmationCard.kt`), `voice/` (`VoiceOutput.kt`, `GeminiSpeech.kt`, `KokoroVoice.kt`), `ui/SettingsActivity.kt` ("Meu Euno"), `settings/AppSettings.kt`, `poc/ModelStore.kt` e `ModelDownload.kt`. AIDL do MCP: `app/src/main/aidl/com/joctaeng/euno/mcp`.
- Ferramentas: `tools/` + `action/`. Todas passam por `ToolGateway` (autonomia × risco, confirmação, histórico em arquivo `audit.jsonl`).
- Ferramentas atuais: `abrir_app`, `listar_apps`, `estado_do_celular`, `criar_alarme`, `criar_timer`, `lanterna`, `compartilhar_texto`, `abrir_link`, `pesquisar_na_web`, `abrir_mapa`; mais as do MCP (`edumath_*`).

## 6. Como compilar, testar e publicar
- **Testes (lógica pura):** `./gradlew -Pjarvis.jvmOnly=true test` (hoje 65+ testes; devem passar sempre).
- **APK:** o app Android **só compila no GitHub Actions** (o ambiente de nuvem do Claude Code não acessa o Google Maven). Push na branch → workflow `Android` → artefato `Euno-fase<N>-v<versão>-build<run>.apk` (aba Actions, fim da página da execução).
- **Compilar localmente (Android Studio, Windows/Linux):** JDK 21; Android SDK com **NDK 29.0.13113456** e **CMake 3.31.6**; clonar `git clone --depth 1 --branch v0.5.0 https://github.com/ggml-org/llama.cpp mind/provider-llama/src/main/cpp/llama.cpp`; baixar `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar` para `app/libs/` (SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`); depois `./gradlew :app:assembleDebug`.
- **Versão:** `euno.appName`, `euno.phase`, `euno.version` em `gradle.properties` (fonte única). Atualize ao entregar APK com mudanças relevantes.
- **Assinatura (ADR 0010):** o CI gera a keystore a partir do segredo `EUNO_SIGNING_SEED` (`tools/assinatura/gerar_keystore.py`). Sem o segredo, o APK sai com chave temporária e **não atualiza por cima** do instalado.
- **Commits:** mensagens em português, explicando o porquê. **Nunca** commitar `.gguf`, `.apk`, chaves ou o AAR (já estão no `.gitignore`).

## 7. PRÓXIMOS PASSOS (em ordem de prioridade)

### 7.1 (Usuário) Ativar a chave de assinatura fixa — bloqueia todo o resto
GitHub → repositório → Settings → Secrets and variables → Actions → New repository secret → nome `EUNO_SIGNING_SEED`, valor = frase longa (≥ 16 caracteres), guardada num gerenciador de senhas. Depois: rodar o workflow (novo push ou *Re-run*), o usuário **desinstala o Euno uma última vez** e instala o novo APK. Daí em diante cada APK instala por cima e mantém chave do Gemini, memórias e modelos baixados (Qwen 2,4 GB, voz 130 MB). A IA não consegue ver se o segredo existe; confirmar com o usuário. O log do CI mostra um aviso amarelo "Segredo EUNO_SIGNING_SEED ausente" quando não existe.

### 7.2 Animação de "dimensão" + recolher/chamar por voz (pedido do usuário; PARCIAL: o Antigravity fez o portal, faltam o comando de voz, chamar pelo nome e o "risquinho"; ver seção 12)
**Pedido (resumo fiel):** quando o personagem surge na tela, parte do corpo dele (ou o corpo inteiro, se o personagem tiver) aparece **como se saísse de outra dimensão**. Quando o usuário disser "tchau", "até logo" ou qualquer comando que mostre que não precisa dele agora, ele **se recolhe para essa dimensão** e fica só um **"risquinho" discreto** (a fenda entre as dimensões). Ele deve **entender o comando de despedida** e **atender quando o usuário o chamar pelo nome**, surgindo de novo.

Proposta técnica (ajustar ao ver o código):
1. **Estado:** `DimensionState { HIDDEN, OPENING, PRESENT, CLOSING }` em `OverlayBus` (ao lado de `anim`, `emotion`, `listening`). Persistir `dimensionHidden` em `AppSettings` e restaurar ao reiniciar o serviço.
2. **Desenho (só código, sem imagem nova):** em `character/CharacterView.kt`, no `Canvas`: um **portal** (anel elíptico com brilho na cor do personagem, `bodyColor`); a arte é recortada por `clipPath` (interior do portal) e **desliza de dentro para fora** (translação + leve escala, easing; ~600 ms abrindo, ~450 ms fechando). A arte atual é busto (cabeça e ombros): é o "parte do corpo saindo". Fechado: só a **fenda** (linha vertical/horizontal fina, brilho suave pulsando devagar).
3. **Janela:** em `overlay/OverlayService.kt`, quando `HIDDEN` reduzir a janela para ~16 dp × 64 dp na borda/posição atual (para não bloquear toques) e tornar a fenda tocável (toque = chamar). Em `OPENING`, ampliar a janela para `characterSizeDp` **antes** de animar, para não cortar. `animate=false` (0 fps) enquanto `HIDDEN`.
4. **Despedida por voz (determinística, antes do LLM):** novo objeto puro `DismissCommands` em `mind/persona` (padrão de `MemoryCommands`), com testes. Normalizar (minúsculas, sem acento). Frases: tchau, até logo, até mais, até amanhã, pode ir, pode se recolher, pode descansar, some, não preciso mais, por hoje é só, é só isso. **Evitar falso positivo**: só vale se a frase for curta (≤ 6 palavras) e a expressão for o núcleo dela ("como se diz tchau em inglês" NÃO recolhe). Interceptar em `ConversationController.send` (como as memórias). Resposta: despedida curta na voz do personagem (opção de desligar), depois `CLOSING`, parar a escuta contínua. Também oferecer a ferramenta `recolher_personagem` para o LLM.
5. **Chamar pelo nome — duas camadas:**
   - **(a) Sem escuta contínua (fazer primeiro):** tocar na fenda chama o personagem; também o botão/atalho já existente.
   - **(b) Por voz, com ele recolhido:** exige **escuta contínua do microfone**. **Decisão de privacidade do usuário**: o desenho original era "ativado ao toque"; o usuário pediu chamar pela voz, então é permitido, mas deve ser **opcional, desligado por padrão**, com notificação fixa ("Euno ouvindo só pelo nome"), e a detecção do nome **100% no aparelho** (nenhum áudio gravado nem enviado). Serviço em primeiro plano com tipo `microphone` (Android 14+: permissão `FOREGROUND_SERVICE_MICROPHONE`; verificar regras de início a partir do segundo plano), e pedir "sem restrições de bateria" (a tela já existe em `device/SystemSettings.kt`) porque o HyperOS mata serviços.
   - **Motor de detecção (NÃO verificado; pesquisar fonte oficial e licença antes de escolher):** (i) *sherpa-onnx KeywordSpotter* (já temos o AAR 1.13.8; falta confirmar modelo e palavras-chave em português); (ii) *Vosk* com modelo pequeno pt-BR e gramática restrita ao nome (verificar o modelo e a licença); (iii) *Porcupine* (suporta português, exige AccessKey e palavra treinada; proprietário). Prototipar, medir **bateria** e **falsos acionamentos** no aparelho do usuário.
   - Nomes aceitos: `settings.displayName` (o nome do personagem, que o usuário pode trocar) e "Euno"; prefixos "ei", "oi".
6. **Testes:** `DismissCommands` (frases e falsos positivos); máquina de estados da dimensão (puro, testável); o resto é visual: o usuário valida no aparelho.

### 7.3 Encerrar o que a ferramenta abriu (pedido do usuário, NÃO implementado)
**Fato técnico (conferir se algo mudou):** no Android um app **não pode fechar outro app**. Caminhos reais, do mais simples ao mais forte:
1. **Voltar ao início/anterior:** ao concluir a tarefa, o Euno traz a tela inicial (`ACTION_MAIN` + `CATEGORY_HOME`). Não fecha o app, mas o tira da frente. O Euno tem `SYSTEM_ALERT_WINDOW`, que dá exceção para iniciar atividade em segundo plano (confirmar no aparelho).
2. **Serviço de Acessibilidade** (já previsto na Fase 2 para o personagem desviar de botões): `performGlobalAction(GLOBAL_ACTION_BACK / HOME / RECENTS)` permite "voltar/fechar" de verdade, com a permissão especial que o usuário concede. **Recomendado**: construir esse serviço uma vez e usar nos dois casos.
3. `ActivityManager.killBackgroundProcesses(pacote)` (permissão `KILL_BACKGROUND_PROCESSES`): só encerra processos **em segundo plano**; serve depois de o app sair da tela.
Regras: encerrar **só o que o Euno abriu agora** (a ferramenta `abrir_app` deve devolver o pacote; guardar), nunca um app que o usuário já estava usando; **não** encerrar navegação ativa (Maps) sem perguntar; fechar a tela do Relógio **não** cancela o alarme/timer (confirmar no aparelho). Investigar também por que `EXTRA_SKIP_UI` não evitou abrir a tela do Relógio no HyperOS. Ferramentas novas: `fechar_app`, `voltar_ao_inicio` (risco `WRITE_REVERSIBLE`, passam pelo Gateway).

### 7.4 Arte dos personagens
- **Pronto:** Joctã Casual com 6 de 8 expressões (`neutro, feliz, pensativo, falando, surpreso, dormindo`). **Faltam:** `ouvindo` e `preocupado` (hoje caem em `neutro` e `pensativo`).
- **Folha original** em `docs/arte/folha-de-personagens.png`; recorte de cada um em `docs/arte/<id>/referencia.png` (o robô Zig foi excluído por pedido do usuário). IDs: `joca, luna, thor, nina, selene, rex, maya, kiko, astra, jocta_estrategista, jocta_casual, jocta_jovem`.
- **O usuário vai gerar as imagens que faltam** (3 Joctãs inteiros; as 2 expressões do Joctã Casual) e enviá-las em `docs/arte/<id>/<expressao>.png|jpg` (pode ser do Gemini, com qualquer nome: a IA identifica, renomeia e padroniza; fundo xadrez "falso" é removido).
- **Os outros 9** já têm 72 imagens (8 por personagem) geradas no Canva; os IDs de mídia estão em `docs/arte/canva_ids.json`. **Não foram baixadas** em resolução cheia: o ambiente de nuvem bloqueia `www.canva.com`, `media.canva.com`, `export-download.canva.com` (e `huggingface.co`). Opções: liberar esses domínios em *Network access → Custom* do ambiente; ou baixar à mão no Canva. Qualidade conhecida (relatório da geração): Nina e Maya `ouvindo` com enquadramento um pouco mais apertado; Astra `preocupado` com orelhas abertas; Kiko com o logotipo do boné variando; topo da cabeça perto da borda em todos.
- **Pipeline:** `pip install "rembg[cpu]" pillow scipy` → `python tools/arte/preparar_arte.py <id>` (recorta o fundo, aplica o mesmo enquadramento, grava `app/src/main/res/drawable-nodpi/arte_<id>_<expressao>.webp`) → registrar em `character/CharacterArt.kt`. **Melhoria sugerida:** trocar o mapa fixo por `resources.getIdentifier("arte_<id>_<expr>", "drawable", pacote)`, para que adicionar arte seja só rodar o script. Só personagens com arte aparecem na escolha; os demais aparecem como "arte em produção".
- Fazer a conferência visual (fundo escuro e claro) depois de cada recorte, como no `0.3.0`.

### 7.5 Checklist para o usuário testar a v0.5.0 (e reportar com print)
1. Pedir "explique a equação do 2º grau para leigos": fórmulas desenhadas; ele **lê** as fórmulas em português; botão **Copiar**.
2. Meu Euno → Voz: motor Automático; testar voz Gemini (online), baixar a **voz offline Kokoro** (130 MB, Wi-Fi) e testar sem internet; conferir se soa natural em português do Brasil.
3. "Abre o EduMath", "alarme às 7h", "timer de 5 minutos", "liga a lanterna", "como está a bateria?": confirmação aparece para o que altera; **Histórico de ações** registra.
4. Autonomia (Observador/Assistente/Operador/Agente) muda o comportamento; desligar uma ferramenta a bloqueia.
5. IA local com ferramentas (modo avião): confiabilidade do `<tool_call>` no Qwen3-4B (**não medida**).
6. Depois de recompilar o EduMath (7.6): Meu Euno → "Procurar e testar" lista o EduMath com suas ferramentas.

### 7.6 EduMath
- Branch `claude/euno-mcp` do EduMath (versão `1.0.9-euno`, `versionCode 10`): `android-app/.../euno/` (`EunoMcpService`, `EunoMcpPonte`, `EunoMcpPlugin`), AIDL idêntico ao do Euno, `services/euno/servidorMcp.ts`, ligação no `MorpheusFlutuante`/`App.tsx`. Detalhes em `CONTEXTO_PROJETO.md` do EduMath ("Porta do Euno").
- Verificado: `tsc --noEmit` sem erros; `servidorMcp.teste.ts` (11 verificações) passa. **Não** compilado em APK nem testado no aparelho.
- **O usuário precisa:** mesclar a branch (ou compilar dela) e gerar o APK no Windows (`CONTEXTO_PROJETO.md`: *Gerar o APK*), **assinando com a keystore de release dele** (`%USERPROFILE%\.edumath-keystore`), senão não atualiza o EduMath instalado.
- Ferramentas: as de leitura do Mathie (`edumath_listar_turmas`, `resumo_turma`, `buscar_aluno`, `boletim_aluno`, `alunos_em_risco`, `alunos_em_recuperacao`, `destaques`, `pendencias_de_nota`, `agenda`, `explicar_calculo`, `abrir_tela`) + `edumath_perguntar`, `edumath_lancar_nota`, `edumath_estado_sincronizacao`, `edumath_sincronizar_agora`.
- Regras do EduMath que valem (arquivo `AGENTS.md` dele): nunca alterar AVA/SAF sozinho; toda conta de nota passa por `services/gradeEngine.ts`; nota existente só com pedido expresso; só turma ativa; backup antes de alterar; **nunca** gravar notas em vários bancos.
- Lacunas conhecidas: chamada/frequência e financeiro não têm ferramenta (financeiro só como "fichas" do Mathie); robôs do AVA/SAF só no PC (rotas `/api/robo/*`, `/api/turmas/:id/sync-grades`, `launch-saf`). Para o Euno usá-los, o servidor Flask precisaria expor um `/mcp` (transporte HTTP já existe no Euno).
- Com o EduMath fechado o serviço responde `-32001` e o Euno **abre o EduMath e tenta de novo** (`McpHub`).

### 7.7 Depois: fechar a Fase 1 e começar a Fase 2
- **Critérios de saída da Fase 1** (`docs/ROTEIRO.md`, seção 12): toque → reação visual < 100 ms; fim da fala → primeira palavra < 2 s online / < 4 s offline; 7 dias seguidos sem travar ou sumir; nenhuma ação sem registro. Medir no aparelho do usuário.
- **Fase 2** (ordem sugerida): agenda (primeiro teste do roteiro: "como está meu dia?" com dados reais), contatos, arquivos, Drive, Gmail (só leitura), notificações e app em primeiro plano, visão (câmera + OCR), skills ("fechar o dia"), memória com embeddings, **AccessibilityService** (compartilhado com 7.3), TTS neural offline (já adiantado), personagem 3D opcional.

## 8. Decisões que NÃO devem ser desfeitas
- Nome **Euno**; pacote interno `com.joctaeng.jarvis` (ADR 0008).
- **Dois cérebros**: API online **e** IA local de 2,5 a 4 bi de parâmetros (Qwen3-4B Q4_K_M via llama.cpp; ADR 0009). O local nunca pode deixar de funcionar.
- Chave de API **só no aparelho** (Android Keystore); nunca no código, no APK ou no GitHub.
- Toda ação passa por Policy Engine + confirmação + Audit Log; **crítico sempre confirma**; padrão **Operador** (ADR 0011).
- **Arte sem a bolinha provisória**: só aparecem na escolha personagens com arte.
- MCP **pelo Binder** no celular (offline, sem porta aberta) e HTTP para PC/rede (ADR 0011).
- Segredos nunca no repositório (é público). A frase `EUNO_SIGNING_SEED` só existe nos Secrets do GitHub e com o usuário.
- O usuário decide sobre **privacidade** (microfone sempre ligado só opcional e desligado por padrão).

## 9. Riscos e itens NÃO verificados (diga isto ao usuário quando for relevante)
- **Gemini TTS:** o modelo (`gemini-3.8-flash-tts`) e o formato da chamada vêm do cookbook e do SDK oficiais (`google-genai` 2.28), **mas a página oficial de idiomas estava bloqueada**: não confirmei suporte a **pt-BR** nem o custo. Se falhar, a fala cai no Kokoro/Android sem travar.
- **Kokoro pt-BR:** vozes `pf_dora` (42), `pm_alex` (43), `pm_santa` (44) confirmadas nos metadados do modelo e síntese de teste gerada, mas **a qualidade auditiva não foi avaliada por humano**.
- **Qwen3 e `<tool_call>`:** o formato é o que o modelo aprendeu, mas a **confiabilidade não foi medida**; ajustar o prompt se errar com frequência.
- **Velocidade da IA local:** ~3 a 7 tokens/s (medida no EduMath no mesmo aparelho); a 1ª resposta pode levar ~30 s; as seguintes usam o cache do prefixo (a hora fica no fim do prompt de propósito).
- **HyperOS:** pode matar serviços em segundo plano e ignorar `EXTRA_SKIP_UI`; testar sempre no aparelho.
- **FormattedText (WebView):** a página não acessa rede nem arquivos; validar no aparelho o desempenho com respostas longas em streaming (redesenha ~7x/s).
- Segredo de assinatura ausente = APK com chave temporária (7.1).

## 10. Ambiente do Claude Code na nuvem (se a IA for ela)
- Sem acesso ao Google Maven: o app não compila lá; usar o CI e ler o log (`get_job_logs`).
- Rede restrita por política: bloqueados `huggingface.co`, `www.canva.com`, `media.canva.com`, `export-download.canva.com`, `ai.google.dev`. Liberados e usados: GitHub, registro npm, PyPI, Maven Central. O usuário muda isso em *Network access* do ambiente.
- Ferramentas do GitHub são por MCP (sem `gh`). Não abrir PR sem o usuário pedir.

## 11. Diário (acrescente uma linha por sessão: data, o que fez, o que ficou pendente)
- **2026-10-07** — v0.3.0 a v0.5.0: arte do Joctã Casual, llama.cpp + Qwen3, correções do Gemini, chave fixa (código), fórmulas, ferramentas Android, MCP (Binder/HTTP), voz Gemini + Kokoro, porta do EduMath (branch `claude/euno-mcp`). Pendentes: segredo `EUNO_SIGNING_SEED`, dimensão/portal e chamar pelo nome, encerrar apps abertos, arte restante, testes do usuário na v0.5.0, recompilar o EduMath.

- **2026-10-07 (noite)** — Revisão do trabalho do Antigravity (builds 21–24): seção 12. Nada de código do app alterado nesta sessão; adicionado `tools/arte/verificar_alinhamento.py`.

## 12. Revisão das mudanças do Antigravity (07/10, noite) — feita por leitura do código, testes e medição

**Escopo:** 4 commits (`f9dc784`, `ea9fe64`, `6d93eb2`, `be2eca2`), 46 arquivos, +609/−80. **Testes JVM: 85, 0 falhas. CI: builds 22, 23 e 24 verdes; o 21 falhou e foi corrigido no 22.** Nada do que está abaixo foi validado no aparelho por mim; o relatório do usuário está em `docs/FALHAS_E_MELHORIAS_TESTES.md`.

**Entregue e correto:** correção do CI (`cryptography`); Luna e Thor com 8 expressões, registradas em `CharacterArt`; dossiê "Sobre Mim" (`AppSettings.userBio`, `Persona.kt`, tela em Meu Euno) com teste; portal dimensional desenhado em `CharacterView.kt` (anéis, transição de 420 ms); recolher por inatividade (`autoPortalDismiss`) e por toque duplo; ícone novo; relatório de falhas bem escrito.

**Problemas encontrados (do mais grave ao menos):**
1. **Arte da Luna e do Thor desalinhada (medido).** `python tools/arte/verificar_alinhamento.py luna thor jocta_casual`: Joctã Casual 0 falhas; Luna e Thor falham em quase todos os quadros (tronco deslocado de 56 a 177 px em 512; poses diferentes: Luna "neutro" está andando, Thor "neutro" é uma figura menor; alturas variam até 8%). Ainda há um **portal desenhado dentro da imagem** em vários quadros (e ausente em outros), além do portal desenhado pelo código: aparecem dois. **Esta é a causa real do "corpo todo se move quando ele fala"** (Falha 5): a fala alterna `falando` com o quadro-base e eles são fotos diferentes. Reduzir `bobbing`/`breathing` (solução proposta no relatório) **não resolve**. Correção: gerar cada expressão como edição só do rosto da MESMA imagem-base (mesma pose, câmera e escala; sem efeitos nem portal), recortar com `tools/arte/preparar_arte.py` e só aceitar quando o verificador passar sem FALHA.
2. **Dados pessoais no repositório público.** Nome completo, empregadores e cidade do usuário estão em `SettingsActivity.kt` (texto de exemplo e botão "Preencher modelo") e em `PersonaTest.kt`. Remover (usar texto fictício) e decidir se o histórico precisa ser limpo. **O dossiê vai inteiro no prompt de TODA requisição, inclusive para o Gemini (nuvem)**, sem limite de tamanho: arquivo grande (`readText()` de qualquer arquivo) pode estourar o contexto de 4096 da IA local (erro "conversa longa demais") e encarece a nuvem. Pôr limite (ex.: 1.500 caracteres no modo local, 6.000 na nuvem; truncar a leitura do arquivo) e avisar na tela que o dossiê é enviado ao provedor online.
3. **O portal não é o que o usuário pediu.** Pedido: um "risquinho" discreto quando recolhido. Feito: orbe holográfico grande e pulsante, desenhado também com o personagem em pé (pedestal). A janela **não encolhe** ao recolher (`OverlayService` só troca o estado do renderer): a área invisível continua bloqueando toques no app de baixo. A animação segue a 30 fps mesmo recolhido (`animate = true` fixo): custo de bateria. **Toque duplo:** o primeiro toque já executa `onTap()` (abre chat/escuta) e o segundo recolhe: o chat abre e o personagem some. **Recolher por inatividade (40 s) vem ligado**, mas só conta toque e fala do personagem: pode recolher durante a escuta, durante o "pensando" (a 1ª resposta da IA local leva ~30 s) ou com o chat aberto.
4. **Pedidos ainda não feitos:** comando de voz "tchau"/"até logo" (não há nada no código), chamar pelo nome, encerrar o app aberto pela ferramenta (7.3). A Falha 1 do relatório (esconder quando o usuário fala) segue aberta: o `OverlayService` só observa a fala do personagem, não a escuta.
5. **Assinatura fixa: risco latente.** `gerar_keystore.py` agora, se `ecdsa_deterministic` não existir, assina **sem** determinismo e segue em silêncio: cada build teria um certificado diferente e o Android recusaria a atualização. O build 21 ter falhado no passo de assinatura indica que o segredo `EUNO_SIGNING_SEED` já existe (o passo sai antes se ele faltar), mas **não confirmei que a impressão digital é a mesma entre builds**: no log de cada build a linha "SHA-256 do certificado:" deve ser idêntica. Correção: trocar o fallback por erro e fixar a impressão digital esperada em um arquivo do repositório que o CI confere.
6. **Falha 2 (áudio demora; "falar e receber só texto"):** a opção já existe (`speakReplies`, "Responder falando quando eu falar"); falta um atalho de um toque. A causa do atraso é provavelmente do código de voz original (não do Antigravity): o Gemini TTS é chamado por frase, sem streaming (o SDK oficial tem modo `stream=True` com PCM em pedaços), e **se o Gemini falhar a frase cai no Kokoro** (medido no sandbox: ~1,6× mais lento que o tempo real, 9 s para 5,7 s de fala), somando as esperas. Medir e instrumentar antes de mudar.
7. **Menores:** `metadata.json` na raiz (arquivo de ferramenta, nome "PROJETO JARVIS") pode sair; 16 JPGs de ~1,9 MB entraram no git (≈29 MB); mensagens de commit e o ícone ("Reator Arc", referência ao Homem de Ferro) voltam à marca JARVIS, contra o ADR 0008 (decidir se vale para uso pessoal ou se o ícone muda antes de qualquer publicação).

**Falhas 3 e 4 do relatório (tocar sem abrir o chat; modo trabalho "de costas"):** pedidos de funcionalidade válidos e ainda não feitos. A 4 exige arte nova (personagem de costas): decidir se vale o custo antes.
- 2026-10-07 (sessão Claude): limpeza de dados pessoais e limite de 4.000 caracteres do dossiê; assinatura sem fallback; tolerância a pausas na escuta; conhecimento de si; "tchau"; risquinho + toque que aproxima; v0.6.0. Pedidos e fases em `docs/PEDIDOS-DO-USUARIO.md`.
- 2026-10-08 (sessão Claude): registro único de eventos/erros/quedas + botão "Enviar relatório de erros" (`docs/RELATORIO-DE-ERROS.md`); voz: motor/tempos/pausas registrados, Gemini com disjuntor e timeout menor, fallback do Android na ordem; CI confere a assinatura do APK contra `tools/assinatura/impressao_esperada.txt`; v0.6.2.
- 2026-10-08 (sessão Claude, 2): do relatório real: modelo era descarregado ao fechar a conversa (onTrimMemory) e Kokoro roda 2,6× mais lento que o tempo real neste aparelho; corrigidos na v0.6.3 (pré-carga ao abrir a conversa, Kokoro lento -> voz do Android no Automático, métricas do llama no registro).
- 2026-10-08 (sessão Claude, 3): segundo relatório real: cérebro online responde em ~1 s; o atraso era a voz (Gemini TTS não achava o áudio na resposta; Kokoro 3-8x mais lento que o tempo real). v0.6.4: leitura robusta do Gemini TTS com duas formas de pedido e diagnóstico da estrutura da resposta.
- 2026-10-08 (sessão Claude, 4): início da Fase 2 (v0.7.0): agenda_consultar via CalendarContract + permissão em Meu Euno; VoiceCommands (despedida vs. parar de ouvir); prompt de ferramentas pede confirmação em pedidos confusos. Ver docs/FASE-2.md.
- 2026-10-08 (sessão Claude, 5): relatório 0.7.0: voz Gemini OK (generateContent), cérebro online 1-2,7 s; HyperOS OneKeyClean fechou o app; v0.7.1: pedido de permissão automático (PermissionActivity), contatos_buscar, relatório mostra otimização de bateria.
- 2026-10-08 (sessão Claude, 6): relatório 4 (cota diária 100 pedidos do Gemini TTS; kokoroLento=true). v0.8.0: fila de mensagens, paciência ajustável, comando STOP ("pera aí"), barge-in experimental, voz do Gemini com frases agrupadas e contador de cota. Próximo: conversa por voz sem chat (balão) e acessibilidade.
- 2026-10-08 (sessão Claude, 7): v0.9.0 (lote A, só compila no CI): prompt/histórico enxutos só para o cérebro local (decorador CompactProvider); timeout do TTS proporcional; descanso por cota do Gemini persistente; aliases na pesquisa web; chat usa a tela toda com o teclado aberto; seção "Conversa" no topo dos Ajustes (paciência, barge-in, silenciar bip, incluir conversa no relatório); botão "Nova" + "Resumos de conversa" (SummaryStore). Pendente (lote B): modo legenda sem janela, "Oi Joca", acessibilidade, skills.
- 2026-10-08 (sessão Claude, 8): v0.10.0 (lote B parcial, só compila no CI): modo legenda (ChatActivity vira balão no pé da tela, sem bloquear o app de trás; "Expandir" abre o chat); chamado "Oi Joca" opt-in (WakeWord puro com testes + WakeWordRunner no OverlayService com FGS microfone; sem reconhecer a voz do falante). Pendente: reconhecimento de voz do falante, acessibilidade, skills, memória completa, visão.
- 2026-10-08 (sessão Claude, 9): teste da v0.10.0 registrado em docs/TESTE-V0.10.0.md (relatório do usuário + relatório do Gemini sobre o vídeo, conferido). v0.11.0: relatório com resumo de todos os erros + logcat, escuta registrada passo a passo, "Oi Joca" solta o microfone, nova tentativa automática em falhas passageiras, espera da cota do Gemini corrigida, ferramentas de memória, limpeza de cifrões/etiquetas, WhatsApp por pacote, área "Teste completo", toque longo → fechar por completo. Pendente: acessibilidade, "Fechar o dia", reconhecimento do falante, visão.
- 2026-10-09 (sessão Claude, 10): v0.12.0 (Etapa A): cérebro local só quando escolhido ou sem internet; voz do Gemini com menos pedidos e contador real; um reconhecedor por vez; mute do bip com acesso a Não perturbe; ferramentas novas whatsapp_mensagem, agenda_criar e resumo_do_dia. Relatório da v0.11.0 analisado em docs/TESTE-V0.11.0.md. Próximo: Etapa B (acessibilidade e memória completa).
- 2026-10-09 (sessão Claude, 11): v0.13.0 (Etapa B): controle do celular por acessibilidade (tela_*), memória com categorias/edição/busca, Teste completo atualizado. Próximo (Etapa C): visão, reconhecimento do falante, ritual do dia automático, e-mail/notificações.
- 2026-10-10 (sessão Claude, 12): v0.14.0: EventLog.daily (7 dias), TranscriptStore, Exporter (FileProvider + MediaStore/Downloads + PDF), TestsActivity, AppNameMatch, AccessibilityLink, ScreenText.isSensitive reduzido, ScreenTap pede confirmação real (Toolbox.confirm; abre o chat se fechado). Só compila no CI. Próximo: conferir CI, o relatório do teste do usuário; Word; Etapa C.
