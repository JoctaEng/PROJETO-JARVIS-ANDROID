#!/usr/bin/env python3
"""
Gera os personagens 3D do Euno (formato VRM 1.0) a partir de formas simples, sem programa de modelagem:
Joctã Casual e Luna, no estilo das artes 2D (docs/arte). Saída: app/src/main/assets/avatar3d/models/<id>.vrm

Cada modelo tem esqueleto humanoide (pose T), rosto com bocas de fala (aa, ih, ou, ee, oh), piscar e emoções
(happy, sad, surprised, angry, relaxed) como "morph targets". Tudo determinístico (mesma saída a cada execução).

Uso: python3 tools/avatar3d/gerar_personagens.py
"""
import json
import math
import os
import struct
import sys

import numpy as np

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "avatar3d", "models")

# ---------------------------------------------------------------- cores


def lin(hex_color):
    """sRGB (#rrggbb) → linear (o glTF guarda cores lineares)."""
    h = hex_color.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c]


# ---------------------------------------------------------------- geometria básica


class Part:
    """Pedaço de malha: posições, normais (calculadas no fim), triângulos e o osso que o move."""

    def __init__(self, pos, tri, bone, material):
        self.pos = np.asarray(pos, dtype=np.float64)
        self.tri = np.asarray(tri, dtype=np.int64)
        self.bone = bone
        self.material = material


def grid_tris(rows, cols, wrap=True):
    tris = []
    cc = cols if wrap else cols - 1
    for r in range(rows - 1):
        for c in range(cc):
            a = r * cols + c
            b = r * cols + (c + 1) % cols
            d = (r + 1) * cols + c
            e = (r + 1) * cols + (c + 1) % cols
            tris += [(a, d, b), (b, d, e)]
    return tris


def ellipsoid(center, radii, rot=None, nu=28, nv=18, theta_max=None):
    """Elipsoide (esfera esticada). theta_max(phi) corta a parte de baixo (para calotas de cabelo)."""
    pts = []
    for i in range(nv):
        t = i / (nv - 1)
        for j in range(nu):
            phi = 2 * math.pi * j / nu
            tm = theta_max(phi) if theta_max else math.pi
            th = t * tm
            x = math.sin(th) * math.sin(phi)
            y = math.cos(th)
            z = math.sin(th) * math.cos(phi)
            pts.append((x * radii[0], y * radii[1], z * radii[2]))
    p = np.array(pts)
    if rot is not None:
        p = p @ np.asarray(rot).T
    p += np.asarray(center)
    return p, grid_tris(nv, nu)


def rot_to(direction):
    """Matriz que leva o eixo Y para a direção dada."""
    d = np.asarray(direction, dtype=np.float64)
    d = d / np.linalg.norm(d)
    y = np.array([0.0, 1.0, 0.0])
    v = np.cross(y, d)
    c = float(np.dot(y, d))
    if np.linalg.norm(v) < 1e-9:
        return np.eye(3) if c > 0 else np.diag([1.0, -1.0, -1.0])
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1 / (1 + c))


def limb(a, b, r_a, r_b=None, nu=20, nv=14):
    """Membro arredondado de a até b (elipsoide alongado, mais grosso numa ponta)."""
    r_b = r_a if r_b is None else r_b
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    length = np.linalg.norm(b - a)
    p, t = ellipsoid((0, 0, 0), (1, length / 2, 1), nu=nu, nv=nv)
    # raio varia ao longo do comprimento (y de +L/2 em a até -L/2 em b)
    k = (p[:, 1] / (length / 2) + 1) / 2  # 1 em a, 0 em b
    r = r_b + (r_a - r_b) * k
    p[:, 0] *= r
    p[:, 2] *= r
    p = p @ rot_to(a - b).T
    p += (a + b) / 2
    return p, t


def cone(base, tip, radius, n=10):
    base = np.asarray(base, dtype=np.float64)
    tip = np.asarray(tip, dtype=np.float64)
    R = rot_to(tip - base)
    pts = [base]  # centro da base
    for j in range(n):
        a = 2 * math.pi * j / n
        pts.append(base + R @ np.array([math.cos(a) * radius, 0, math.sin(a) * radius]))
    pts.append(tip)
    tris = []
    for j in range(n):
        a, b = 1 + j, 1 + (j + 1) % n
        tris.append((0, b, a))
        tris.append((a, b, n + 1))
    return np.array(pts), tris


