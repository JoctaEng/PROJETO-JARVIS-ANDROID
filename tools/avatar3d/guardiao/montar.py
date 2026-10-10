"""Etapa 2: transforma a escultura em avatar falante (Blender 5.2).

Uso: python montar.py base.blend marcos.json saida.glb
marcos.json (metros, sistema do Blender: Z para cima, personagem olhando para -Y, esquerda do personagem = +X):
  mouth_l / mouth_r: [x, z] cantos da boca (l = esquerda do personagem, x maior)
  seam_z: altura da linha entre os lábios no centro
  chin_z: ponta do queixo; neck_z: onde o pescoço encontra a cabeça (embaixo do queixo)
  eye_l / eye_r: [x, z] centro dos olhos; eye_w / eye_h: meia largura e meia altura do olho
  brow_z: altura das sobrancelhas; shoulder_z, hips_z: alturas no corpo; shoulder_x: meia largura dos ombros
  target_faces: faces do corpo depois de reduzir
Saída: GLB com esqueleto humanoide, um único mesh com morph targets
  aa ih ou ee oh blink happy sad angry surprised relaxed (nessa ordem).
"""
import json
import math
import sys
from pathlib import Path

import bpy  # precisa vir antes do bmesh no módulo bpy
import bmesh
import numpy as np
from mathutils import Matrix, Vector

blend, marks_path, out_glb = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
M = json.loads(marks_path.read_text())
bpy.ops.wm.open_mainfile(filepath=blend)
for o in list(bpy.context.scene.objects):
    if o.type != "MESH":
        bpy.data.objects.remove(o, do_unlink=True)
obj = bpy.data.objects["Guardiao"]
bpy.context.view_layer.objects.active = obj
obj.select_set(True)

xl, zl = M["mouth_l"]
xr, zr = M["mouth_r"]
CX = (xl + xr) / 2
W = abs(xl - xr)
SEAM = M["seam_z"]
CHIN = M["chin_z"]
NECK = M["neck_z"]
EYES = [M["eye_l"], M["eye_r"]]
EW, EH = M["eye_w"], M["eye_h"]
BROW = M["brow_z"]


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def front_y(x, z):
    """Profundidade da superfície da frente (menor y) no ponto (x, z)."""
    ok, loc, nor, idx = obj.ray_cast(Vector((x, -2.0, z)), Vector((0, 1, 0)))
    return loc.y if ok else None


# ---------- 0. soldar as costuras (o OBJ do TripoSR vem com vértices repetidos nas bordas da textura) ----------
_bm = bmesh.new(); _bm.from_mesh(obj.data)
_n0 = len(_bm.verts)
bmesh.ops.remove_doubles(_bm, verts=_bm.verts, dist=1e-5)
print("vértices soldados:", _n0 - len(_bm.verts))
_bm.to_mesh(obj.data); _bm.free(); obj.data.update()

# ---------- 1. reduzir o corpo ----------
if len(obj.data.polygons) > M.get("target_faces", 60000) * 1.05:
    mod = obj.modifiers.new("dec", "DECIMATE")
    mod.ratio = M.get("target_faces", 60000) / len(obj.data.polygons)
    bpy.ops.object.modifier_apply(modifier=mod.name)
print("faces depois de reduzir:", len(obj.data.polygons))

MOUTH_Y = front_y(CX, SEAM)
FACE_Y = MOUTH_Y
assert MOUTH_Y is not None, "não achei a boca na malha"

# ---------- 2. refinar o rosto (mais vértices para a boca e os olhos se mexerem) ----------
bm = bmesh.new()
bm.from_mesh(obj.data)
bm.faces.ensure_lookup_table()
face_faces = []
for f in bm.faces:
    c = f.calc_center_median()
    if abs(c.x - CX) < 0.075 and CHIN - 0.02 < c.z < BROW + 0.03 and c.y < FACE_Y + 0.05:
        face_faces.append(f)
edges = list({e for f in face_faces for e in f.edges})
bmesh.ops.subdivide_edges(bm, edges=edges, cuts=2, use_grid_fill=True)
bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])

