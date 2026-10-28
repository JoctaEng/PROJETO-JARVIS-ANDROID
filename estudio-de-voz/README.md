# Estúdio de Voz
Teleprompter + gravador + tratamento de áudio + montagem (Android via Capacitor).


## Gerar o APK
1. Aba **Actions** → "Gerar APK" → *Run workflow* (também roda a cada push na `main`).
2. Ao terminar, baixe `EstudioDeVoz.apk` em **Releases** (tag `apk-latest`) ou em *Artifacts*.
3. No celular: abrir o APK e permitir "instalar de fontes desconhecidas".

APK de depuração (debug): serve para uso próprio; não é para a Play Store.

## Desenvolvimento
`www/index.html` é o app inteiro (arquivo único). Após editar: `npx cap sync android`.
