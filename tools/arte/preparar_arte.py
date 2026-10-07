"""Gera os drawables do app a partir de docs/arte/<personagem>/<expressao>.(png|jpg|jfif|webp).

Recorta o fundo (rembg), aplica o MESMO enquadramento quadrado a todas as
expressões do personagem e salva WebP em app/src/main/res/drawable-nodpi/.

Uso: python tools/arte/preparar_arte.py jocta_casual [outro_personagem ...]
Requer: pip install "rembg[cpu]" pillow scipy
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage
from rembg import new_session, remove

RAIZ = Path(__file__).resolve().parents[2]
EXPRESSOES = ["neutro", "feliz", "pensativo", "falando", "ouvindo", "surpreso", "preocupado", "dormindo"]
EXTENSOES = [".png", ".jpg", ".jpeg", ".jfif", ".webp"]
LADO = 512
MARGEM = 0.04


def achar(pasta: Path, nome: str):
    for ext in EXTENSOES:
        p = pasta / f"{nome}{ext}"
        if p.exists():
            return p
    return None


def limpar_restos(img: Image.Image) -> Image.Image:
    """Zera restos de fundo claro e sem cor que ficaram presos ao contorno (ex.: entre fios de cabelo)."""
    a = np.array(img)
    rgb = a[..., :3].astype(int)
    claro = (rgb.min(axis=2) > 175) & (rgb.max(axis=2) - rgb.min(axis=2) < 22)
    fundo = a[..., 3] < 40
    rotulos, _ = ndimage.label(claro | fundo)
    externos = np.unique(np.concatenate([rotulos[0], rotulos[-1], rotulos[:, 0], rotulos[:, -1]]))
    externos = externos[externos > 0]
    a[np.isin(rotulos, externos) & claro, 3] = 0
    return Image.fromarray(a)


def preparar(personagem: str, sessao) -> None:
    pasta = RAIZ / "docs" / "arte" / personagem
    recortes = {}
    for expr in EXPRESSOES:
        origem = achar(pasta, expr)
        if origem:
            recortes[expr] = limpar_restos(remove(Image.open(origem).convert("RGB"), session=sessao, post_process_mask=True))
    if not recortes:
        print(f"{personagem}: nenhuma imagem encontrada")
        return

    tamanho = next(iter(recortes.values())).size
    if any(r.size != tamanho for r in recortes.values()):
        sys.exit(f"{personagem}: as expressões têm tamanhos diferentes; o enquadramento precisa ser igual")

    caixas = [r.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox() for r in recortes.values()]
    x0 = min(c[0] for c in caixas); y0 = min(c[1] for c in caixas)
    x1 = max(c[2] for c in caixas); y1 = max(c[3] for c in caixas)
    lado = int(max(x1 - x0, y1 - y0) * (1 + 2 * MARGEM))
    cx = (x0 + x1) // 2
    topo = y0 - int(lado * MARGEM)
    caixa = (cx - lado // 2, topo, cx - lado // 2 + lado, topo + lado)

    destino = RAIZ / "app" / "src" / "main" / "res" / "drawable-nodpi"
    destino.mkdir(parents=True, exist_ok=True)
    for expr, img in recortes.items():
        final = img.crop(caixa).resize((LADO, LADO), Image.LANCZOS)
        saida = destino / f"arte_{personagem}_{expr}.webp"
        final.save(saida, "WEBP", quality=90, method=6)
        print(f"{saida.relative_to(RAIZ)}  {saida.stat().st_size // 1024} KB")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sessao = new_session("isnet-general-use")
    for p in sys.argv[1:]:
        preparar(p, sessao)
