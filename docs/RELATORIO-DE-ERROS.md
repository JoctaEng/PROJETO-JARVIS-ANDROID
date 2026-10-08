# Relatório de erros do Euno (v0.6.2)

## Como enviar
Tela inicial do Euno → **Enviar relatório de erros** → escolha onde enviar (WhatsApp, e-mail, ou "Copiar" para colar na conversa).
O relatório não inclui chaves de API nem o texto das conversas.

## O que ele contém
- Versão, aparelho, Android, memória, bateria e a **assinatura do APK (SHA-256)**: deve ser sempre `52:91:A0:70:…:59:5E`. Se mudar, o Android vai exigir desinstalar.
- **Eventos, erros e quedas** (arquivo `files/logs/euno-eventos.log`, com rotação, no máximo ~800 KB):
  - voz: por frase, o motor (gemini/kokoro/android), tempo de síntese, duração do áudio, quanto a frase esperou e a pausa antes dela; linhas `PAUSA entre frases` quando a pausa passa de 1,2 s; falhas do Gemini (que passa 2 min "de castigo" depois de falhar);
  - conversa: cada turno (tamanho do prompt, tempo até a 1ª palavra e total), falhas e trocas de cérebro;
  - escuta: falhas do reconhecimento e fala reconhecida;
  - sistema: quedas do app (com a pilha de chamadas) e, na abertura seguinte, por que o Android encerrou o processo anterior (pouca memória, ANR, queda nativa do motor de IA...).
- Medições da Fase 0 (`poc01`–`poc05`) e as últimas ações das ferramentas (`audit.jsonl`).

## Proteção contra reinstalar
O CI só publica o APK se a assinatura for igual à de `tools/assinatura/impressao_esperada.txt` e se o segredo `EUNO_SIGNING_SEED` existir. O código de versão cresce a cada build. Mudar essa impressão só com decisão explícita.