def torus(center, R, r, rot=None, nu=36, nv=12, arc=2 * math.pi):
    pts = []
    for i in range(nu):
        u = arc * i / (nu - 1 if arc < 2 * math.pi else nu)
        for j in range(nv):
            v = 2 * math.pi * j / nv
            x = (R + r * math.cos(v)) * math.cos(u)
            y = (R + r * math.cos(v)) * math.sin(u)
            z = r * math.sin(v)
            pts.append((x, y, z))
    p = np.array(pts)
    if rot is not None:
        p = p @ np.asarray(rot).T
    p += np.asarray(center)
    tris = []
    rows = nu
    for i in range(rows - (1 if arc < 2 * math.pi else 0)):
        for j in range(nv):
            a = i * nv + j
            b = i * nv + (j + 1) % nv
            c = ((i + 1) % rows) * nv + j
            d = ((i + 1) % rows) * nv + (j + 1) % nv
            tris += [(a, c, b), (b, c, d)]
    return p, tris


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def normals(pos, tri):
    n = np.zeros_like(pos)
    a, b, c = pos[tri[:, 0]], pos[tri[:, 1]], pos[tri[:, 2]]
    fn = np.cross(b - a, c - a)
    for k in range(3):
        np.add.at(n, tri[:, k], fn)
    ln = np.linalg.norm(n, axis=1, keepdims=True)
    ln[ln == 0] = 1
    return n / ln


# ---------------------------------------------------------------- rosto (formas que mudam: bocas, olhos, sobrancelhas)

HEAD_C = np.array([0.0, 1.27, 0.0])
HEAD_R = np.array([0.125, 0.135, 0.12])


def on_face(x, y, lift):
    """Ponto (x, y) da frente da cabeça, colado na superfície e levantado 'lift' na direção da normal."""
    dx, dy = x / HEAD_R[0], (y - HEAD_C[1]) / HEAD_R[1]
    s = max(1e-6, 1 - dx * dx - dy * dy)
    z = HEAD_R[2] * math.sqrt(s)
    nrm = np.array([x / HEAD_R[0] ** 2, (y - HEAD_C[1]) / HEAD_R[1] ** 2, z / HEAD_R[2] ** 2])
    nrm /= np.linalg.norm(nrm)
    return np.array([x, y, z]) + nrm * lift


def disc(cx, cy, rx, ry, lift, rings=4, seg=28, warp=None):
    """Disco elíptico na superfície do rosto (topologia fixa, para as formas poderem mudar)."""
    pts = [on_face(cx, cy, lift)]
    for r in range(1, rings + 1):
        f = r / rings
        for j in range(seg):
            a = 2 * math.pi * j / seg
            x, y = math.cos(a) * rx * f, math.sin(a) * ry * f
            if warp:
                x, y = warp(x, y)
            pts.append(on_face(cx + x, cy + y, lift))
    tris = []
    for j in range(seg):
        tris.append((0, 1 + j, 1 + (j + 1) % seg))
    for r in range(1, rings):
        base0 = 1 + (r - 1) * seg
        base1 = 1 + r * seg
        for j in range(seg):
            a, b = base0 + j, base0 + (j + 1) % seg
            c, d = base1 + j, base1 + (j + 1) % seg
            tris += [(a, c, b), (b, c, d)]
    return np.array(pts), tris


MOUTH_Y = 1.205

# Parâmetros da boca: largura, abertura, curva (sorriso + / triste -), dentes visíveis
MOUTH = {
    "base": dict(w=0.030, h=0.0035, curve=0.006),
    "aa": dict(w=0.032, h=0.030, curve=0.002),
    "ih": dict(w=0.036, h=0.010, curve=0.003),
    "ou": dict(w=0.016, h=0.016, curve=0.0),
    "ee": dict(w=0.036, h=0.018, curve=0.004),
    "oh": dict(w=0.022, h=0.024, curve=0.0),
    "happy": dict(w=0.040, h=0.016, curve=0.016),
    "sad": dict(w=0.026, h=0.004, curve=-0.010),
    "surprised": dict(w=0.020, h=0.026, curve=0.0),
    "angry": dict(w=0.030, h=0.005, curve=-0.006),
    "relaxed": dict(w=0.032, h=0.004, curve=0.010),
}