# ---------- 3. abrir a boca: corte na linha dos lábios ----------
bm.faces.ensure_lookup_table()
region = []
for f in bm.faces:
    c = f.calc_center_median()
    if abs(c.x - CX) < W / 2 + 0.004 and abs(c.z - SEAM) < 0.012 and c.y < MOUTH_Y + 0.015:
        region.append(f)
geom = list({v for f in region for v in f.verts}) + list({e for f in region for e in f.edges}) + region
res = bmesh.ops.bisect_plane(bm, geom=geom, dist=1e-5, plane_co=Vector((0, 0, SEAM)), plane_no=Vector((0, 0, 1)))
cut = [e for e in res["geom_cut"] if isinstance(e, bmesh.types.BMEdge)]
half = W / 2 * 1.0
seam_edges = []
for e in cut:
    a, b = e.verts
    mid = (a.co + b.co) / 2
    if abs(mid.x - CX) < half and mid.y < MOUTH_Y + 0.012:
        seam_edges.append(e)
bmesh.ops.split_edges(bm, edges=seam_edges)
print("arestas cortadas na boca:", len(seam_edges))
bm.to_mesh(obj.data)
bm.free()
obj.data.update()

# ---------- 4. interior da boca e dentes ----------
def add_material(name, rgb):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*rgb, 1)
    bsdf.inputs["Roughness"].default_value = 1.0
    bsdf.inputs["Metallic"].default_value = 0.0
    return m

mat_mouth = add_material("BocaInterior", (0.035, 0.008, 0.008))
mat_teeth = add_material("Dentes", (0.78, 0.74, 0.66))

parts = []
# "saco" escuro atrás dos lábios
bpy.ops.mesh.primitive_uv_sphere_add(segments=24, ring_count=12, radius=1.0, location=(CX, MOUTH_Y + 0.020, SEAM - 0.004))
bag = bpy.context.active_object
bag.scale = (W * 0.46, 0.017, 0.017)
bpy.ops.object.transform_apply(scale=True)
bag.data.materials.append(mat_mouth)
parts.append(bag)

# dentes: tira curva que acompanha os lábios, um pouco para dentro
def teeth_strip(z_top, z_bot, inset, name):
    xs = np.linspace(CX - W * 0.36, CX + W * 0.36, 9)
    verts, faces = [], []
    for i, x in enumerate(xs):
        y = front_y(x, SEAM) or MOUTH_Y
        y += inset
        verts += [(x, y, z_top), (x, y, z_bot)]
        if i:
            k = 2 * i
            faces.append((k - 2, k - 1, k + 1, k))
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    me.materials.append(mat_teeth)
    return ob

up_teeth = teeth_strip(SEAM + 0.0015, SEAM - 0.0060, 0.0065, "DentesCima")
lo_teeth = teeth_strip(SEAM - 0.0075, SEAM - 0.0135, 0.0075, "DentesBaixo")
parts += [up_teeth, lo_teeth]
n_lo_teeth = len(lo_teeth.data.vertices)

# juntar tudo num mesh só (o VRM liga as expressões a um mesh)
for p in parts:
    p.select_set(True)
obj.select_set(True)
bpy.context.view_layer.objects.active = obj
bpy.ops.object.join()
obj = bpy.context.view_layer.objects.active
me = obj.data
N = len(me.vertices)
co = np.zeros(N * 3)
me.vertices.foreach_get("co", co)
co = co.reshape(N, 3)
X, Y, Z = co[:, 0], co[:, 1], co[:, 2]

# marcar quem é dente de baixo / interior (pelo material de cada face)
mat_idx = {m.name: i for i, m in enumerate(me.materials) if m}
poly_mat = np.zeros(len(me.polygons), int)
me.polygons.foreach_get("material_index", poly_mat)
is_teeth = np.zeros(N, bool)
is_bag = np.zeros(N, bool)
for p in me.polygons:
    if p.material_index == mat_idx.get("Dentes"):
        is_teeth[list(p.vertices)] = True
    if p.material_index == mat_idx.get("BocaInterior"):
        is_bag[list(p.vertices)] = True
