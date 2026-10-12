# Fase 3 — Autonomia e Ferramentas (v0.30 a v0.40)

## v0.30.0 — Memória por Sentido (EmbeddingGemma 2) — 12/10/2026

### 1. Resumo da entrega
- Integração do modelo **EmbeddingGemma 2** (Google DeepMind, Apache 2.0) via LiteRT-LM (`litertlm-android:0.17.1`).
- Módulo puro Kotlin/JVM `:mind:embed` com cálculos vetoriais, normalização L2, truncamento Matryoshka para 256 dimensões, persistência em JSON (`embeddings.json`) e fallback léxico.
- Recuperação semântica (`MemoryRecall`):
  - **Desligada por padrão** (`semanticMemory = false`), mantendo comportamento idêntico à v0.29.1.
  - Quando ativada pelo usuário: busca os top-8 itens mais relevantes semanticamente para o prompt do cérebro (com limite de ~900 caracteres).
- Download em primeiro plano (`EmbeddingDownload` e `EmbeddingDownloadService`, canal `euno_embedding`, ID 4208) com retomada e conferência de integridade SHA-256.
- Interface em Ajustes > "Minha Memória": chave liga/desliga, botão Baixar (~157 MB), Conferir arquivo (SHA-256), Apagar modelo, e indicação de memórias indexadas.
- Diagnóstico (`ReportCollector`): métricas de memória por sentido (status, modelo presente, contagem do índice, tempo de carga e tempo médio de consulta).

### 2. Dados Oficiais do Modelo (Tarefa 0)
- **ID Hugging Face:** `litert-community/embeddinggemma-2-text-270m-litert-lm`
- **Arquivo:** `embeddinggemma-2-text-270m.litertlm`
- **Tamanho:** 164.626.432 bytes (~157 MB)
- **SHA-256:** `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb`
- **Prefixos Oficiais de Tarefa (Search):**
  - Consulta: `task: search result | query: {query}`
  - Documento: `title: none | text: {content}`
- **Dimensões:** 256d (Matryoshka, L2 normalizado).
- **Licença:** Apache 2.0.
