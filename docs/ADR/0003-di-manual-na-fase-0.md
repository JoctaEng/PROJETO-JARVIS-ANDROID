# ADR 0003 — Injeção de dependências manual na Fase 0

**Status:** aceita (Fase 0) · **Data:** 2026-10-06

O roteiro prevê Hilt. Na Fase 0 há poucos objetos (`Diagnostics`, `ModelStore`) e o Hilt exigiria KSP e mais plugins — mais risco de build sem benefício agora. A composição é feita em `JarvisApp`. **Revisitar no início da Fase 1**, quando entrarem orquestrador, memória e conectores no app.
