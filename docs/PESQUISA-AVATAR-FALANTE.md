# Avatar falante: sincronia labial e emoções (pesquisa de 10/10/2026)

Pedido 54: "personagens como verdadeiros avatares, que falem e expressem emoções sincronizados com as palavras".

## O que existe de melhor (e o que serve para o Euno)

| Caminho | Como funciona | Serve agora? |
|---|---|---|
| **Visemas (formatos de boca) + máquina de estados 2D** — padrão do Duolingo com **Rive** | cada personagem tem 9–20 bocas desenhadas; o app troca a boca no tempo de cada som; expressão do rosto roda junto, em camada separada | É o **melhor resultado em 2D**. Precisa desenhar as bocas de cada personagem (arte nova). Adotado em parte: a lógica de visemas já está pronta; falta a arte. |
| **Visemas do Azure** (evento `VisemeReceived`) | a própria voz do Azure diz qual boca usar e quando (ms) | Só pelo **Speech SDK**, não pela API REST que o Euno usa (resposta da Microsoft de 2023; não achei documento novo dizendo o contrário). Fica para depois: exige trocar a forma de chamar o Azure. |
| **Rhubarb Lip Sync** | analisa o áudio e gera as bocas A–H/X | Programa de computador; para português só o reconhecedor "fonético", menos preciso. **Usamos o mesmo conjunto de bocas** (A–H, X). |
| **Live2D Cubism** | modelo 2D "articulado" (olhos, boca, cabeça) | Bonito, mas exige rigging de cada personagem e a licença **não isenta "sistemas de avatar"** (Expandable Application). Não adotado. |
| **IA que anima uma foto** (MuseTalk, LivePortrait, VASA-1) | gera vídeo do rosto falando a partir de uma imagem | Resultados ótimos, mas em servidor com GPU; **não achei nenhum rodando em tempo real no celular**. Não adotado. |

## O que a v0.19.0 implementa (funciona com todas as vozes)

1. **Boca pelo texto (pt-BR) + tempo pelo áudio real** (`presence/expression/LipSync.kt`):
   - cada letra vira um visema (a→D aberta, é→C, i→B esticada, o→E redonda, u→F bico, m/b/p→A fechada, f/v→G, l/lh→H; pontuação = pausa);
   - o **volume do áudio** (RMS a cada 20 ms) acha onde há voz e controla quanto a boca abre; silêncio fecha a boca;
   - a boca segue a **posição real do áudio tocando** (`AudioTrack.playbackHeadPosition`), ~30 vezes por segundo (Azure, Gemini, Piper, Kokoro);
   - voz do Android: segue as **palavras anunciadas pelo motor** (`onRangeStart`); se o motor não anunciar, volta à boca automática.
2. **Rosto + boca em camadas** (Joctã Casual, cuja arte é alinhada): o rosto fica na **emoção da frase** (feliz, surpreso, preocupado, pensativo) e só a **boca** se mexe por cima, com abertura e largura do visema e borda suave. Luna e Thor (arte não alinhada) trocam o quadro inteiro, agora no tempo certo do áudio.
3. **Emoção por frase**: a expressão muda junto com a frase falada ("Que bom!" → feliz; "Infelizmente…" → preocupado; "Nossa…" → surpreso; "Deixa eu ver…" → pensativo); sem pista, fica a emoção da resposta.

## Próximos passos (precisam de decisão/arte)
- **Arte de bocas por personagem** (9 bocas A–H/X, mesma pose) → boca de desenho animado de verdade; com isso, migrar para **Rive** (máquina de estados com camadas de boca e rosto, como o Duolingo).
- **Visemas do Azure pelo Speech SDK** (tempo exato de cada som da voz do Azure).
- Olhos/sobrancelhas animados por emoção (exige arte em camadas).

## Fontes
- Microsoft Learn, "Get facial position with viseme": https://learn.microsoft.com/azure/ai-services/speech-service/how-to-speech-synthesis-viseme
- Microsoft Q&A, "Can I get viseme data via the Text-to-Speech REST API?": https://learn.microsoft.com/en-us/answers/questions/1190743/can-i-get-viseme-data-via-the-text-to-speech-rest
- Duolingo, "World character visemes" (Rive): https://blog.duolingo.com/world-character-visemes
- Rhubarb Lip Sync (formas A–H, X; reconhecedor fonético para outras línguas): https://github.com/DanielSWolf/rhubarb-lip-sync
- Android, `UtteranceProgressListener.onRangeStart`: https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener
- Live2D, licença do SDK: https://www.live2d.com/en/sdk/license/
- MuseTalk (arXiv 2410.10122): https://arxiv.org/abs/2410.10122 ; VASA-1 (Microsoft Research): https://www.microsoft.com/en-us/research/project/vasa-1/
