# ADR 0002 — Versões de build e estrutura de módulos

**Status:** aceita (Fase 0) · **Data:** 2026-10-06

## Decisão
- Versões (AGP 9.0.1, Kotlin 2.2.21, Compose BOM 2026.02.00, Gradle 9.2.1, CameraX 1.4.2) espelham o app oficial **Google AI Edge Gallery**, que usa LiteRT-LM em produção — reduz o risco de incompatibilidade.
- LiteRT-LM fixado em **0.17.1** (última tag publicada em 06/10/2026; o Gallery usa 0.11.0). A API usada (`Engine`, `EngineConfig`, `Backend.CPU()/GPU()`, `ConversationConfig`, `sendMessageAsync(): Flow<Message>`) foi conferida no código-fonte da tag `v0.17.1`.
- `-Xskip-metadata-version-check`, como no Gallery, porque o AAR do LiteRT-LM pode ser compilado com Kotlin mais novo.
- **Lógica pura em módulos Kotlin/JVM** (`core`, `mind:orchestrator`, `action:gateway`, `system:resources`, `presence:placement`), testável sem Android. `-Pjarvis.jvmOnly=true` compila só esses módulos (útil quando o Google Maven não está acessível).
- Plugins no classpath da raiz (`buildscript`) para todos os módulos compartilharem o mesmo classloader.
- Os demais módulos da seção 5.2 do roteiro serão criados quando a fase deles começar — módulos vazios agora seriam só ruído.

## Consequências
- O APK é compilado no GitHub Actions (`.github/workflows/android.yml`) e publicado como artefato.
