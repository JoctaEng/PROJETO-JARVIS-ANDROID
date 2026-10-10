# Avatar 3D (VRM)

Página que desenha o avatar 3D do Euno com three.js + @pixiv/three-vrm, usada pelo app numa WebView transparente
(`app/.../character/Avatar3D.kt`). Tudo fica dentro do APK (`app/src/main/assets/avatar3d/`); nada é baixado da internet.

- `src/avatar.js` → gera `app/src/main/assets/avatar3d/avatar.js` com `npm install && npm run build` (esbuild, IIFE).
- Interface `window.Avatar`: `setMode`, `setMouth({aa,ih,ou,ee,oh})`, `setEmotion(nome, peso)`, `lookAt`, `setFraming`, `setPaused`, `status`, `value`.
- O app manda os comandos gerados por `presence/expression/VrmFace.kt` (testado na JVM).
- Modelo padrão: `models/avatar.vrm` (pixiv, VRM Public License 1.0, redistribuição permitida). Licenças em `LICENCAS.txt`.
- Teste no navegador: servir `app/src/main/assets/avatar3d/` (ex.: `python3 -m http.server`) e abrir `index.html`.
- **Personagens próprios em 3D**: `python3 tools/avatar3d/gerar_personagens.py` gera `models/jocta_casual.vrm` e
  `models/luna.vrm` (formas simples, esqueleto humanoide em pose T, rosto com formas de boca e emoções). O app usa o
  modelo do personagem escolhido quando existe (`Avatar3D.modelPath`). Para criar outro personagem, escreva uma função
  como `jocta()`/`luna()` (cores, cabelo, roupa) e chame `build(...)` em `main()`.
