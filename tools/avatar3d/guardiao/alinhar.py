"""Acha a transformação (escala + deslocamento) que leva a silhueta da imagem original à vista de frente do modelo.

Uso: python alinhar.py front.png mapa.json fonte_rgba.png saida.json
Vista de frente: câmera ortográfica (ver inspecionar.py). Saída: pixel da fonte -> mundo:
  x = a*px + bx ;  z = -a*py + bz
"""
import json
import sys

import numpy as np
from PIL import Image

front, mapa, src, out = sys.argv[1:5]
info = json.load(open(mapa))
v = info["views"]["front"]
ortho, res, cz = v["ortho"], v["res"], v["loc"][2]

fr = np.asarray(Image.open(front).convert("RGB")).astype(float)
mask_r = (fr.min(axis=2) < 235)  # fundo branco
sa = np.asarray(Image.open(src).convert("RGBA"))[..., 3] > 128
H, W = sa.shape

# mundo de cada pixel da vista de frente
def r2w(px, py):
    return (px / res - 0.5) * ortho, cz + (0.5 - py / res) * ortho

ys, xs = np.nonzero(mask_r)
rx, rz = r2w(xs + 0.5, ys + 0.5)
# caixa das duas silhuetas -> chute inicial
sys_, sxs = np.nonzero(sa)
a0 = (rz.max() - rz.min()) / (sys_.max() - sys_.min())
bx0 = rx.mean() - a0 * sxs.mean()
bz0 = rz.mean() + a0 * sys_.mean()

# grade da vista de frente em baixa resolução para o IoU
N = 280
gx = np.linspace(-ortho / 2, ortho / 2, N)
gz = np.linspace(cz + ortho / 2, cz - ortho / 2, N)
GX, GZ = np.meshgrid(gx, gz)
small_r = np.asarray(Image.fromarray(mask_r.astype(np.uint8) * 255).resize((N, N), Image.BILINEAR)) > 127


def iou(a, bx, bz):
    px = ((GX - bx) / a).astype(int)
    py = ((bz - GZ) / a).astype(int)
    ok = (px >= 0) & (px < W) & (py >= 0) & (py < H)
    m = np.zeros_like(small_r)
    m[ok] = sa[py[ok], px[ok]]
    inter = (m & small_r).sum()
    return inter / max(1, (m | small_r).sum())


best = (iou(a0, bx0, bz0), a0, bx0, bz0)
for scale_span, shift in ((0.12, 0.10), (0.04, 0.03), (0.015, 0.01), (0.005, 0.004)):
    a_c, bx_c, bz_c = best[1:]
    for da in np.linspace(-scale_span, scale_span, 9):
        a = a_c * (1 + da)
        for dx in np.linspace(-shift, shift, 9):
            for dz in np.linspace(-shift, shift, 9):
                s = iou(a, bx_c + dx, bz_c + dz)
                if s > best[0]:
                    best = (s, a, bx_c + dx, bz_c + dz)
    print("IoU", round(best[0], 4))
score, a, bx, bz = best
json.dump({"a": a, "bx": bx, "bz": bz, "iou": score, "src_w": W, "src_h": H}, open(out, "w"), indent=1)
print(json.dumps({"a": a, "bx": bx, "bz": bz, "iou": score}))
