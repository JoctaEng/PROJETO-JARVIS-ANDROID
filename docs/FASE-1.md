# Fase 1 — MVP 1: "Ele mora no meu celular" (app Euno)

## Baixar
GitHub → **Actions → Android** → execução mais recente → artefato **`Euno-fase1-v<versão>-build<N>`**. Enquanto não houver chave de assinatura fixa, desinstale a versão anterior antes de instalar a nova.

## O que este APK faz

| Função | Como usar |
|---|---|
| **Escolher personagem** | Meu Euno → Personagem: 12 opções (9 da folha de personagens, sem o robô, + 3 do Joctã digital). O nome pode ser trocado. A personalidade de cada um vai para o cérebro. |
| **Conversar por voz** | Toque no personagem: ele já começa a ouvir. Responde falando frase a frase e volta a ouvir (conversa contínua). Silêncio encerra a escuta. |
| **Conversar por texto** | Na conversa, escreva no campo de texto. Respostas escritas não são faladas. |
| **Cérebro online** | Meu Euno → Cérebro: escolha Gemini, OpenAI, OpenRouter ou seu servidor (Ollama/vLLM), cole a chave, toque em **Buscar modelos**, escolha um e toque em **Testar conexão**. |
| **Cérebro no celular** | Meu Euno → Cérebro → **Baixar Qwen3-4B** (2,4 GB, só Wi-Fi) ou **Importar** um `.gguf`/`.litertlm`; depois **Testar IA do celular**. Sem internet, ele é usado automaticamente (e avisa). Motor: llama.cpp (ADR 0009). |
| **Memória** | Diga "lembre que…" e "esqueça isto" (ou "esqueça que…"). Veja e apague em Meu Euno → Minha Memória. |
| **Posição** | Livre (padrão: fica onde você soltar) ou grudada nas bordas. Tamanho por pinça com dois dedos ou pelo controle em Meu Euno. |
| **Voz** | Prefere o motor "Serviços de fala do Google" quando instalado; escolha de voz, velocidade e tom. |
| **Modo Privado** | Usa só o cérebro do celular e não memoriza nada. |

## Onde conseguir uma chave (exemplo: Gemini)
1. Entre em aistudio.google.com com sua conta Google e gere uma **API key**.
2. No app: Meu Euno → Cérebro → Google Gemini → cole a chave → **Salvar chave** → **Buscar modelos** → escolha um modelo "flash" **da lista** → **Testar conexão**.
   Não digite o nome comercial ("Gemini 3.8 flash"): a API só aceita o identificador técnico (ex.: `gemini-2.5-flash`) e responde `unexpected model name format`.

A chave fica cifrada no Android Keystore e nunca vai para o código ou para o GitHub.

## Critérios de saída da Fase 1 (roteiro, seção 12)
- [ ] Toque → primeira reação visual < 100 ms (medida no Diagnóstico, PoC 0.5)
- [ ] Fim da fala → primeira palavra falada: online < 2 s; offline < 4 s
- [ ] 7 dias de uso diário sem travar ou sumir
- [ ] Nenhuma ação executada sem registro (ainda não há ações em outros apps nesta versão)

## Ainda não está nesta versão
- **Arte dos personagens:** só o Joctã Casual tem arte; os demais entram na escolha quando a arte chegar (docs/arte).
- **Voz natural de verdade:** depende do motor instalado; vozes neurais online ficam para o MVP 2 (roteiro: "TTS avançado").
- **Agenda, arquivos, Drive, ações em apps:** MVP 2.
- **Desviar sozinho de botões:** MVP 2 (precisa do serviço de acessibilidade).
