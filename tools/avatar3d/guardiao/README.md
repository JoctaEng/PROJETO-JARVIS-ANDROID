# Guardião 3D (pedido 64)

Como `app/src/main/assets/avatar3d/models/guardiao.vrm` foi feito, sem custo, a partir de `docs/arte/guardiao/ficha.png`.
Decisão e limites: `docs/ADR/0013-guardiao-3d.md`.

| # | Etapa | Onde roda | Script |
|---|---|---|---|
| 1 | Recorte do guerreiro (sem lobo, sem pedra, sem fundo; rembg isnet) | nuvem/PC | `preparar_imagem.py ficha.png guardiao_input.png` (polígono do lobo fixo para esta ficha) |
| 2 | Escultura 3D: **TripoSR** (MIT) na CPU do PC | Windows | `setup_tsr.ps1`, depois `rodar_tsr.ps1` → `mesh.obj` + `texture.png` |
| 3 | Importar, soldar costuras, pôr em pé (1,80 m, olhando para -Y) e renderizar vistas | Blender 5.2 (`pip install bpy==5.2.2`) | `inspecionar.py mesh.obj saida -90 -90 texture.png` |
| 4 | Alinhar a silhueta da arte com a vista de frente (IoU 0,887) | Python | `alinhar.py front.png mapa.json guardiao_input.png alinhamento.json` |
| 5 | **Projetar a arte na frente do modelo** e gravar numa textura nova (rosto e armadura nítidos) | Blender | `projetar.py base.blend alinhamento.json guardiao_input.png saida 2048` |
| 6 | Corte da boca, interior e dentes, 5 bocas (aa ih ou ee oh), piscar, 5 emoções, esqueleto humanoide | Blender | `montar.py base_proj.blend marcos.json guardiao_rig.glb` |
| 7 | GLB → VRM 1.0 (expressões com os nomes que o `avatar.js` usa; material sem luz) | Python (pygltflib) | `to_vrm.py guardiao_rig.glb guardiao.vrm` |
| 8 | Teste na página real do avatar (Chromium sem tela) | Python (Playwright) | `testar_avatar.py assets/avatar3d models/guardiao.vrm saida busto [zoom]` |

- `marcos.json`: cantos da boca, olhos, sobrancelhas, queixo, pescoço (em metros), calculados da arte pelo `alinhamento.json`.
- Braços e pernas têm ossos, mas não mexem a malha (o app só gira tronco, pescoço e cabeça; a capa e o pelo deformariam mal).
- O TRELLIS (Microsoft, MIT) dá mais detalhe, mas o espaço grátis do Hugging Face tem cota diária de GPU (2 min sem login,
  5 min com conta grátis). `gerar_trellis1.py` fica pronto para isso; o resto do caminho (etapas 3–8) é o mesmo.
