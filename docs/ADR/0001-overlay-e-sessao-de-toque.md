# ADR 0001 — Overlay em foreground service + Activity compacta no toque

**Status:** aceita (Fase 0) · **Data:** 2026-10-06

## Contexto
O personagem precisa flutuar sobre qualquer app e, ao ser tocado, usar microfone e câmera.
- Overlay sobre outros apps exige `SYSTEM_ALERT_WINDOW` e uma janela `TYPE_APPLICATION_OVERLAY`.
- A partir do Android 14, todo foreground service declara um tipo; e um serviço que precise de câmera/microfone **não pode ser iniciado com o app em segundo plano** ([documentação oficial](https://developer.android.com/develop/background-work/services/fgs/changes)).

## Decisão
1. `OverlayService` é um foreground service do tipo `specialUse` (com a justificativa em `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) que só desenha o personagem. Em Android 13 (versão de fábrica do Redmi Note 13 Pro+) inicia sem tipo.
2. O toque abre `TouchSessionActivity`, uma Activity translúcida e compacta. Com ela visível o app está em primeiro plano e pode usar câmera e microfone sem foreground service de câmera/microfone.
3. Arrastar usa coordenadas absolutas (`rawX/rawY`), porque a janela se move junto com o dedo.

## Consequências
- PoC 0.2 mede se isso funciona no HyperOS. A Xiaomi tem a permissão própria "abrir janelas em segundo plano", que pode bloquear a Activity em silêncio; por isso há um watchdog de 3 s que registra `launch_timeout`.
- O modo *live* contínuo enquanto o usuário usa outro app (Fase 1) vai precisar de foreground service `microphone` iniciado **a partir** dessa Activity.
