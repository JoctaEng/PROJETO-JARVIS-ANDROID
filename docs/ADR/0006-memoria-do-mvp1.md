# ADR 0006 — Memória do MVP 1 em arquivo JSON privado

**Status:** aceita (Fase 1) · **Data:** 2026-10-06

O roteiro prevê Room + SQLCipher e busca vetorial. No MVP 1 a memória só guarda o que o usuário pede explicitamente ("lembre que…"), em pequena quantidade, e tudo entra no prompt.

**Decisão:** `MemoryStore` (módulo puro `mind:memory`) grava um JSON no armazenamento interno do app (inacessível a outros apps; `allowBackup=false`). Cada item tem texto, origem, motivo e data, como pede a especificação ("Minha Memória").

**Revisitar no MVP 2:** com memória automática (extração ao fim da conversa) o volume cresce — aí entram banco cifrado e busca por similaridade.
