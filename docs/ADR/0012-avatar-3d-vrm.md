# ADR 0012 — Avatar 3D com VRM, three.js e three-vrm numa WebView

- Data: 10/10/2026 (pedidos 55 e 56)
- Estado: aceito (v0.20.0)

## Contexto
O usuário quer um avatar de verdade, de preferência 3D, que fale com a boca sincronizada e mostre emoções.
O app já calcula a boca (visemas) e a emoção de cada frase (ADR implícito na v0.19.0, `LipSync.kt`).

## Opções
1. **Filament/SceneView (nativo)**: leve e rápido, mas morph targets/expressões do VRM, material MToon e
   "spring bones" teriam de ser refeitos à mão.
2. **three.js + @pixiv/three-vrm numa WebView** (escolhida): implementação de referência do formato VRM
   (expressões aa/ih/ou/ee/oh, blink, happy/sad/surprised/angry/relaxed, olhar, cabelo com física), MIT, testável
   num Chromium sem tela. Custo: uma WebView (memória) e ~0,75 MB de código.
3. **Live2D**: licença não isenta "sistemas de avatar"; exige rigging. Descartado.
4. **IA que anima foto (MuseTalk, VASA-1)**: não roda em tempo real no celular. Descartado.

## Decisão
- Página `assets/avatar3d` (código gerado por `tools/avatar3d`), servida pelo próprio app num endereço interno;
  qualquer outro endereço é recusado (sem internet).
- Modelo padrão: exemplo VRM da pixiv (VRM Public License 1.0: uso e redistribuição permitidos, crédito dispensado).
- O app envia `VrmFace.script(...)` (Kotlin puro, com testes) ~30x/s, só quando muda.
- O usuário pode importar o próprio `.vrm` (ex.: feito no VRoid Studio); fica só no celular.
- Se a WebView/WebGL falhar, o personagem 2D continua.

## Consequências
- APK ~11 MB maior (modelo). Memória maior com o 3D ligado (opção pode ser desligada em Meu Euno → Personagem).
- Personagens próprios (Joctã, Luna, Thor) em 3D dependem de criar os modelos VRM.

## Atualização (v0.21.0)
Joctã Casual e Luna ganharam modelos VRM próprios, gerados por código (`tools/avatar3d/gerar_personagens.py`), sem programa de modelagem. Estilo de boneco 3D simples; modelos mais detalhados podem ser feitos depois no VRoid Studio/Blender e colocados em `assets/avatar3d/models/<id>.vrm`.
