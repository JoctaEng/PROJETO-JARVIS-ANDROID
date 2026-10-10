# Gera a escultura (CPU, ~6 min num i7-5500U). Entrada: guardiao_tsr.png (fundo cinza, personagem em 85% do quadro).
# Com --bake-texture o TripoSR só grava OBJ, e a subpasta de saída precisa existir antes.
$d = "$env:USERPROFILE\Tools\euno-3d"
New-Item -ItemType Directory -Force "$d\saida_tsr\0" | Out-Null
Set-Location "$d\TripoSR"
& "$d\venv_tsr\Scripts\python.exe" run.py "$d\guardiao_tsr.png" --device cpu --no-remove-bg --mc-resolution 320 `
  --output-dir "$d\saida_tsr" --model-save-format obj --bake-texture --texture-resolution 2048
