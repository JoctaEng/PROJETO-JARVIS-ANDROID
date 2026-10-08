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

## Diagnóstico do relatório real (v0.6.2, 08/10/2026)
- A assinatura bateu: a atualização por cima funcionou sem desinstalar.
- **Modelo local recarregado a cada uso (1ª palavra em ~58–72 s)**: `onTrimMemory` descarregava o modelo em qualquer nível ≥ RUNNING_LOW, inclusive UI_HIDDEN (ao fechar a conversa). Com o modelo já na memória a 1ª palavra saiu em ~15 s. Corrigido na 0.6.3 (só descarrega com memória crítica) e o modelo passa a ser carregado ao abrir a conversa.
- **Kokoro sintetizava 3,3 s de áudio em ~8,5–11 s (2,6× mais lento que o tempo real)**: por isso as pausas longas entre trechos. Na 0.6.3 o Automático marca o Kokoro como lento e usa a voz do Android (flag `kokoroLento` no relatório); escolher Kokoro manualmente em Meu Euno → Voz ainda o usa.
- Nova linha `llama:` no registro: tokens do prompt, quantos vieram do cache, tempo de leitura do prompt, 1º token, tokens gerados e tempo, e a carga do modelo.

## Segundo relatório (v0.6.2, cérebro Gemini online)
- Cérebro online: 1ª palavra em ~1 s e resposta inteira em ~1,0–1,3 s. O modelo online não é o gargalo.
- O atraso para falar vinha 100% da voz: o Gemini TTS falhava com `resposta sem áudio` (HTTP 200, mas o áudio não estava onde o código procurava) e caía no Kokoro, que levava 5–36 s por frase.
- v0.6.4: a leitura do Gemini TTS passou a procurar o áudio em qualquer parte da resposta, tenta `generateContent` e `interactions` e, se ainda falhar, o relatório mostra o formato real da resposta (`resposta={...}`), sem o áudio. Nas linhas `voz:` o motor aparece como `gemini/generateContent` ou `gemini/interactions`.
