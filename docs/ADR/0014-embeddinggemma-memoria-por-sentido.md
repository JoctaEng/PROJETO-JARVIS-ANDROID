# ADR 0014 — Memória por sentido com EmbeddingGemma 2 via LiteRT-LM

- Data: 12/10/2026 (pedido 82)
- Estado: aceito (v0.30.0)

## Contexto
O usuário quer que o Euno encontre memórias pelo significado da frase, não apenas por correspondência exata de palavras.
Por exemplo: "quando é o aniversário da minha mãe?" deve recuperar a memória "o aniversário dela é em 3 de maio".
O app já utiliza LiteRT-LM (`litertlm-android:0.17.1`) para o modelo local Gemma do chat.

## Opções
1. **API online de embeddings (Google AI / OpenAI)**:
   - Depende de conexão com a internet e gasta cota/chave. Não funciona offline. Descartada para memória privada.
2. **Modelo local Sentence-Transformers via ONNX / sherpa**:
   - Exigiria nova dependência nativa pesada de runtime e gerenciamento adicional de C++.
3. **EmbeddingGemma 2 no LiteRT-LM 0.17.1 (Google DeepMind, Apache 2.0)** (escolhida):
   - A biblioteca `litertlm-android:0.17.1` já possui `com.google.ai.edge.litertlm.EmbeddingEngine` nativo em C++/JNI.
   - Modelo oficial da comunidade LiteRT: `litert-community/embeddinggemma-2-text-270m-litert-lm`.
   - Tamanho quantizado compacto: ~165 MB (`164.626.432` bytes), SHA-256 `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb`.
   - Suporta Matryoshka com truncamento para 256 dimensões e normalização L2.

## Decisão
- Criar o módulo puro JVM `:mind:embed` contendo:
  - `TextEmbedder` com suporte a papéis `EmbedRole.QUERY` e `EmbedRole.DOCUMENT`.
  - Prefixos oficiais para busca:
    - Consulta: `task: search result | query: {query}`
    - Documento: `title: none | text: {content}`
  - Álgebra vetorial: truncamento para 256d, normalização L2, similaridade de cosseno com desempate estável por id e fallback léxico (`KeywordScore`).
  - Armazenamento de vetores em arquivo JSON privado (`filesDir/memory/embeddings.json`), com descarte automático ao mudar modelo ou dimensões, e reaproveitamento por `textHash`.
- No módulo `:app`:
  - `EmbeddingRuntime`: gerencia o `EmbeddingEngine` via LiteRT-LM em CPU, descarregando após 60 s ocioso ou em memória crítica (`onTrimMemory`).
  - `EmbeddingDownload` e `EmbeddingDownloadService`: download em primeiro plano com retomada (`Range`), notificação própria (ID 4208, canal `euno_embedding`), espera de Wi-Fi e conferência de SHA-256.
  - Opção **desligada por padrão** (`settings.semanticMemory = false`).
  - Quando desligada, mantém exatamente o comportamento da v0.29.1 (todas as memórias injetadas no prompt).
  - Quando ligada, seleciona as 8 memórias mais relevantes com teto de ~900 caracteres.
  - No modo privado, nada é gravado no índice.
  - Apagar ou editar memória atualiza ou remove imediatamente o vetor correspondente.

## Consequências
- Aumento de apenas ~165 MB de download opcional quando o usuário desejar ativar a busca por sentido.
- Zero dependências binárias adicionais (aproveita o LiteRT-LM 0.17.1 já presente).
- Tolerância a falhas: timeout de 3 segundos com recuo imediato para busca por palavras sem travar a conversa.
