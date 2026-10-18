# Contrato: modelos do Euno lidos pelo Joca Finanças (somente leitura) — versão 1

Desde o Euno **0.33.0**. Fonte no código: `mind/models/.../SharedModels.kt` (constantes) e `app/.../share/SharedModelsProvider.kt`.
Mudar qualquer nome aqui exige mudar o código e o Joca juntos (e subir `versao_contrato`).

## Para que serve
O Joca Finanças pode usar um modelo offline que o usuário **já baixou no Euno**, sem baixar de novo (2,6 a 3,7 GB).
O Euno só **mostra** a lista e **entrega o arquivo para leitura**. Ninguém de fora apaga, troca ou grava nada no Euno.

## Porta
`ContentProvider` exportado do Euno:
- pacote do Euno: `com.joctaeng.jarvis`
- autoridade: `com.joctaeng.jarvis.modelos`

### 1. Lista dos modelos baixados
`content://com.joctaeng.jarvis.modelos/modelos` — `ContentResolver.query(uri, null, null, null, null)`.
Uma linha por arquivo **completo** do catálogo do Euno (arquivos `.download` em andamento e arquivos importados fora do catálogo não aparecem).

| coluna | tipo | exemplo | observação |
|---|---|---|---|
| `id` | texto | `gemma-4-e2b` | id do catálogo do Euno (estável) |
| `nome` | texto | `Gemma 4 E2B` | nome para mostrar |
| `arquivo` | texto | `gemma-4-E2B-it.litertlm` | nome original do Hugging Face |
| `motor` | texto | `litertlm` ou `gguf` | `litertlm` = LiteRT-LM; `gguf` = llama.cpp |
| `tamanho_bytes` | inteiro (long) | `2583000000` | tamanho real do arquivo no aparelho |
| `sha256` | texto ou nulo | `2d07…86fb` | SHA-256 já conferido no Euno; nulo se o usuário ainda não conferiu |
| `capacidades` | texto | `texto,ferramentas,visao,audio` | separadas por vírgula: `texto`, `ferramentas`, `visao`, `audio`, `embeddings`, `fala_texto` (do catálogo; o arquivo confirma ao carregar) |
| `ativo` | inteiro | `1` | 1 = é o cérebro do celular ativo no Euno agora; 0 = não |
| `licenca` | texto | `Apache 2.0` | |
| `versao_contrato` | inteiro | `1` | versão deste contrato |

### 2. Arquivo do modelo (somente leitura)
`content://com.joctaeng.jarvis.modelos/arquivo/<id>` (ex.: `.../arquivo/gemma-4-e2b`) —
`ContentResolver.openFileDescriptor(uri, "r")`. Qualquer modo diferente de `"r"` é recusado (`SecurityException`).
Modelo não baixado → `FileNotFoundException`.

**Como o Joca usa o arquivo (caminho garantido):** copiar do descritor para a pasta privada do Joca
(`FileInputStream(pfd.fileDescriptor)` → arquivo temporário → renomear), conferir o tamanho (`tamanho_bytes`) e, se vier,
o `sha256`. Copiar no aparelho é bem mais rápido que baixar, mas ocupa o espaço de novo.
**Sem copiar (não testado):** abrir o LiteRT-LM direto em `/proc/self/fd/<fd>` enquanto o descritor estiver aberto.
Não foi verificado se o LiteRT-LM 0.17.1 aceita esse caminho; trate como experimento e caia para a cópia se falhar.

### 3. Operações recusadas
`insert`, `update` e `delete` lançam `UnsupportedOperationException`.

## Quem pode ler
- Só o pacote `com.aistudio.jocafinancas.wxyzkp`. O Euno descobre o pacote pelo **UID que o Android informa** na chamada
  (`Binder.getCallingUid()` → `getPackagesForUid`), não pelo que o app declara. Outro app recebe `SecurityException`.
- O usuário pode desligar em Euno → Meu Euno → Cérebro → Catálogo de modelos → **Joca Finanças** (ligado por padrão).
  Desligado → `SecurityException` com a mensagem "o usuário desligou o compartilhamento de modelos".

## O que o Joca precisa declarar (Android 11+)
No `AndroidManifest.xml` do Joca, para enxergar o provedor do Euno:
```xml
<queries>
    <provider android:authorities="com.joctaeng.jarvis.modelos" />
</queries>
```
Nenhuma permissão nova é necessária.

## Exemplo (Kotlin, no Joca)
```kotlin
val uri = Uri.parse("content://com.joctaeng.jarvis.modelos/modelos")
val modelos = runCatching {
    contentResolver.query(uri, null, null, null, null)?.use { c ->
        buildList {
            while (c.moveToNext()) add(
                Triple(
                    c.getString(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("arquivo")),
                    c.getInt(c.getColumnIndexOrThrow("ativo")) == 1,
                ),
            )
        }
    }.orEmpty()
}.getOrDefault(emptyList()) // Euno ausente, antigo (< 0.33.0) ou compartilhamento desligado → lista vazia
```

## Por que é seguro
- Só leitura: o Joca não muda nada no Euno.
- Dados expostos: nomes, tamanhos e hashes de arquivos públicos do Hugging Face e qual está ativo. **Nenhuma** chave,
  token, memória, conversa ou dado pessoal passa por aqui.
- O nome de pacote é único no aparelho: outro app só poderia se passar pelo Joca se o Joca não estivesse instalado
  (risco aceito: o que vazaria são arquivos públicos). Não há conferência do certificado do Joca porque ele ainda é
  assinado com a chave de teste do AI Studio; quando o Joca tiver a chave definitiva, vale fixar a impressão digital aqui.

## Não verificado
- Compilado só no CI; **não testado no aparelho** com o Joca (o Joca precisa da versão que lê este contrato).
