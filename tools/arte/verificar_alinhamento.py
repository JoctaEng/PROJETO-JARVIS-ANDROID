"""Confere se as expressões de um personagem estão alinhadas entre si (mesma pose, escala e posição).

Uso: python tools/arte/verificar_alinhamento.py luna thor jocta_casual
Para cada arte em app/src/main/res/drawable-nodpi/arte_<id>_<expressao>.webp mede o deslocamento do tronco em
relação ao quadro "neutro" (correlação de fase) e a caixa que envolve o personagem. Se o tronco deslocar mais de
8 px (em 512) ou a altura variar mais de 6%, a fala vai parecer que "o corpo todo se mexe" e a arte deve ser refeita
(gerar cada expressão como edição só do rosto da mesma imagem-base). Requer: pillow, numpy.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

RAIZ = Path(__file__).resolve().parents[2]
EXPRESSOES = ["neutro", "feliz", "pensativo", "falando", "ouvindo", "surpreso", "preocupado", "dormindo"]


def carregar(pers, expr):
    p = RAIZ / "app/src/main/res/drawable-nodpi" / f"arte_{pers}_{expr}.webp"
    return Image.open(p).convert("RGBA") if p.exists() else None


def deslocamento(a, b):
    A, B = np.fft.fft2(a - a.mean()), np.fft.fft2(b - b.mean())
    R = A * np.conj(B)
    R /= np.abs(R) + 1e-9
    y, x = np.unravel_index(np.fft.ifft2(R).real.argmax(), a.shape)
    h, w = a.shape
    return (x if x < w // 2 else x - w), (y if y < h // 2 else y - h)


def tronco(im):
    arr = np.array(im)
    return np.array(im.convert("L"), dtype=float)[300:, :] * (arr[300:, :, 3] > 24)


def caixa(im):
    return im.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()


def main(personagens):
    ruim = False
    for pers in personagens:
        base = carregar(pers, "neutro")
        if base is None:
            print(f"{pers}: sem arte")
            continue
        b0, h0 = tronco(base), caixa(base)[3] - caixa(base)[1]
        print(f"== {pers}")
        for e in EXPRESSOES[1:]:
            im = carregar(pers, e)
            if im is None:
                continue
            dx, dy = deslocamento(b0, tronco(im))
            c = caixa(im)
            dh = abs((c[3] - c[1]) - h0) / h0
            falha = max(abs(dx), abs(dy)) > 8 or dh > 0.06
            ruim |= falha
            print(f"  {e:11s} tronco dx={dx:+4d} dy={dy:+4d}  altura {dh*100:4.1f}%  {'FALHA' if falha else 'ok'}")
    sys.exit(1 if ruim else 0)


if __name__ == "__main__":
    main(sys.argv[1:] or ["jocta_casual"])