# Olhos: abertura (1 = normal), escala, sorriso dos olhos (arco), sobrancelha: altura, inclinação interna
EYES = {
    "base": dict(open=1.0, scale=1.0, arc=0.0, brow_y=0.0, brow_in=0.0),
    "blink": dict(open=0.06, scale=1.0, arc=0.0, brow_y=-0.002, brow_in=0.0),
    "happy": dict(open=0.10, scale=1.0, arc=1.0, brow_y=0.004, brow_in=0.0),
    "sad": dict(open=0.8, scale=0.96, arc=0.0, brow_y=0.003, brow_in=0.010),
    "surprised": dict(open=1.15, scale=1.15, arc=0.0, brow_y=0.012, brow_in=0.0),
    "angry": dict(open=0.75, scale=1.0, arc=0.0, brow_y=-0.004, brow_in=-0.010),
    "relaxed": dict(open=0.55, scale=1.0, arc=0.0, brow_y=0.0, brow_in=0.0),
}

EYE_X = 0.046
EYE_Y = 1.262


def mouth_parts(p, colors):
    w, h, c = p["w"], p["h"], p["curve"]

    def warp(x, y):
        # cantos sobem (sorriso) ou descem (tristeza); abre mais embaixo que em cima
        return x, (y * (1.3 if y < 0 else 0.7)) + c * (x / max(w, 1e-6)) ** 2

    mp, mt = disc(0.0, MOUTH_Y, w, h, 0.0016, warp=warp)
    # dentes de cima: faixa no topo da boca, visível só quando abre
    tw, th = w * 0.78, max(h * 0.22, 0.0006)
    tp, tt = disc(0.0, MOUTH_Y + h * 0.62 + c * 0.25, tw, th, 0.0019)
    # língua: embaixo
    gw, gh = w * 0.6, max(h * 0.28, 0.0006)
    gp, gt = disc(0.0, MOUTH_Y - h * 0.7 + c * 0.2, gw, gh, 0.0019)
    return [(mp, mt, colors["mouth"]), (tp, tt, "teeth"), (gp, gt, "tongue")]


def eye_parts(p, side, colors):
    """side: +1 = olho esquerdo do personagem (x positivo)."""
    cx = side * EYE_X
    s = p["scale"]
    op = p["open"]
    arc = p["arc"]
    yline = EYE_Y - 0.012  # linha onde o olho fecha (um pouco abaixo do centro)

    def squash(x, y):
        yy = (EYE_Y + y - yline) * op + yline - EYE_Y
        # olhos sorrindo: viram um arco para cima
        yy += arc * (0.010 - 900 * x * x * 0.010 / 0.6)
        return x, yy

    parts = []
    sp, st = disc(cx, EYE_Y, 0.027 * s, 0.033 * s, 0.0012, warp=squash)
    parts.append((sp, st, "eye_white"))
    ip, it = disc(cx, EYE_Y - 0.003, 0.019 * s, 0.023 * s, 0.0022, warp=lambda x, y: squash(x, y - 0.003))
    parts.append((ip, it, colors["iris"]))
    pp, pt = disc(cx, EYE_Y - 0.003, 0.009 * s, 0.011 * s, 0.0030, warp=lambda x, y: squash(x, y - 0.003))
    parts.append((pp, pt, "pupil"))
    hp, ht = disc(cx + 0.007 * s, EYE_Y + 0.008 * s, 0.0055 * s, 0.0055 * s, 0.0038, warp=lambda x, y: squash(x + 0.007 * s, y + 0.008 * s))
    parts.append((hp, ht, "highlight"))
    # pálpebra de cima (traço escuro que desce ao piscar)
    top = (EYE_Y + 0.033 * s - yline) * op + yline + arc * 0.010

    def lid(x, y):
        return x, y + 0.0

    lp, lt = disc(cx, top, 0.029 * s, 0.0032, 0.0044, rings=2, seg=24, warp=lid)
    parts.append((lp, lt, "lash"))
    # sobrancelha
    by = EYE_Y + 0.052 + p["brow_y"]
    tilt = p["brow_in"]

    def brow(x, y):
        inner = (-x * side) / 0.024  # +1 no lado de dentro
        return x, y + tilt * max(0.0, inner) * 0.5 + tilt * 0.5 * (inner if inner > 0 else 0) + 0.004 * (1 - (x / 0.024) ** 2)

    bp, bt = disc(cx, by, 0.024, 0.0045, 0.0030, rings=2, seg=24, warp=brow)
    parts.append((bp, bt, colors["brow"]))
    return parts


def face_parts(params_mouth, params_eyes, colors):
    parts = mouth_parts(params_mouth, colors)
    for side in (1, -1):
        parts += eye_parts(params_eyes, side, colors)
    return parts


