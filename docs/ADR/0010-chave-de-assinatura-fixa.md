# ADR 0010 — Chave de assinatura fixa a partir de um segredo

**Status:** aceita (Fase 1) · **Data:** 2026-10-07

## Contexto
Cada execução do CI assinava o APK com uma chave de debug nova. O Android recusa atualizar um app assinado com outra chave, então cada versão exigia desinstalar e perder configurações, chave do Gemini, memórias e o modelo local (2,4 GB). O repositório é **público**: a keystore não pode ficar no git, senão qualquer pessoa assinaria um APK que instala por cima do Euno.

## Decisão
- Um único segredo do GitHub, `EUNO_SIGNING_SEED` (frase longa, só o dono conhece).
- `tools/assinatura/gerar_keystore.py` deriva dela uma chave EC P-256 (HKDF-SHA256) e um certificado com datas fixas e assinatura ECDSA determinística: **a mesma frase gera sempre o mesmo certificado** (verificado: duas gerações com senhas diferentes deram o mesmo SHA-256).
- O CI gera a keystore em `$RUNNER_TEMP` com senha aleatória mascarada e o Gradle assina debug e release com ela. Sem o segredo, cai na chave temporária e avisa no log.

## Consequências
- Perder a frase = não conseguir mais atualizar por cima (precisaria desinstalar uma vez). Guarde-a num gerenciador de senhas.
- A primeira instalação com a chave nova ainda exige desinstalar a versão antiga; as seguintes atualizam por cima.
- Quem tiver a frase pode assinar APKs do Euno: nunca a coloque em código, issue ou conversa.
