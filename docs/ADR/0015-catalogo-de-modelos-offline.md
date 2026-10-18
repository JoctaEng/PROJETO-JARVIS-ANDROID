# ADR 0015 — Catálogo de modelos offline com troca por "Salvar"

- Data: 18/10/2026 (pedido 87)
- Estado: aceito (v0.33.0)

## Contexto
O usuário quer tocar, baixar, usar e trocar modelos offline para testar cada um, com ênfase em ferramentas e leitura de
imagem, áudio, PDF e documentos. Até a 0.32 o Euno só baixava o Gemma 4 E2B e a escolha do modelo aplicava na hora.

## Decisões
1. **Catálogo versionado** em JSON no módulo puro `:mind:models` (`catalogo-modelos.json`, com testes). Só entra o que foi
   conferido (repositório, arquivo, tamanho, licença). Tamanho aproximado = do cartão do modelo; o download confere o
   real pelo servidor e o SHA-256 pela API do Hugging Face (`ModelIntegrity`), guardado ao lado do arquivo (`.sha256`).
2. **Marcação pelo aparelho** (`DeviceFit`): Recomendado / Funciona / Pesado demais pela RAM total, espaço livre e versão
   do LiteRT-LM exigida.
3. **Troca só com Salvar** (requisito do usuário): escolher não aplica; Salvar libera o modelo anterior, abre o novo,
   gera uma frase de teste e só então grava; a confirmação mostra o que o motor carregou (GPU/CPU, imagem, áudio,
   ferramentas nativas). Falhou → mantém o anterior. Sair com alteração pendente pergunta Salvar/Descartar.
4. **Capacidades lidas do arquivo** (`Capabilities` do LiteRT-LM 0.17.1: `inputModalities`, `supportsFunctionCalling`).
5. **Ferramentas nativas**: o bloco `<tools>` sai do prompt e as mesmas ferramentas são registradas como `OpenApiTool`
   com `automaticToolCalling = false`; a chamada volta como `LlmChunk.ToolRequest` e é executada pelo **ToolGateway**
   (política, confirmação e histórico iguais). Se o motor recusar, volta ao `<tool_call>` de texto.
6. **Anexos**: imagem e áudio vão ao modelo do celular que os lê (`Content.ImageFile/AudioFile`); PDF, DOCX, TXT e CSV
   viram texto no aparelho (`DocumentText`, sem biblioteca nova); PDF sem texto vira imagens das 3 primeiras páginas
   (`PdfRenderer`). Os cérebros online do Euno recebem só texto: com imagem/áudio e sem modelo que leia, o Euno avisa.
7. **LiteRT-LM fica na 0.17.1**: a 0.18.0 existe (06/10), mas houve defeitos relatados (pedido 80) e daqui não dá para
   compilar nem testar; nada do catálogo atual exige a 0.18. Quem exige (EmbeddingGemma 2 740M) ficou fora.
8. **Joca Finanças** lê a lista e os arquivos por um `ContentProvider` somente leitura (`docs/CONTRATO-MODELOS-JOCA.md`).
   O Euno não tem servidor MCP (só cliente), por isso não foi pela porta MCP.

## Fora do catálogo (não confirmados para Android/LiteRT-LM 0.17.1)
Gemma 4 12B (arquivos ~6 GB para computador/web), Gemma 4 26B-A4B (computador/web, só texto), Qwen3.5-4B (nome do
arquivo só visto em espelho), Agents-A1-4B e MiniCPM-V 4.7 (nada encontrado em `.litertlm`), Nemotron Nano 9B v2 (sem
`.litertlm`), Parakeet TDT 0.6B v3 (repositório existe, mas o arquivo e a API de fala→texto no litertlm-android não foram
confirmados; o reconhecedor do Android continua), EmbeddingGemma 2 740M (exige 0.18).

## Consequências
- Um download por vez (arquivos de gigabytes). Pausar guarda o pedaço; Retomar continua por `Range`.
- Só um modelo na memória: a troca descarrega o anterior antes de abrir o novo.
- Nada disto foi testado no aparelho; o app compila só no CI.