EXPRESSIONS = ["aa", "ih", "ou", "ee", "oh", "blink", "blinkLeft", "blinkRight", "happy", "sad", "surprised", "angry", "relaxed"]


def build_face(colors):
    base = face_parts(MOUTH["base"], EYES["base"], colors)
    targets = []
    for name in EXPRESSIONS:
        m = MOUTH.get(name, MOUTH["base"])
        if name.startswith("blink"):
            e_left = EYES["blink"] if name in ("blink", "blinkLeft") else EYES["base"]
            e_right = EYES["blink"] if name in ("blink", "blinkRight") else EYES["base"]
            parts = mouth_parts(m, colors) + eye_parts(e_left, 1, colors) + eye_parts(e_right, -1, colors)
        else:
            parts = face_parts(m, EYES.get(name, EYES["base"]), colors)
        targets.append([p[0] - b[0] for p, b in zip(parts, base)])
    return base, targets


# ---------------------------------------------------------------- esqueleto (pose T, olhando para +Z; esquerda = +X)

BONES = [
    ("hips", None, (0, 0.72, 0)),
    ("spine", "hips", (0, 0.80, 0)),
    ("chest", "spine", (0, 0.95, 0)),
    ("neck", "chest", (0, 1.11, 0)),
    ("head", "neck", (0, 1.17, 0)),
    ("leftUpperArm", "chest", (0.165, 1.065, 0)),
    ("leftLowerArm", "leftUpperArm", (0.385, 1.065, 0)),
    ("leftHand", "leftLowerArm", (0.575, 1.065, 0)),
    ("rightUpperArm", "chest", (-0.165, 1.065, 0)),
    ("rightLowerArm", "rightUpperArm", (-0.385, 1.065, 0)),
    ("rightHand", "rightLowerArm", (-0.575, 1.065, 0)),
    ("leftUpperLeg", "hips", (0.085, 0.68, 0)),
    ("leftLowerLeg", "leftUpperLeg", (0.085, 0.38, 0)),
    ("leftFoot", "leftLowerLeg", (0.085, 0.08, 0)),
    ("rightUpperLeg", "hips", (-0.085, 0.68, 0)),
    ("rightLowerLeg", "rightUpperLeg", (-0.085, 0.38, 0)),
    ("rightFoot", "rightLowerLeg", (-0.085, 0.08, 0)),
]
BONE_INDEX = {b[0]: i for i, b in enumerate(BONES)}
BONE_POS = {b[0]: np.array(b[2], dtype=np.float64) for b in BONES}


# ---------------------------------------------------------------- corpo de cada personagem


def body_common(parts, c, rng):
    add = lambda geo, bone, mat: parts.append(Part(geo[0], geo[1], bone, mat))  # noqa: E731
    # cabeça, orelhas, nariz, pescoço
    add(ellipsoid(HEAD_C, HEAD_R, nu=40, nv=28), "head", "skin")
    for s in (1, -1):
        add(ellipsoid((s * 0.122, 1.255, -0.005), (0.018, 0.028, 0.014), rot=rot_y(s * 0.3)), "head", "skin")
    add(ellipsoid((0, 1.232, 0.118), (0.011, 0.010, 0.010)), "head", "skin")
    add(limb((0, 1.17, -0.005), (0, 1.07, 0), 0.040, 0.045), "neck", "skin")
    # tronco (moletom)
    add(ellipsoid((0, 0.93, 0), (0.150, 0.195, 0.10), nu=32, nv=22), "chest", c["top"])
    add(ellipsoid((0, 0.76, 0), (0.15, 0.10, 0.095)), "hips", c["top"])
    # capuz atrás do pescoço e gola
    add(ellipsoid((0, 1.09, -0.075), (0.115, 0.065, 0.06)), "chest", c["top"])
    add(torus((0, 1.085, 0.0), 0.072, 0.024, rot=rot_x(math.pi / 2 - 0.25)), "chest", c["collar"])
    # ombros, braços, mãos
    for s, side in ((1, "left"), (-1, "right")):
        add(ellipsoid((s * 0.14, 1.05, 0), (0.05, 0.045, 0.05)), "chest", c["top"])
        add(limb((s * 0.165, 1.065, 0), (s * 0.385, 1.065, 0), 0.038, 0.034), side + "UpperArm", c["sleeve"])
        add(limb((s * 0.385, 1.065, 0), (s * 0.545, 1.065, 0), 0.034, 0.030), side + "LowerArm", c["sleeve"])
        add(torus((s * 0.55, 1.065, 0), 0.028, 0.008, rot=rot_y(math.pi / 2)), side + "LowerArm", c["cuff"])
        add(ellipsoid((s * 0.60, 1.062, 0.005), (0.042, 0.022, 0.034)), side + "Hand", "skin")
        add(limb((s * 0.59, 1.07, 0.03), (s * 0.615, 1.07, 0.05), 0.010, 0.009), side + "Hand", "skin")  # polegar
        # pernas e tênis
        add(limb((s * 0.085, 0.72, 0), (s * 0.085, 0.38, 0), 0.07, 0.055), side + "UpperLeg", c["pants"])
        add(limb((s * 0.085, 0.38, 0), (s * 0.085, 0.09, 0), 0.055, 0.046), side + "LowerLeg", c["pants"])
        add(ellipsoid((s * 0.085, 0.05, 0.035), (0.055, 0.045, 0.10)), side + "Foot", c["shoe"])
        add(ellipsoid((s * 0.085, 0.015, 0.035), (0.058, 0.016, 0.105)), side + "Foot", c["sole"])
    # cordões do capuz
    for s in (1, -1):
        add(limb((s * 0.028, 1.06, 0.075), (s * 0.036, 0.985, 0.104), 0.0055), "chest", c["string"])
        add(ellipsoid((s * 0.036, 0.98, 0.105), (0.008, 0.012, 0.008)), "chest", c["string"])


