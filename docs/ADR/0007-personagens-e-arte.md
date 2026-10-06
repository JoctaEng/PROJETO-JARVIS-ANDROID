# ADR 0007 — Catálogo de personagens antes da arte final

**Status:** aceita (Fase 1) · **Data:** 2026-10-06

O usuário aprovou uma folha com 10 personagens e excluiu o robô (Zig); depois acrescentou 3 opções do "Joctã digital" (estrategista, casual, jovem estilizado). As imagens chegaram pela conversa e não estão no repositório.

**Decisão:** `CharacterCatalog` (módulo `mind:persona`) guarda identidade, traço, descrição, instrução de fala, gênero gramatical e cor de cada personagem. A personalidade já vai para o cérebro; o visual usa o personagem provisório na cor de cada um.

**Arte final (próximo passo):** colocar as imagens de referência em `docs/arte/` e produzir, por personagem, um arquivo Rive (2.5D, com os estados da seção 6.2) e depois um GLB (3D). O contrato `CharacterRenderer` não muda.
