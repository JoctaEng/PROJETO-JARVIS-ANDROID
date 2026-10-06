# PROJETO JARVIS — Personal AI Character para Android

> *"Não é uma IA dentro do celular. É um personagem que vive dentro dele."*

Um personagem 3D fofo — uma caricatura original do Joctã — que flutua sobre qualquer app do Android, acorda ao toque, conversa por voz ou por balão de chat, enxerga pela câmera sob demanda, lembra das suas preferências e age no celular por meio de conectores autorizados (nativos, MCP e skills). O cérebro é híbrido e trocável: IA local (offline), API própria ou APIs externas.

## Documentos

| Documento | Conteúdo |
|---|---|
| [`docs/ROTEIRO.md`](docs/ROTEIRO.md) | Roteiro técnico: limitações reais do Android, orçamento de hardware, stack, arquitetura, fases (0 → 4) com critérios de saída e fontes verificadas |
| [`docs/FASE-1.md`](docs/FASE-1.md) | O que o APK da Fase 1 faz e como configurar o cérebro |
| [`docs/FASE-0.md`](docs/FASE-0.md) | Como instalar o APK da Fase 0 e rodar as provas de conceito no celular |
| [`docs/benchmarks.md`](docs/benchmarks.md) | Medições reais no aparelho (preenchidas após os testes) |
| [`docs/ADR/`](docs/ADR/) | Registro das decisões técnicas |
| `docs/ESPECIFICACAO.md` | Especificação conceitual (66 itens) — a adicionar |

## Status

**Fase 1 (MVP 1) — em andamento.** Fase 0 testada no aparelho: personagem flutua, ouve e fala.

- ✅ Projeto Android multi-módulo, contratos centrais e regras puras com testes
- ✅ APK de testes com as PoCs 0.1 a 0.5 (compilado pelo GitHub Actions)
- ⏳ Medições no aparelho real (ver `docs/FASE-0.md`)
- ⏳ PoC 0.4b (sherpa-onnx) e personagem Rive

## Compilar

```bash
./gradlew test :app:assembleDebug          # tudo (requer acesso ao Google Maven)
./gradlew -Pjarvis.jvmOnly=true test       # só a lógica pura, sem Android SDK
```

Dispositivo de referência: Redmi Note 13 Pro+ (12 GB RAM / 512 GB). Meta mínima: aparelhos com 8 GB de RAM.
