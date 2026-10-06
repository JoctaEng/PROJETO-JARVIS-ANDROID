# Integração de Arte de Personagens — Fase 2

## Status
- ✅ Pasta `docs/arte/` criada com 12 subdiretórios (um por personagem)
- ✅ Sistema de renderização preparado para carregar PNGs
- ⏳ Aguardando upload de arte

## Próximos Passos (quando as imagens forem enviadas)

### 1. Mover PNGs para o app Android
```bash
# Copiar imagens de docs/arte para app/src/main/res/drawable
for char in joca luna thor nina selene rex maya kiko astra jocta_estrategista jocta_casual jocta_jovem; do
  for expr in referencia neutro feliz pensativo falando ouvindo surpreso preocupado dormindo; do
    src="docs/arte/$char/$expr.png"
    dst="app/src/main/res/drawable/character_${char}_${expr}.png"
    if [ -f "$src" ]; then
      cp "$src" "$dst"
    fi
  done
done
```

### 2. Mapeamento Automático de Emoções → Expressões
O sistema já mapeia automaticamente em `CharacterExpressions.kt`:

- `NEUTRAL` → neutro
- `HAPPY` / `CELEBRATING` → feliz
- `THINKING` → pensativo
- `SURPRISED` → surpreso
- `CONCERNED` → preocupado
- Estado `SPEAKING` → falando (boca aberta)
- Estado `LISTENING` → ouvindo
- Estado `SLEEPING` → dormindo

### 3. Integração com UI
O código está pronto em:
- `app/src/main/kotlin/com/joctaeng/jarvis/character/ImageCharacterRenderer.kt`
- `app/src/main/kotlin/com/joctaeng/jarvis/character/CharacterExpressions.kt`

Usa fallback automático para o placeholder se a imagem não for encontrada.

### 4. Build e Release
```bash
./gradlew assembleRelease
# APK será: Euno-fase1-v0.3.0-build<run>.apk
```

## Convenções de Nomenclatura
- Nomes de arquivo: `<expression>.png` (ex: `neutro.png`, `falando.png`)
- Recurso no Android: `character_<id>_<expression>` (ex: `character_joca_neutro`)
- Dimensões: 512×512+ recomendado, PNG com fundo transparente

## Checklist de Integração
- [ ] Imagens PNG recebidas em `docs/arte/`
- [ ] Imagens copiadas para `app/src/main/res/drawable/`
- [ ] Build sem erros
- [ ] Testar cada personagem na app com as 9 expressões
- [ ] Confirmar que emoções disparam as expressões certas
- [ ] Release APK v0.3.0