is_skin = ~(is_teeth | is_bag)

# ---------- 5. pesos do queixo (mandíbula) ----------
PIV = np.array([CX, MOUTH_Y + 0.085, SEAM + 0.030])  # articulação, atrás e acima da boca
dx = np.abs(X - CX)
lower = Z < SEAM - 1e-5
w_h = 1 - smooth(0.040, 0.068, dx)
w_down = smooth(CHIN - 0.050, CHIN - 0.010, Z)  # pescoço embaixo do queixo estica pouco
w_front = 1 - smooth(PIV[1] - 0.03, PIV[1] + 0.01, Y)
# abertura em forma de lente: logo abaixo do corte o peso cai do meio (1) para os cantos (0); mais abaixo, queixo inteiro
lens = np.clip(1 - (dx / (W / 2 * 1.05)) ** 2, 0, 1)
near = 1 - smooth(0.0, 0.014, SEAM - Z)
w_corner = near * lens + (1 - near)
jaw = np.where(lower & is_skin, w_h * w_down * w_front * w_corner, 0.0)
jaw = np.where(is_bag, smooth(SEAM + 0.004, SEAM - 0.010, Z), jaw)
teeth_lo = is_teeth & (Z < SEAM - 0.007)
jaw = np.where(teeth_lo, 1.0, jaw)
jaw = np.where(is_teeth & ~teeth_lo, 0.0, jaw)
# só a cabeça (nada do corpo)
jaw = np.where(Z < NECK - 0.06, 0.0, jaw)


def rot_x(points, angle_per_vertex):
    """Gira cada ponto em torno do eixo X que passa por PIV (abre a boca para baixo)."""
    p = points - PIV
    c, s = np.cos(angle_per_vertex), np.sin(angle_per_vertex)
    y2 = p[:, 1] * c - p[:, 2] * s
    z2 = p[:, 1] * s + p[:, 2] * c
    out = p.copy()
    out[:, 1], out[:, 2] = y2, z2
    return out + PIV


def jaw_open(angle):
    # ângulo positivo em X (pivô atrás e acima da boca): queixo desce e recua um pouco
    return rot_x(co, angle * jaw) - co


# região dos lábios (para franzir/esticar)
lip_r = np.sqrt(((X - CX) / (W / 2 + 0.010)) ** 2 + ((Z - SEAM) / 0.016) ** 2)
lip_w = np.where(is_skin & (Y < MOUTH_Y + 0.02), 1 - smooth(0.55, 1.0, lip_r), 0.0)
corner_w = []
for xc in (xl, xr):
    r = np.sqrt((X - xc) ** 2 + (Z - SEAM) ** 2)
    corner_w.append(np.where(is_skin & (Y < MOUTH_Y + 0.02), 1 - smooth(0.004, 0.016, r), 0.0))
corners = corner_w[0] + corner_w[1]


def pucker(k, forward):
    d = np.zeros_like(co)
    d[:, 0] = -(X - CX) * k * lip_w
    d[:, 1] = -forward * lip_w
    return d


def widen(k, up=0.0):
    d = np.zeros_like(co)
    d[:, 0] = (X - CX) * k * lip_w
    d[:, 2] = up * corners
    return d


def corners_move(dz, dx_out=0.0):
    d = np.zeros_like(co)
    d[:, 2] = dz * corners
    d[:, 0] = np.sign(X - CX) * dx_out * corners
    return d


# olhos
def eye_masks():
    lids = []
    for ex, ez in EYES:
        r = np.sqrt(((X - ex) / (EW * 1.35)) ** 2 + ((Z - ez) / (EH * 2.6)) ** 2)
        f = np.where(is_skin & (Y < MOUTH_Y + 0.03), 1 - smooth(0.55, 1.0, r), 0.0)
        lids.append((ex, ez, f))
    return lids

LIDS = eye_masks()


