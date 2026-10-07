# ADR 0009 — Cérebro no celular com llama.cpp (modelos GGUF)

**Status:** aceita (Fase 1) · **Data:** 2026-10-07 · **Revisável**

## Contexto
O combinado é ter dois cérebros: **API online** e **IA local de 2,5 a 4 bilhões de parâmetros**. O Euno só tinha o LiteRT-LM (arquivos `.litertlm`), que exige importar o modelo à mão pelo Diagnóstico. O usuário já usa no mesmo aparelho (Redmi Note 13 Pro+, 12 GB) o **Qwen3-4B Q4_K_M** (`.gguf`, ~2,4 GB, Apache 2.0) no EduMath, rodando com llama.cpp por uma ponte JNI própria. O LiteRT-LM não lê GGUF.

## Decisão
- Novo módulo `mind:provider-llama`: llama.cpp **v0.5.0** (ggml-org, MIT) + a ponte JNI do EduMath (mesmo autor), só arm64-v8a, dotprod+fp16 (sem exigir i8mm).
- O motor é escolhido pela extensão: `.gguf` → llama.cpp; `.litertlm` → LiteRT-LM (continua).
- Modelo recomendado: `Qwen/Qwen3-4B-GGUF`, arquivo `Qwen3-4B-Q4_K_M.gguf`, revisão fixada `a9a60d0…` (a mesma do EduMath). Baixado pelo **DownloadManager** (retoma, só Wi-Fi, continua com o app fechado) ou importado de um arquivo.
- Parâmetros medidos no EduMath no mesmo aparelho: contexto 4096, 2 threads para gerar e 4 para ler o prompt, lote 512, "modo pensar" do Qwen3 desligado, cache de prefixo ligado.
- A hora atual passou para a última linha do prompt de sistema, para não invalidar o cache de prefixo a cada minuto.

## Consequências
- Velocidade esperada (medições do EduMath): ~3–7 tokens/s na geração; a primeira resposta é a mais lenta (lê todo o prompt), as seguintes reaproveitam o começo.
- O Android não deixa o Euno ler o arquivo guardado pelo EduMath (`Android/data` de outro app). Reaproveitar exige copiar o `.gguf` para a pasta Download e importar; caso contrário, baixar de novo (dois arquivos de 2,4 GB).
- O build do APK passa a precisar do NDK 29 e do CMake 3.31.6 (instalados pelo Gradle no CI) e da fonte do llama.cpp, clonada pelo CI (fora do git).
- Sem a biblioteca nativa ou sem dotprod, o provedor se declara indisponível em vez de travar o app.
