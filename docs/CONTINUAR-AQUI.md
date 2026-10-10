# CONTINUAR AQUI — resumo para a próxima sessão (atualizado em 10/10/2026)

> Leia este arquivo primeiro. Ele resume a conversa longa com o usuário até a v0.24.0.
> Detalhes: `docs/PEDIDOS-DO-USUARIO.md` (pedidos 1–66), `docs/MEMORIA-DO-PROJETO.md`, `docs/HANDOFF.md`, `docs/FASE-2.md`.

## 1. Quem é o usuário e como trabalhar com ele
- Prof. Joctã (pt-BR). Celular: Redmi Note 13 Pro+ 5G, HyperOS 3, Android 16. Respostas **curtas, em português, honestas**.
- **Horários sempre no horário de Brasília** (America/Sao_Paulo, UTC−3), nunca em UTC. As ferramentas (CI, send_later) devolvem UTC: converter antes de falar (ex.: 15:24 UTC = 12:24 de Brasília). Pedido 68.
- **Não começar a programar sem ordem dele.** Quando ele diz "só anote", apenas registrar nos docs.
- Ele testa no celular e manda **relatórios e conversas pelo Google Drive** (arquivos "Relatório do Euno", "Testes rápidos do Euno").
  Para ler: `search_files` (modifiedTime recente) → `download_file_content` (vem em base64; arquivos grandes são salvos em disco, decodificar com python).
- **Repositório público: nenhum dado pessoal** (nome completo, família, telefones, empregador) em código/docs/commits. Chaves só no celular (SecretStore).
- Ao gerar APK com mudança relevante: subir `euno.version` em `gradle.properties`, rodar `./gradlew -Pjarvis.jvmOnly=true test -q --offline`,
  commit (atribuição Co-Authored-By/Claude-Session do sistema), push no branch `claude/android-virtual-assistant-mascot-gg76n8`,
  conferir o CI (GitHub Actions, workflow Android) e mandar o **link do artefato**: `https://github.com/JoctaEng/PROJETO-JARVIS-ANDROID/actions/runs/<run>/artifacts/<id>`.
  O APK vem num ZIP; instalar por cima, **nunca pedir para desinstalar**.
- Não é possível pôr o APK no Drive (ferramenta só envia conteúdo inline).
- Ele se irrita quando: telas/áreas somem ou são "resumidas", algo que funcionava quebra, ou entrego qualidade baixa dizendo que está bom. **Testar visualmente antes** (ex.: Chromium/Playwright para o avatar) e ser franco sobre limites.

## 2. Estado atual (v0.24.0, build 92 — CI verde)
- **Cérebros**: lista com vários provedores (Gemini, Groq, Cerebras, OpenAI, OpenRouter, servidor próprio), cada um com chave, Testar, liga/desliga e ordem;
  429 → descansa e passa ao próximo (`BrainStore`, `BrainSlots`, `ProviderCooldown`). Mesma lista em Meu Euno → Cérebro e em Configurar IA e voz.
- **Voz**: Automático = Azure (1ª, F0 ~500 mil caracteres/mês, para em 480 mil) → Gemini → Piper/Kokoro offline → Android.
  Escolha de vozes do Azure sempre visível (`AzureVoicePicker`). Piper baixado pelo próprio app (DownloadManager travava na fila). Usuário já tem chave Azure (brazilsouth).
- **Controle do celular** (acessibilidade): ler/tocar/digitar/rolar/deslizar/navegar. O Android desliga o serviço quando a permissão muda (aconteceu na 0.18 com gestos); a tela inicial avisa e tem botão "Ligar acessibilidade".
- **Sincronia labial e emoção** (v0.19): `presence/expression/LipSync.kt` (visemas pt-BR + volume real do áudio), `LipDriver`, emoção por frase (`SentenceMood`).
- **Avatar 3D** (v0.20+): VRM em WebView (three.js + three-vrm), `assets/avatar3d`, `character/Avatar3D.kt`, comandos de `VrmFace.kt`.
  Modelos no APK: exemplo da pixiv + **Victoria, Vita, Vivi, Shino, Fumiriya, Clara** (CC0 VRoid, texturas reduzidas por `tools/avatar3d/otimizar_vrm.py`).
  + **Guardião 3D** (v0.24.1, pedido 64: TripoSR + arte projetada + boca/emoções no Blender; ADR 0013).
  Aparecem no topo de Meu Euno → Personagem com miniatura. Importar .vrm próprio também funciona.
- **Personagens 2D**: Joctã Casual, Luna, Thor, **Guardião** (recortado da ficha do usuário `docs/arte/guardiao/ficha.png`, ampliado 4x com Real-ESRGAN em NumPy: `tools/arte/ampliar_esrgan.py`).
- O usuário **odiou** os 3D feitos por código (Joctã/Luna — removidos) e a boca "colada" do Guardião. **Gostou** dos avatares VRoid e do exemplo.

## 3. Decisões em vigor
- **Personagem é bônus, não a essência** (pedido 63). Não mexer em personagem sem pedido. Uma sessão local do Claude no PC dele cria 3D por imagem
  (ex.: `euno-3d-personagem.glb` no Drive = relevo da imagem, sem esqueleto/boca) — **não integrar sem pedido**.
  Exceção já feita: o **Guardião 3D** foi integrado na 0.24.1 a pedido explícito dele (pedido 64).
- Se um dia voltar ao personagem: boca boa exige variações da MESMA imagem só com a boca mudando (9 formas) — prompts estão na conversa/pedido 60–63.
- Foco agora: **melhorias e novas fases**.

## 4. Próximo passo
- **v0.25.0 feita** (pedidos 65–66; aguarda teste do usuário): avatar igual em todo lugar e sem o 2D por trás; agente que espera, rola como gente, vê o print (`tela_ver`), toca no ponto, tenta em silêncio. Detalhes em `docs/FASE-2.md`.
  Atenção no teste: a permissão de print faz o Android **desligar a acessibilidade uma vez** (a tela inicial avisa).
- Depois: **v0.26** (visão pela câmera, notificações, Bom dia/Fechar o dia automáticos) → v0.27 skills → v0.28 tarefas programadas → ... (`docs/PLANO-ATE-V1.md`).

## 5. Lembretes técnicos
- App Android só compila no CI; aqui só módulos JVM. Google Maven bloqueado aqui; npm, PyPI e raw.githubusercontent funcionam; Hugging Face não.
- Avatar: refazer o bundle com `cd tools/avatar3d && npm install && npm run build` (ou esbuild apontando para node_modules). Testar página servindo `app/src/main/assets/avatar3d/` + Playwright (Chromium em /opt/pw-browsers, flags `--use-angle=swiftshader`).
- Galeria de modelos VRM livres: repositório público `madjin/vrm-samples` (clonar com `GIT_LFS_SKIP_SMUDGE=1`).
- Relatório do app mostra primeiro só os eventos da versão atual.