def hairline(front, side, back):
    def tm(phi):
        # phi = 0 é a frente (z+); interpola frente → lado → trás
        a = abs(((phi + math.pi) % (2 * math.pi)) - math.pi)  # 0 frente, pi trás
        if a < math.pi / 2:
            t = a / (math.pi / 2)
            return front + (side - front) * (t * t * (3 - 2 * t))
        t = (a - math.pi / 2) / (math.pi / 2)
        return side + (back - side) * (t * t * (3 - 2 * t))
    return tm


def jocta(parts, rng):
    c = dict(top="hoodie_black", collar="trim_blue", sleeve="hoodie_black", cuff="trim_blue", pants="pants_dark",
             shoe="shoe_black", sole="sole_white", string="trim_blue")
    body_common(parts, c, rng)
    add = lambda geo, bone, mat: parts.append(Part(geo[0], geo[1], bone, mat))  # noqa: E731
    # triângulo luminoso no peito (invertido) e zíper
    tri = np.array([on_face_body(0.048, 0.965), on_face_body(-0.048, 0.965), on_face_body(0.0, 0.89)])
    back = tri - np.array([0, 0, 0.004])
    pts = np.vstack([tri, back])
    t = [(0, 1, 2), (3, 5, 4), (0, 3, 1), (1, 3, 4), (1, 4, 2), (2, 4, 5), (2, 5, 0), (0, 5, 3)]
    add((pts, t), "chest", "glow_blue")
    add(limb((0, 1.04, 0.1), (0, 0.70, 0.095), 0.004), "chest", "zip")
    # cabelo castanho bagunçado: calota + mechas pontudas
    add(ellipsoid((0, 1.292, -0.012), (0.134, 0.128, 0.13), nu=40, nv=22,
                  theta_max=hairline(0.98, 1.55, 2.15)), "head", "hair_brown")
    for k in range(46):
        th = rng.uniform(0.05, 1.25)
        ph = rng.uniform(-math.pi, math.pi)
        if abs(ph) < 0.9 and th > 0.95:
            th = 0.95  # franja curta na testa
        d = np.array([math.sin(th) * math.sin(ph), math.cos(th), math.sin(th) * math.cos(ph)])
        base = np.array([0, 1.292, -0.012]) + d * np.array([0.128, 0.122, 0.124])
        up = d + np.array([0, 0.55, 0]) + (np.array([0, -0.2, 0.55]) if abs(ph) < 0.9 else 0)
        up /= np.linalg.norm(up)
        length = rng.uniform(0.045, 0.085)
        tip = base + up * length + rng.normal(0, 0.008, 3)
        add(cone(base, tip, rng.uniform(0.022, 0.032)), "head", "hair_brown" if k % 4 else "hair_light")


def on_face_body(x, y):
    """Ponto na frente do tronco (elipsoide do peito)."""
    cx, cy, rx, ry, rz = 0.0, 0.93, 0.150, 0.195, 0.10
    s = max(1e-6, 1 - (x / rx) ** 2 - ((y - cy) / ry) ** 2)
    return np.array([x, y, rz * math.sqrt(s) + 0.006])


