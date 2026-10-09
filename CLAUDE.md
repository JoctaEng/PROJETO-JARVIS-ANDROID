# Euno (projeto JARVIS) — convenções

- Produto: **Euno — Seu segundo eu digital** (ADR 0008). O pacote interno continua `com.joctaeng.jarvis` para as atualizações não perderem dados.
- **Versão e fase:** `euno.appName`, `euno.phase` e `euno.version` em `gradle.properties` são a fonte única. **Ao mudar de fase ou gerar um APK para o usuário com mudanças relevantes, atualize esses valores.** O CI publica o APK como `Euno-fase<N>-v<versão>-build<run>.apk`.
- Lógica pura fica em módulos Kotlin/JVM com testes (`./gradlew -Pjarvis.jvmOnly=true test`); o app Android só compila no CI (o ambiente de nuvem não acessa o Google Maven).
- Decisões técnicas em `docs/ADR/`; roteiro em `docs/ROTEIRO.md`; guia de cada fase em `docs/FASE-<N>.md`.
- **Leia primeiro `docs/MEMORIA-DO-PROJETO.md`**: visão, regras de trabalho com o usuário, fases, ajustes do plano e pendências. **Atualize-a a cada fase e a cada pedido novo do usuário.**
- **Repositório público: nenhum dado pessoal do usuário** (nome completo, familiares, telefones, empregador) em código, testes, docs ou commits; use nomes e números fictícios.
- **Contexto completo e próximos passos:** `docs/HANDOFF.md` (leia primeiro) e `AGENTS.md`. Acrescente uma linha ao Diário no fim de cada sessão.
