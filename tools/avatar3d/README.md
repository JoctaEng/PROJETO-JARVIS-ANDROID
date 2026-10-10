# Avatar 3D (VRM)

Página que desenha o avatar 3D do Euno com three.js + @pixiv/three-vrm, usada pelo app numa WebView transparente
(`app/.../character/Avatar3D.kt`). Tudo fica dentro do APK (`app/src/main/assets/avatar3d/`); nada é baixado da internet.

- `src/avatar.js` → gera `app/src/main/assets/avatar3d/avatar.js` com `npm install && npm run build` (esbuild, IIFE).
- Interface `window.Avatar`: `setMode`, `setMouth({aa,ih,ou,ee,oh})`, `setEmotion(nome, peso)`, `lookAt`, `setFraming`, `setPaused`, `status`, `value`.
- O app manda os comandos gerados por `presence/expression/VrmFace.kt` (testado na JVM).
- Modelo padrão: `models/avatar.vrm` (pixiv, VRM Public License 1.0, redistribuição permitida). Licenças em `LICENCAS.txt`.
- Teste no navegador: servir `app/src/main/assets/avatar3d/` (ex.: `python3 -m http.server`) e abrir `index.html`.