def luna(parts, rng):
    c = dict(top="hoodie_lilac", collar="hoodie_lilac_dark", sleeve="hoodie_lilac", cuff="hoodie_lilac_light",
             pants="jeans", shoe="shoe_lilac", sole="sole_white", string="string_white")
    body_common(parts, c, rng)
    add = lambda geo, bone, mat: parts.append(Part(geo[0], geo[1], bone, mat))  # noqa: E731
    # bolso canguru
    add(ellipsoid((0, 0.80, 0.088), (0.095, 0.045, 0.02)), "hips", "hoodie_lilac_dark")
    # cabelo roxo escuro: calota, franja, mechas laterais e cabelo longo atrás
    add(ellipsoid((0, 1.29, -0.01), (0.135, 0.13, 0.13), nu=40, nv=22,
                  theta_max=hairline(0.92, 1.75, 2.3)), "head", "hair_purple")
    for k in range(6):
        x = -0.075 + k * 0.03
        top = np.array([x * 0.8, 1.375, 0.07 - abs(x) * 0.2])
        low = np.array([x * 1.05, 1.305 - abs(x) * 0.25, 0.118 - abs(x) * 0.3])
        geo = limb(top, low, 0.03, 0.012, nu=16, nv=10)
        # achata a mecha contra a testa
        c0 = (top + low) / 2
        nrm = np.array([x, 0.25, 0.9])
        nrm /= np.linalg.norm(nrm)
        g = geo[0] - c0
        g -= np.outer(g @ nrm, nrm) * 0.6
        add((g + c0, geo[1]), "head", "hair_purple" if k % 2 else "hair_purple_light")
    for s in (1, -1):
        add(limb((s * 0.11, 1.30, 0.04), (s * 0.135, 1.05, 0.045), 0.032, 0.02), "head", "hair_purple")
        add(limb((s * 0.12, 1.28, -0.03), (s * 0.15, 0.98, -0.05), 0.045, 0.03), "head", "hair_purple")
    add(limb((0, 1.28, -0.07), (0, 0.92, -0.11), 0.125, 0.10), "head", "hair_purple")
    # fones de ouvido roxos
    add(torus((0, 1.265, -0.005), 0.148, 0.013, arc=math.pi), "head", "phones")
    for s in (1, -1):
        cup = limb((s * 0.128, 1.255, -0.005), (s * 0.165, 1.255, -0.005), 0.046, 0.046)
        add(cup, "head", "phones")
        add(ellipsoid((s * 0.168, 1.255, -0.005), (0.006, 0.03, 0.03)), "head", "phones_light")


# ---------------------------------------------------------------- materiais

MATERIALS = {
    "skin": ("#f4c7a8", 0.75, None),
    "eye_white": ("#fbfbfb", 0.4, None),
    "pupil": ("#141414", 0.3, None),
    "highlight": ("#ffffff", 0.2, "#ffffff"),
    "lash": ("#2a1b16", 0.6, None),
    "teeth": ("#f7f5f0", 0.4, None),
    "tongue": ("#d9706f", 0.6, None),
    "mouth_dark": ("#5a1f2a", 0.8, None),
    "iris_brown": ("#7a4a22", 0.4, None),
    "iris_violet": ("#7b4fb8", 0.4, None),
    "brow_brown": ("#4a2c1d", 0.8, None),
    "brow_purple": ("#3b2150", 0.8, None),
    "hair_brown": ("#5a3624", 0.65, None),
    "hair_light": ("#7a4c32", 0.65, None),
    "hair_purple": ("#40245a", 0.6, None),
    "hair_purple_light": ("#5a3580", 0.6, None),
    "hoodie_black": ("#1d1f24", 0.85, None),
    "trim_blue": ("#2f7cf6", 0.6, None),
    "glow_blue": ("#38c6ff", 0.4, "#38c6ff"),
    "zip": ("#55585f", 0.5, None),
    "pants_dark": ("#2a2c31", 0.85, None),
    "shoe_black": ("#232323", 0.6, None),
    "sole_white": ("#ececec", 0.7, None),
    "hoodie_lilac": ("#b48be8", 0.85, None),
    "hoodie_lilac_dark": ("#8e5fd3", 0.85, None),
    "hoodie_lilac_light": ("#d9c4f7", 0.85, None),
    "string_white": ("#f2eefa", 0.7, None),
    "jeans": ("#323a52", 0.85, None),
    "shoe_lilac": ("#c7a6f2", 0.6, None),
    "phones": ("#a77ae6", 0.45, None),
    "phones_light": ("#e6d9fb", 0.4, None),
}


