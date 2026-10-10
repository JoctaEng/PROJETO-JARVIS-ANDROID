# ADR 0013 — Guardião em 3D: TripoSR + projeção da arte + boca montada no Blender

- Data: 10/10/2026 (pedido 64)
- Estado: aceito (v0.24.1)

## Contexto
O usuário quer o Guardião (ficha 2D, v0.23.0) como avatar 3D que fale sincronizado com a voz, **sem custo**.
Geradores de imagem→3D (TRELLIS, TripoSR, Hunyuan3D) fazem a escultura, mas **não** fazem boca nem expressões.
O PC do usuário tem GeForce 910M (2 GB): não roda TRELLIS. Bonecos feitos só por código foram rejeitados (pedido 58).

## Opções
1. **TRELLIS / TRELLIS.2 no espaço grátis do Hugging Face**: melhor escultura, mas cota diária de GPU (2 min sem login,
   5 min com conta grátis); a cota acabou na 1ª tentativa e o usuário não quis criar conta. Fica como melhoria.
2. **TripoSR (MIT) na CPU do PC** (escolhida): ~6 min, sem conta. Corpo bom; **rosto borrado** (o rosto tem ~60 px na ficha).
3. Serviços pagos (Tripo, Meshy, Avaturn): descartados (custo).

## Decisão
- Escultura do TripoSR; **a arte da ficha é projetada na frente do modelo** (alinhamento pela silhueta, IoU 0,887) e gravada
  numa textura nova: o rosto e a armadura ficam como na arte; costas e lados mantêm a textura do TripoSR.
- Boca: corte na linha dos lábios + interior escuro + dentes; queixo gira num pivô atrás da boca, com abertura em lente.
  Formas `aa ih ou ee oh`, `blink`, `happy sad angry surprised relaxed` (os nomes que `avatar.js`/`VrmFace` já usam).
- Esqueleto humanoide VRM; só tronco, pescoço e cabeça mexem a malha (o app não anima braços; capa e pelo deformariam mal).
- Material sem luz (`KHR_materials_unlit`): a luz já está pintada na arte.
- No app: Meu Euno → Personagem → **Avatares 3D** → Guardião (com miniatura). O Guardião 2D continua em Personagens 2D.

## Consequências
- APK ~7 MB maior. Testado só em Chromium sem tela (carrega em ~0,5 s; as 11 formas respondem). **Não testado no aparelho.**
- Vista de lado/costas é menos nítida que a frente. A cabeça gira pouco no app, então o efeito é pequeno.
- Para trocar por uma escultura do TRELLIS depois: repetir as etapas 3–8 de `tools/avatar3d/guardiao/README.md`.
