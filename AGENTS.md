# Euno — instruções para agentes de IA (Antigravity, Claude Code, outros)

**Antes de qualquer coisa leia `docs/HANDOFF.md`**: contexto completo, estado atual, decisões que não se desfazem, próximos passos e a revisão mais recente (seção 12). No fim da sessão, acrescente uma linha ao Diário (seção 11).

Regras curtas:
- Português do Brasil, mensagens curtas e honestas. Diga o que foi verificado e o que não foi; **CI verde não é "funciona no aparelho"**.
- O repositório é **público**: nunca commitar chaves, senhas, frases secretas, nome completo, empregadores ou outros dados pessoais do usuário.
- O app Android só compila no CI (GitHub Actions) ou no Android Studio com NDK 29 (passos em `HANDOFF.md`, seção 6). Lógica pura vai em módulos Kotlin/JVM com teste: `./gradlew -Pjarvis.jvmOnly=true test`.
- Versão e fase em `gradle.properties`. Decisões em `docs/ADR/`. Arte: `docs/arte/README.md` e `python tools/arte/verificar_alinhamento.py <id>` antes de aceitar qualquer arte nova.
- Toda ação do assistente passa por `ToolGateway` (autonomia, confirmação, histórico). Não abra PR sem o usuário pedir.
- Os créditos do usuário são limitados: não refaça o que já está feito, não escreva documentos de análise sem pedido, e prefira mudanças pequenas e testadas.
