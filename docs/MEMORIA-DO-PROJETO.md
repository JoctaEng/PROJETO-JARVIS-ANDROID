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
**Plano atual (10/10/2026): `docs/PLANO-ATE-V1.md`** — v0.25 age direito (pedido 65) → v0.26 visão/notificações → v0.27 biblioteca de skills → v0.28 tarefas programadas → v0.29 Fase 3 → v0.30 Fase 4 → v1.0 comercial. A lista abaixo é a antiga.
1. **Resolver a instalação da v0.13.x** (isolar o serviço de acessibilidade) e então devolver o controle do celular.
2. **Etapa C**: visão ("o que é isto?" com câmera/tela); reconhecer a voz do usuário no "Oi Joca" (pesquisa, pode não valer a pena); "Bom dia" e "Fechar o dia" automáticos em horário escolhido; e-mail e notificações; rotinas ensinadas pelo usuário.
3. Pendências conhecidas: voz natural do Gemini depende da cota diária; arte dos personagens restantes; chave de assinatura fixa já ativa (conferida no CI); EduMath (ponte MCP) ainda sem teste no aparelho.
4. **Ideias do usuário ainda não implementadas**: cadastro/continuação de pedidos futuros vão na seção 8.

## 8. Pedidos do usuário (diário curto; acrescente no fim)
Lista completa e com situação: `docs/PEDIDOS-DO-USUARIO.md`. Diário de sessões: `docs/HANDOFF.md` §11.
- 10/10/2026 (pedido 65): bugs do avatar (2D ao fundo), agente mais humano (minimizar antes, esperar, rolar como gente, print da tela, tentar em silêncio), dezenas de skills, tarefas programadas, plano até a v1 comercial.
- 09/10/2026: pediu que esta memória em .md exista para nunca esquecer fases e ajustes; colou a conversa original (conceito + lista de nomes) para ser lembrada.