def blink(amount=1.0):
    d = np.zeros_like(co)
    for ex, ez, f in LIDS:
        upper = Z >= ez
        target_up = ez - EH * 0.25
        d[:, 2] += np.where(upper, (target_up - Z) * f * amount, 0.0)
        d[:, 2] += np.where(~upper, (ez - EH * 0.25 - Z) * 0.55 * f * amount, 0.0)
    return d


def widen_eyes(k):
    d = np.zeros_like(co)
    for ex, ez, f in LIDS:
        d[:, 2] += np.where(Z >= ez, k * f, -k * 0.4 * f)
    return d


def brows(dz_inner, dz_outer, dx_in=0.0):
    d = np.zeros_like(co)
    for ex, ez, _ in LIDS:
        band = np.where(is_skin & (Y < MOUTH_Y + 0.03), 1.0, 0.0)
        r = np.sqrt(((X - ex) / (EW * 1.9)) ** 2 + ((Z - BROW) / (EH * 2.2)) ** 2)
        f = band * (1 - smooth(0.5, 1.0, r))
        inward = np.sign(CX - ex)  # direção do meio do rosto
        t = np.clip((X - ex) * inward / (EW * 1.6) * 0.5 + 0.5, 0, 1)  # 1 = lado de dentro
        d[:, 2] += f * (dz_inner * t + dz_outer * (1 - t))
        d[:, 0] += f * t * dx_in * inward
    return d


def cheeks(dz):
    d = np.zeros_like(co)
    for ex, ez, _ in LIDS:
        r = np.sqrt(((X - ex) / 0.022) ** 2 + ((Z - (ez - 0.025)) / 0.014) ** 2)
        d[:, 2] += np.where(is_skin & (Y < MOUTH_Y + 0.03), (1 - smooth(0.4, 1.0, r)) * dz, 0.0)
    return d


SHAPES = {
    "aa": jaw_open(0.17),
    "ih": jaw_open(0.07) + widen(0.07),
    "ou": jaw_open(0.045) + pucker(0.30, 0.005),
    "ee": jaw_open(0.055) + widen(0.12, 0.0012),
    "oh": jaw_open(0.11) + pucker(0.20, 0.003),
    "blink": blink(1.0),
    "happy": corners_move(0.0065, 0.0020) + cheeks(0.0030) + blink(0.25),
    "sad": corners_move(-0.0050) + brows(0.0055, -0.0015),
    "angry": brows(-0.0060, 0.0015, 0.0022) + corners_move(-0.0022) + blink(0.18),
    "surprised": brows(0.0075, 0.0060) + widen_eyes(0.0016) + jaw_open(0.09),
    "relaxed": corners_move(0.0030) + blink(0.40),
}

obj.shape_key_add(name="Basis", from_mix=False)
for name, d in SHAPES.items():
    sk = obj.shape_key_add(name=name, from_mix=False)
    sk.data.foreach_set("co", (co + d).reshape(-1))
print("formas:", list(SHAPES))

# ---------- 6. esqueleto humanoide ----------
H = max(Z[is_skin])
HIPS = M["hips_z"]
SHZ = M["shoulder_z"]
SHX = M["shoulder_x"]
arm_data = bpy.data.armatures.new("Armature")
arm = bpy.data.objects.new("Armature", arm_data)
bpy.context.scene.collection.objects.link(arm)
bpy.context.view_layer.objects.active = arm
bpy.ops.object.mode_set(mode="EDIT")
eb = arm_data.edit_bones


def bone(name, head, tail, parent=None):
    b = eb.new(name)
    b.head, b.tail = Vector(head), Vector(tail)
    b.roll = 0
    if parent:
        b.parent = eb[parent]
        b.use_connect = False
    return b


