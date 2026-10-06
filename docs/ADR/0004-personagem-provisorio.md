# ADR 0004 — Personagem provisório desenhado em código

**Status:** aceita (Fase 0) · **Data:** 2026-10-06

A PoC 0.5 previa um personagem Rive provisório, mas ainda não existe arte (`.riv`). Para não bloquear a Fase 0, `ComposeCharacterRenderer` desenha um personagem simples em Compose (corpo, olhos que piscam, boca por emoção, respiração) e implementa o mesmo contrato `CharacterRenderer`.
- Animação limitada a 30 quadros/s por um laço com `delay`, não pela taxa de 120 Hz da tela.
- Mede fps (média por minuto) e latência toque → primeiro quadro reagindo.
- **Substituição:** quando a arte Rive existir, cria-se `RiveCharacterRenderer` com o mesmo contrato; nada mais muda. O custo medido aqui é uma referência, não a medida final do Rive.