# ---------------------------------------------------------------- GLB / VRM


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views = []
        self.accessors = []

    def add(self, arr, ctype, typ, target=None, minmax=False):
        data = np.ascontiguousarray(arr).tobytes()
        while len(self.bin) % 4:
            self.bin.append(0)
        view = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if target:
            view["target"] = target
        self.bin += data
        self.views.append(view)
        acc = {"bufferView": len(self.views) - 1, "componentType": ctype, "count": int(arr.shape[0]), "type": typ}
        if minmax:
            acc["min"] = [float(v) for v in arr.min(axis=0)]
            acc["max"] = [float(v) for v in arr.max(axis=0)]
        self.accessors.append(acc)
        return len(self.accessors) - 1


def build(char_id, name, make, face_colors, out_path, seed):
    rng = np.random.default_rng(seed)
    parts = []
    make(parts, rng)
    face_base, face_targets = build_face(face_colors)

    glb = Glb()
    mat_names = []

    def mat_index(m):
        if m not in mat_names:
            mat_names.append(m)
        return mat_names.index(m)

    # corpo: um primitivo por material, cada vértice preso ao seu osso
    by_mat = {}
    for p in parts:
        by_mat.setdefault(p.material, []).append(p)
    body_prims = []
    for m, plist in by_mat.items():
        pos, nor, idx, joints = [], [], [], []
        off = 0
        for p in plist:
            pos.append(p.pos)
            nor.append(normals(p.pos, p.tri))
            idx.append(p.tri + off)
            joints.append(np.full((len(p.pos),), BONE_INDEX[p.bone]))
            off += len(p.pos)
        P = np.vstack(pos).astype(np.float32)
        N = np.vstack(nor).astype(np.float32)
        I = np.vstack(idx).astype(np.uint32).reshape(-1)
        J = np.zeros((len(P), 4), dtype=np.uint16)
        J[:, 0] = np.concatenate(joints)
        W = np.zeros((len(P), 4), dtype=np.float32)
        W[:, 0] = 1
        body_prims.append({
            "attributes": {
                "POSITION": glb.add(P, 5126, "VEC3", 34962, True),
                "NORMAL": glb.add(N, 5126, "VEC3", 34962),
                "JOINTS_0": glb.add(J, 5123, "VEC4", 34962),
                "WEIGHTS_0": glb.add(W, 5126, "VEC4", 34962),
            },
            "indices": glb.add(I, 5125, "SCALAR", 34963),
            "material": mat_index(m),
        })

    # rosto: um primitivo por peça, com as mesmas formas-alvo em todos
    face_prims = []
    for k, (fp, ft, fm) in enumerate(face_base):
        P = fp.astype(np.float32)
        T = np.array(ft, dtype=np.uint32)
        N = normals(fp, np.array(ft)).astype(np.float32)
        J = np.zeros((len(P), 4), dtype=np.uint16)
        J[:, 0] = BONE_INDEX["head"]
        W = np.zeros((len(P), 4), dtype=np.float32)
        W[:, 0] = 1
        targets = []
        for tgt in face_targets:
            D = tgt[k].astype(np.float32)
            targets.append({"POSITION": glb.add(D, 5126, "VEC3", 34962, True)})
        face_prims.append({
            "attributes": {
                "POSITION": glb.add(P, 5126, "VEC3", 34962, True),
                "NORMAL": glb.add(N, 5126, "VEC3", 34962),
                "JOINTS_0": glb.add(J, 5123, "VEC4", 34962),
                "WEIGHTS_0": glb.add(W, 5126, "VEC4", 34962),
            },
            "indices": glb.add(T.reshape(-1), 5125, "SCALAR", 34963),
            "material": mat_index(fm),
            "targets": targets,
        })

    # nós: ossos (posição local = mundo - pai), malhas na raiz
    nodes = []
    for bname, parent, pos in BONES:
        local = np.array(pos) - (BONE_POS[parent] if parent else 0)
        nodes.append({"name": bname, "translation": [float(v) for v in local], "children": []})
    for i, (bname, parent, _) in enumerate(BONES):
        if parent:
            nodes[BONE_INDEX[parent]]["children"].append(i)
    for n in nodes:
        if not n["children"]:
            del n["children"]
    ibm = np.zeros((len(BONES), 16), dtype=np.float32)
    for i, (bname, _, pos) in enumerate(BONES):
        m = np.eye(4, dtype=np.float32)
        m[:3, 3] = -np.array(pos)
        ibm[i] = m.T.reshape(-1)  # coluna-maior
    ibm_acc = glb.add(ibm, 5126, "MAT4")
    body_node = len(nodes)
    nodes.append({"name": "Corpo", "mesh": 0, "skin": 0})
    face_node = len(nodes)
    nodes.append({"name": "Rosto", "mesh": 1, "skin": 0})

    materials = []
    for m in mat_names:
        color, rough, emissive = MATERIALS[m]
        mat = {"name": m, "pbrMetallicRoughness": {"baseColorFactor": lin(color) + [1.0], "metallicFactor": 0.0, "roughnessFactor": rough}}
        if emissive:
            mat["emissiveFactor"] = lin(emissive)
        if m.startswith("hair") or m.startswith("hoodie"):
            mat["doubleSided"] = True  # sem frestas vendo o "lado de dentro" do cabelo e do capuz
        materials.append(mat)

    def bind(idx):
        return {"morphTargetBinds": [{"node": face_node, "index": idx, "weight": 1.0}], "isBinary": False,
                "overrideBlink": "none", "overrideLookAt": "none", "overrideMouth": "none"}

    preset = {e: bind(i) for i, e in enumerate(EXPRESSIONS)}
    preset["neutral"] = {"isBinary": False, "overrideBlink": "none", "overrideLookAt": "none", "overrideMouth": "none"}

    gltf = {
        "asset": {"version": "2.0", "generator": "Euno gerar_personagens.py"},
        "extensionsUsed": ["VRMC_vrm"],
        "scene": 0,
        "scenes": [{"nodes": [0, body_node, face_node]}],
        "nodes": nodes,
        "meshes": [
            {"name": "Corpo", "primitives": body_prims},
            {"name": "Rosto", "primitives": face_prims, "weights": [0.0] * len(EXPRESSIONS),
             "extras": {"targetNames": EXPRESSIONS}},
        ],
        "skins": [{"joints": list(range(len(BONES))), "inverseBindMatrices": ibm_acc, "skeleton": 0}],
        "materials": materials,
        "accessors": glb.accessors,
        "bufferViews": glb.views,
        "buffers": [{"byteLength": len(glb.bin)}],
        "extensions": {
            "VRMC_vrm": {
                "specVersion": "1.0",
                "meta": {
                    "name": name, "version": "1.0", "authors": ["Projeto Euno"],
                    "licenseUrl": "https://vrm.dev/licenses/1.0/", "avatarPermission": "onlyAuthor",
                    "commercialUsage": "personalNonProfit", "creditNotation": "required",
                    "allowRedistribution": False, "modification": "prohibited",
                    "allowExcessivelyViolentUsage": False, "allowExcessivelySexualUsage": False,
                    "allowPoliticalOrReligiousUsage": False, "allowAntisocialOrHateUsage": False,
                },
                "humanoid": {"humanBones": {b[0]: {"node": BONE_INDEX[b[0]]} for b in BONES}},
                "expressions": {"preset": preset},
            },
        },
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    while len(js) % 4:
        js += b" "
    binb = bytes(glb.bin)
    while len(binb) % 4:
        binb += b"\0"
    total = 12 + 8 + len(js) + 8 + len(binb)
    with open(out_path, "wb") as f:
        f.write(struct.pack("<4sII", b"glTF", 2, total))
        f.write(struct.pack("<I4s", len(js), b"JSON"))
        f.write(js)
        f.write(struct.pack("<I4s", len(binb), b"BIN\0"))
        f.write(binb)
    print(f"{out_path}: {total // 1024} KB, {len(parts)} peças, {len(face_base)} peças no rosto, {len(EXPRESSIONS)} expressões")


def main():
    os.makedirs(OUT, exist_ok=True)
    build("jocta_casual", "Joctã Casual", jocta,
          {"mouth": "mouth_dark", "iris": "iris_brown", "brow": "brow_brown"},
          os.path.join(OUT, "jocta_casual.vrm"), seed=7)
    build("luna", "Luna", luna,
          {"mouth": "mouth_dark", "iris": "iris_violet", "brow": "brow_purple"},
          os.path.join(OUT, "luna.vrm"), seed=11)


if __name__ == "__main__":
    sys.exit(main())