## 9. Onde está cada coisa
- Regras curtas: `CLAUDE.md`, `AGENTS.md` · Contexto técnico: `docs/HANDOFF.md` · Plano mestre: `docs/ROTEIRO.md` · Decisões: `docs/ADR/` (0001–0011)
- Pedidos: `docs/PEDIDOS-DO-USUARIO.md` · Histórico por versão: `docs/FASE-2.md` · Testes e relatórios: `docs/TESTE-V0.10.0.md`, `docs/TESTE-V0.11.0.md`, `docs/RELATORIO-DE-ERROS.md`
- Versão/fase do app: `gradle.properties` (`euno.phase`, `euno.version`). APK: aba Actions → artefato `Euno-fase<N>-v<versão>-build<run>.apk`.
- Testes de lógica: `./gradlew -Pjarvis.jvmOnly=true test` (o app Android só compila no CI).
- 09/10/2026: o usuário mostrou que o Euno não aparecia na lista de Não perturbe; faltava a permissão `ACCESS_NOTIFICATION_POLICY` no manifesto (corrigido na 0.13.2). **Lição:** toda permissão especial que o app pede ao usuário precisa estar declarada no manifesto; conferir o manifesto antes de dizer "liberar em Configurações".
- 09/10/2026: **a v0.13.1 instalou e a v0.13.0 não**: a declaração do serviço de acessibilidade impede a instalação neste aparelho. Controle do celular volta quando o serviço puder ser declarado sem quebrar a instalação. O usuário pediu: **não gerar APK novo até ele mandar**.
- 09/10/2026: dados do aparelho de teste (prints do usuário): Redmi Note 13 Pro+ 5G (23090RA98G), **HyperOS 3.0.302.0 (WNOMIXM.C07), Android 16 build BP2A.250605.031.A3, patch 2026-08-01**, 512 GB com 260 GB livres. Espaço **não** é a causa da falha de instalação da v0.13.0; a causa segue desconhecida (não verificada em fonte oficial se o HyperOS 3/Android 16 restringe instalar apps que declaram serviço de acessibilidade).
- 09/10/2026: **causa da falha de instalação = Google Play Protect bloqueia o APK com serviço de acessibilidade** (print do usuário). Declaração do serviço não é o problema; controle do celular fica condicionado a desligar o Play Protect (opção A) ou a outro caminho. Aguardando decisão do usuário.
- 10/10/2026: v0.14.0 pedida pelo usuário ("tudo que testei não adiantou nada"): relatório completo em arquivo (txt/PDF) com registro contínuo por dia, exportar toda a conversa, tela "Testar funções", correções de abrir_app/confirmação/acessibilidade. Pendente dele: decidir reescrever o histórico do git (dados pessoais antigos); "controle sob demanda" (disableSelf) não foi ordenado.
- 10/10/2026: relatório da 0.14.0 lido no Drive ("Relatório do Euno.pdf", build 65): exportação em arquivo funcionou (sem corte). Achados: tela_ler funciona com a acessibilidade ligada, mas o usuário relatou que o Euno lê a própria conversa (pedido 39); toque com emoji e limite de ferramentas (pedido 40). **O relatório não inclui o texto da conversa** (só tamanhos e as frases de "Pedido:" do registro de ações); para analisar uma conversa é preciso "Exportar toda a conversa". Usuário mandou **não criar** ainda.
- 10/10/2026: v0.15.0 criada por ordem do usuário ("Pode começar") após ele ler a lista do que seria feito. Duas camadas contra o Euno ler a si mesmo: modo agindo na tela + janelas do Euno ignoradas. A tela "Testar funções" da 0.14.0 mostrou: acessibilidade ligada mas não conectada (hipótese: HyperOS não reconecta após "Fechar por completo"; religar o serviço resolve). O usuário só testa quando mandar.
- 10/10/2026: v0.16.0 por ordem do usuário. Causas confirmadas no relatório da 0.15.0: (1) ferramentas chamadas com argumentos vazios/outros nomes → ArgFixer; (2) escuta presa à ChatActivity → VoiceSession no app + balão em janela de sobreposição. O serviço do personagem passa a pedir o tipo microfone sempre que houver permissão (antes só com "Oi Joca").
- 10/10/2026: v0.17.0 por ordem do usuário (pedidos 48–50): voz Azure pt-BR como 1ª opção (grátis F0 ~500 mil caracteres/mês; o Automático para em 480 mil), Piper pt-BR offline (~67 MB por voz, SHA-256 conferido), cérebros Groq e Cerebras (grátis) como principal ou **cérebro reserva** automático, ferramentas no prompt só com intenção de ação (economia de cota), tela **Configurar IA e voz** com passo a passo dentro do app. Correções do teste da 0.16: não inventar valores, abrir conversa do WhatsApp sem texto, agenda toca em Salvar, EduMath espera o app abrir, "pare" mais tolerante, "fechar app" = início/voltar, queda ao abrir Acessibilidade, relatório separa a versão atual. Fora: Gemma 4 no aparelho, Supertonic, Word, reconhecer a voz do usuário.
- 10/10/2026: teste da 0.17.0 (pedido 51, só anotado): unificar chaves — vários provedores com chave, teste e ordem próprios, e Configurar IA e voz ligada a Meu Euno (hoje um campo só, uma chave apaga a outra); Piper parado em "baixando 0 MB". Azure: conta pessoal precisou da avaliação gratuita (assinatura) antes de criar o recurso; o link direto de criação falha sem assinatura (AADSTS16000) → instruções do app devem mandar à página inicial do portal e ao "Iniciar".
- 10/10/2026: v0.18.0 por ordem do usuário (pedido 53): **cérebros em lista** (`BrainStore`/`BrainSlots`, chave por cérebro `brain_key_<id>`, migração única das chaves antigas, mesma lista em Meu Euno e Configurar IA e voz, ordem subir/descer, liga/desliga, Testar com 512 tokens); cérebro com 429 descansa o tempo pedido (`ProviderCooldown`, RetryHint entende "try again in"); gpt-oss com `reasoning_effort=low`; voz do Gemini usa a chave do Gemini da lista; agenda aceita "OK" com app de agenda na frente; nova ferramenta `tela_deslizar` (ação do app ou gesto; `canPerformGestures` no serviço — pode ser preciso desligar e ligar a acessibilidade); regras: não dizer que fez antes do resultado, não dizer "não consigo" sem tentar, "abra X" = abrir_app; Piper mostra e registra por que o download parou e aceita rede limitada; instruções do Azure (página inicial + avaliação gratuita).
- 10/10/2026: v0.19.0 (pedido 54, avatar falante): `LipSync.kt` (visemas Rhubarb A–H/X a partir do texto pt-BR, envelope RMS do PCM, `LipTimeline`, `SentenceMood`), `LipDriver` segue `playbackHeadPosition` (e `onRangeStart` na voz do Android), boca em camada (`drawMouthPatch`, `CharacterArt.mouthBox`) sobre o rosto da emoção da frase. Melhor resultado futuro exige arte: 9 bocas por personagem + Rive (padrão Duolingo).
- 10/10/2026: v0.20.0 (pedidos 55–56, avatar 3D): `assets/avatar3d` (three.js 0.186.1 + three-vrm 3.5.5, gerado por `tools/avatar3d`), modelo VRM da pixiv (licença VRM 1.0), `character/Avatar3D.kt` (WebView só com endereço interno), `VrmFace.kt` (+testes), overlay com moldura que fica com os toques, opção em Meu Euno → Personagem (ligar, enquadrar, importar .vrm, aplicar agora). 3D ligado por padrão; se falhar, volta ao 2D. Ready Player Me saiu do ar (31/01/2026).
- 10/10/2026: v0.21.0 (pedido 57): Joctã Casual e Luna em 3D, gerados por código (`tools/avatar3d/gerar_personagens.py`: esqueleto humanoide em pose T, malhas por peça presas ao osso, rosto com morph targets aa/ih/ou/ee/oh, blink, happy/sad/surprised/angry/relaxed); enquadramento pela altura real do modelo; o 3D troca sozinho ao mudar de personagem; `avatarModel` = personagem/exemplo. Estilo: boneco 3D simples (não é a arte Pixar das imagens 2D).
- 10/10/2026: decisão do usuário (pedido 63): personagem/3D é bônus; manter os avatares leves atuais (VRoid CC0 + Guardião) e **priorizar melhorias e novas fases** (Etapa C da Fase 2 e Fase 3). Uma sessão local do Claude no PC dele gera 3D por imagem (ex.: `euno-3d-personagem.glb` no Drive: relevo da imagem, sem esqueleto/boca) — não integrar sem pedido.
- 10/10/2026: v0.24.1 (pedido 64, sessão local ligada ao PC): Guardião em 3D sem custo, integrado por pedido explícito do usuário ("coloque no projeto no GitHub", "quero testar no celular"). TRELLIS grátis no Hugging Face sem cota → TripoSR (MIT) na CPU do PC; rosto do TripoSR borrado → arte da ficha projetada na frente e gravada na textura; boca, olhos e emoções montados no Blender 5.2. Testado em Chromium; falta o aparelho. ADR 0013.
