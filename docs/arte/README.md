# Arte dos personagens

Uma pasta por personagem. O nome de cada pasta é o identificador usado no app (`CharacterCatalog`).

## Arquivos esperados em cada pasta

| Arquivo | Expressão / uso |
|---|---|
| `referencia.png` | Imagem original do personagem (a que serviu de base) |
| `neutro.png` | Repouso |
| `feliz.png` | Feliz / comemorando |
| `pensativo.png` | Pensando na resposta |
| `falando.png` | Boca aberta (alterna com `neutro.png` enquanto fala) |
| `ouvindo.png` | Atento, escutando |
| `surpreso.png` | Surpreso |
| `preocupado.png` | Preocupado / erro |
| `dormindo.png` | Olhos fechados, em repouso longo |

**Formato:** PNG ou JPG, quadrado (512×512 ou maior), **mesmo tamanho e mesmo enquadramento** (busto, centralizado), mesma roupa e mesmas cores em todas as expressões. O fundo não precisa ser transparente de verdade: o xadrez "falso" que o Gemini desenha, ou um fundo branco liso, é removido automaticamente.

**Expressões que faltarem** usam uma substituta: `ouvindo` → `neutro`; `preocupado` → `pensativo` → `neutro`; as demais → `neutro`. Só `neutro` é indispensável.

## Como a arte chega ao app

```bash
pip install "rembg[cpu]" pillow scipy
python tools/arte/preparar_arte.py <personagem>
```

O script recorta o fundo, aplica o mesmo enquadramento a todas as expressões e grava `app/src/main/res/drawable-nodpi/arte_<personagem>_<expressao>.webp` (512×512, ~35 KB). Depois, registre o personagem em `app/.../character/CharacterArt.kt`. No app: boca alterna `falando`/expressão base enquanto fala; `dormindo` também serve de piscada a cada ~4 s em repouso.

| Personagem | Arte no app |
|---|---|
| jocta_casual | ✅ neutro, feliz, pensativo, falando, surpreso, dormindo (faltam ouvindo, preocupado) |
| demais | ⏳ ficam fora da lista de escolha até a arte chegar |

**Prompt sugerido** (troque só a palavra entre colchetes):

> Mesmo personagem da imagem de referência, estilo 3D cinematográfico fofo, mesmo rosto, roupa e cores. Enquadramento de busto, centralizado, fundo transparente, 1024×1024. Expressão: [feliz].

## Como enviar pelo celular
No GitHub, entre na pasta do personagem → **Add file → Upload files** → escolha as imagens → **Commit changes**. Use exatamente os nomes de arquivo da tabela.
