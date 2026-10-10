# Instala o TripoSR (MIT) num ambiente Python separado, no Windows, só CPU. Pasta: %USERPROFILE%\Tools\euno-3d
$ProgressPreference = 'SilentlyContinue'   # sem isso o Invoke-WebRequest do PowerShell 5 fica lentíssimo
$d = "$env:USERPROFILE\Tools\euno-3d"
New-Item -ItemType Directory -Force $d | Out-Null
Set-Location $d
if (-not (Test-Path "$d\TripoSR\run.py")) {
  Invoke-WebRequest -UseBasicParsing "https://codeload.github.com/VAST-AI-Research/TripoSR/zip/refs/heads/main" -OutFile "$d\triposr.zip"
  Expand-Archive -Force "$d\triposr.zip" "$d\tsr_tmp"; Move-Item "$d\tsr_tmp\TripoSR-main" "$d\TripoSR"; Remove-Item -Recurse -Force "$d\tsr_tmp"
}
if (-not (Test-Path "$d\venv_tsr")) { python -m venv "$d\venv_tsr" }
$py = "$d\venv_tsr\Scripts\python.exe"
& $py -m pip install --upgrade pip
& $py -m pip install torch --index-url https://download.pytorch.org/whl/cpu
& $py -m pip install "transformers<5" omegaconf einops trimesh huggingface_hub scikit-image pillow xatlas moderngl imageio rembg onnxruntime
# torchmcubes precisa compilar: troca pelo marching cubes do scikit-image
Copy-Item -Force "$PSScriptRoot\isosurface_skimage.py" "$d\TripoSR\tsr\models\isosurface.py"
