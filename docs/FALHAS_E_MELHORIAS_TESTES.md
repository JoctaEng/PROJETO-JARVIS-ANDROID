# Registro de Falhas e Melhorias em Teste Real (Prof. Joctã)

Data de início do relato: 07/10/2026  
Versão testada: Euno Fase 1 (v0.5.0 - Build 23)

---

## 📌 Falha 1: Comportamento do Portal Dimensional / Inatividade ao Falar

* **Relato do Professor:**
  > "Apenas um dos bonecos fica se esconde fica para dentro porque eu falei quando ele tava inativo e o outro eu ainda não testei."
* **Diagnóstico Técnico:**
  1. **Acionamento por voz do usuário**: O `OverlayService` estava observando apenas `app.voice.speaking` (saída de áudio da IA). Quando o professor começa a falar (entrada do microfone / reconhecimento de voz ou hotword), o personagem que está recolhido no portal precisa emergir imediatamente em 3D.
  2. **Consistência entre Personagens**: Verificar se a transição e dimensões do portal dimensional (`CharacterView.kt`) se comportam de maneira idêntica em todos os personagens (`jocta_casual`, `luna` e `thor`), garantindo que nenhum frame trave o personagem para dentro do portal.
* **Solução:** Vincular o evento de escuta/detecção de voz do microfone para disparar `renderer.emergeFromDimension()`.

---

## 📌 Falha 2: Atraso na Resposta em Áudio (TTS) e Opção de Resposta em Texto

* **Relato do Professor:**
  > "Quando eu quero falar em áudio às vezes ele demora muito para responder em áudio e acaba que ele responde muito rápido em texto e o áudio só vem depois de 10 15 minutos ou 15 segundos após o texto já estar dito ou até mais tempo. Então eu quero ter opção de eu falar em áudio mas ele responder em texto."
* **Diagnóstico Técnico:**
  1. **Gargalo no TTS**: Quando configurado para Gemini TTS online ou processamento pesado, a síntese de voz demora para gerar o buffer de áudio, enquanto o texto do LLM chega instantaneamente via streaming.
  2. **Falta de modo misto (Voz -> Texto)**: O usuário precisa de um modo direto onde ele **fala por voz**, mas a IA responde **apenas por texto na tela**, sem travar esperando áudio.
* **Solução:**
  - Adicionar chave em `AppSettings`: `speakReplies` (comutável diretamente na interface com um toque rápido: modo "Responder por voz" vs "Responder só por texto").
  - Otimizar o streaming do TTS para não atrasar quando o modo voz estiver ativo.

---

## 📌 Falha 3: Conversar sem Forçar Abertura da Caixa de Texto (Overlay Flutuante)

* **Relato do Professor:**
  > "Quero poder conversar sem ele precisar abrir uma caixa. Eu posso ter a opção ou não de abrir caixa de texto. Posso tocar nele quando ele tiver ativo sem ter que necessariamente abrir a caixa agora."
* **Diagnóstico Técnico:**
  1. O toque no personagem (`onTap`) hoje dispara incondicionalmente a `ChatActivity`, abrindo a tela inteira de chat e cobrindo os aplicativos que o professor está usando.
  2. Para a experiência real do assistente (JARVIS), o toque simples no personagem deve abrir a **escuta de voz diretamente no overlay**, com o personagem ouvindo, pensando e respondendo (ou mostrando uma legenda flutuante compacta), sem cobrir a tela inteira.
* **Solução:**
  - Adicionar configuração e comportamento: Toque no personagem = Iniciar escuta por voz direta no overlay.
  - A caixa de texto completa (`ChatActivity`) passa a ser opcional (aberta por duplo toque ou botão dedicado de histórico/teclado).

---

## 📌 Falha 4: Modo Trabalho / Cena de Operação (Personagem de Costas no Sistema)

* **Relato do Professor:**
  > "Quando eu pedir para ele fazer algum trabalho, alguma tarefa em algum local, eu quero que o modo dele mude, a cena mude. Ele fica de costas como se ele tivesse de frente para o sistema, para o local onde ele está fazendo o serviço. E quando ele quiser conversar comigo, ele volta, vira a cabeça ou vira o corpo todo para mim."
* **Diagnóstico Técnico:**
  1. Falta de pose/estado de animação de "Trabalhando / Operando o Sistema" (`AnimState.WORKING` / pose de costas com telas holográficas).
  2. O assistente precisa de transição de rotação de corpo/cabeça:
     - Estado de execução de tarefa/ferramenta: vira-se de costas (interagindo com o sistema/computador).
     - Estado de relatório/interação: vira-se de frente de volta para o usuário.
* **Solução:**
  - Criar o estado `AnimState.WORKING` e a pose/rotação do personagem (flip horizontal ou arte de trabalho/costas).
  - Integrar essa transição com a execução de ações e ferramentas (`Toolbox` / MCP).

---

## 📌 Falha 5: Movimentação Desordenada e "Bagunçada" do Corpo ao Falar

* **Relato do Professor:**
  > "As interações deles não estão eficientes e corretas. Quando ele vai tentar falar com a boca, não é apenas a boca que se move, o corpo todo se move, tá uma bagunça. Essas coisas precisam melhorar para que se comece as novas fases."
* **Diagnóstico Técnico:**
  1. **Oscilação corporal excessiva no Canvas**: No `CharacterView.kt`, as funções `breathing` (respiração) e `bobbing` (flutuação) aplicam transformações de escala e translação contínuas no corpo inteiro enquanto os frames de boca alternam, criando uma sensação visual de que o corpo está tremendo e pulando de forma descontrolada ("uma bagunça").
  2. **Isolamento de movimento**: O corpo deve permanecer estável e suave, e apenas a expressão facial e a abertura da boca devem reagir dinamicamente durante a fala.
* **Solução:**
  - Estabilizar a base do personagem em `CharacterView.kt`.
  - Reduzir drasticamente o `bobbing` e `breathing` durante o estado de fala (`AnimState.SPEAKING`), mantendo a animação limpa, focada e natural.