HEAD_BASE = NECK + 0.02
band = is_skin & (np.abs(Z - (HIPS + 0.30)) < 0.05)
TY = float(np.median(Y[band])) if band.any() else 0.0  # meio do tronco (profundidade)
HY = MOUTH_Y + 0.075  # meio da cabeça (profundidade)
bone("hips", (CX, TY, HIPS), (CX, TY, HIPS + 0.10))
bone("spine", (CX, TY, HIPS + 0.10), (CX, TY, HIPS + 0.24), "hips")
bone("chest", (CX, TY, HIPS + 0.24), (CX, TY, SHZ - 0.02), "spine")
bone("neck", (CX, (TY + HY) / 2, SHZ - 0.02), (CX, HY, HEAD_BASE), "chest")
bone("head", (CX, HY, HEAD_BASE), (CX, HY, H), "neck")
for side, sx in (("left", 1), ("right", -1)):
    o = lambda dx, dy, z: (CX + sx * dx, TY + dy, z)
    bone(f"{side}Shoulder", o(0.04, 0, SHZ - 0.03), o(SHX * 0.8, 0, SHZ - 0.02), "chest")
    bone(f"{side}UpperArm", o(SHX * 0.8, 0, SHZ - 0.02), o(SHX + 0.06, 0, SHZ - 0.30), f"{side}Shoulder")
    bone(f"{side}LowerArm", o(SHX + 0.06, 0, SHZ - 0.30), o(SHX + 0.10, -0.02, SHZ - 0.55), f"{side}UpperArm")
    bone(f"{side}Hand", o(SHX + 0.10, -0.02, SHZ - 0.55), o(SHX + 0.11, -0.03, SHZ - 0.65), f"{side}LowerArm")
    bone(f"{side}UpperLeg", o(0.10, 0, HIPS - 0.02), o(0.11, 0, HIPS * 0.53), "hips")
    bone(f"{side}LowerLeg", o(0.11, 0, HIPS * 0.53), o(0.12, 0, 0.08), f"{side}UpperLeg")
    bone(f"{side}Foot", o(0.12, 0, 0.08), o(0.12, -0.12, 0.02), f"{side}LowerLeg")
bpy.ops.object.mode_set(mode="OBJECT")

# pesos: tronco por altura; cabeça por uma esfera (não pega a pele do ombro); braços e pernas ficam parados
hc = np.array([CX, MOUTH_Y + 0.06, (SEAM + H) / 2])
dist_head = np.sqrt(((X - hc[0]) / 1.0) ** 2 + ((Y - hc[1]) / 1.05) ** 2 + ((Z - hc[2]) / 1.25) ** 2)
w_head = (1 - smooth(0.13, 0.17, dist_head)) * smooth(NECK - 0.03, NECK + 0.01, Z)
w_head = np.where(is_skin, w_head, 1.0)
w_neck = (1 - w_head) * smooth(SHZ - 0.05, SHZ + 0.02, Z) * (1 - smooth(0.06, 0.12, np.sqrt((X - CX) ** 2 + (Y - hc[1]) ** 2)))
rest = 1 - w_head - w_neck
w_chest = rest * smooth(HIPS + 0.14, HIPS + 0.30, Z)
w_spine = rest * (1 - smooth(HIPS + 0.14, HIPS + 0.30, Z)) * smooth(HIPS - 0.02, HIPS + 0.10, Z)
w_hips = rest - w_chest - w_spine
groups = {}
for name in [b.name for b in arm_data.bones]:
    groups[name] = obj.vertex_groups.new(name=name)
for name, w in (("head", w_head), ("neck", w_neck), ("chest", w_chest), ("spine", w_spine), ("hips", w_hips)):
    g = groups[name]
    for i in np.nonzero(w > 1e-3)[0]:
        g.add([int(i)], float(w[i]), "REPLACE")
mod = obj.modifiers.new("Armature", "ARMATURE")
mod.object = arm
obj.parent = arm

# ---------- 7. exportar ----------
bpy.ops.object.select_all(action="SELECT")
bpy.ops.export_scene.gltf(
    filepath=str(out_glb),
    export_format="GLB",
    use_selection=True,
    export_yup=True,
    export_apply=False,
    export_morph=True,
    export_morph_normal=False,
    export_skins=True,
    export_animations=False,
    export_image_format="JPEG",
    export_image_quality=90,
)
print("exportado", out_glb, out_glb.stat().st_size)
