"""Projeta a arte original (vista de frente) sobre o modelo e grava numa textura nova (bake no Cycles).

Uso: python projetar.py base.blend alinhamento.json fonte_rgba.png saida_dir [tam_textura]
Só recebe a arte o que está de frente e visível (raio vindo da frente acerta o próprio ponto) e dentro da silhueta;
o resto fica com a textura do TripoSR. Gera saida_dir/textura.png e saida_dir/base_proj.blend.
"""
import json
import sys
from pathlib import Path

import bpy
import numpy as np
from mathutils import Vector

blend, alin, src, out = sys.argv[1], json.load(open(sys.argv[2])), sys.argv[3], Path(sys.argv[4])
size = int(sys.argv[5]) if len(sys.argv) > 5 else 2048
out.mkdir(parents=True, exist_ok=True)
bpy.ops.wm.open_mainfile(filepath=blend)
for o in list(bpy.context.scene.objects):
    if o.type != "MESH":
        bpy.data.objects.remove(o, do_unlink=True)
obj = bpy.data.objects["Guardiao"]
me = obj.data
a, bx, bz, W, H = alin["a"], alin["bx"], alin["bz"], alin["src_w"], alin["src_h"]

src_img = bpy.data.images.load(src)
src_px = np.asarray(src_img.pixels[:]).reshape(src_img.size[1], src_img.size[0], 4)[::-1]  # linha 0 = topo
alpha = src_px[..., 3]

N = len(me.vertices)
co = np.zeros(N * 3); me.vertices.foreach_get("co", co); co = co.reshape(N, 3)
me.calc_normals_split() if hasattr(me, "calc_normals_split") else None
nor = np.zeros(N * 3); me.vertices.foreach_get("normal", nor); nor = nor.reshape(N, 3)

# visível de frente?
vis = np.zeros(N)
for i in range(N):
    x, y, z = co[i]
    ok, loc, n, idx = obj.ray_cast(Vector((x, -3.0, z)), Vector((0, 1, 0)))
    if ok and loc.y > y - 0.03:
        vis[i] = 1.0
px = (co[:, 0] - bx) / a
py = (bz - co[:, 2]) / a
inside = (px >= 0) & (px < W - 1) & (py >= 0) & (py < H - 1)
al = np.zeros(N)
al[inside] = alpha[py[inside].astype(int), px[inside].astype(int)]
facing = np.clip((-nor[:, 1] - 0.05) / 0.35, 0, 1)
w = vis * facing * np.clip((al - 0.3) / 0.5, 0, 1)
# suaviza o peso pelos vizinhos (tira faixas finas onde o teste de visibilidade falhou)
ev = np.zeros(len(me.edges) * 2, int); me.edges.foreach_get("vertices", ev); ev = ev.reshape(-1, 2)
for _ in range(3):
    acc = np.zeros(N); cnt = np.zeros(N)
    np.add.at(acc, ev[:, 0], w[ev[:, 1]]); np.add.at(acc, ev[:, 1], w[ev[:, 0]])
    np.add.at(cnt, ev[:, 0], 1); np.add.at(cnt, ev[:, 1], 1)
    w = np.maximum(w, acc / np.maximum(cnt, 1)) * (facing > 0.05) * (al > 0.2)
print("vértices com a arte:", int((w > 0.5).sum()), "de", N)

# UV de projeção e peso por vértice (atributo de cor)
uv_proj = me.uv_layers.new(name="proj")
loops = np.zeros(len(me.loops), int); me.loops.foreach_get("vertex_index", loops)
uvp = np.stack([px[loops] / W, 1 - py[loops] / H], axis=1)
uv_proj.data.foreach_set("uv", uvp.reshape(-1))
attr = me.color_attributes.new(name="projw", type="FLOAT_COLOR", domain="POINT")
col = np.repeat(w[:, None], 4, axis=1); col[:, 3] = 1
attr.data.foreach_set("color", col.reshape(-1))
orig_uv = [l.name for l in me.uv_layers if l.name != "proj"][0]
me.uv_layers.active = me.uv_layers[orig_uv]

mat = me.materials[0]
nt = mat.node_tree
orig_tex = [n for n in nt.nodes if n.type == "TEX_IMAGE"][0]
uvn1 = nt.nodes.new("ShaderNodeUVMap"); uvn1.uv_map = orig_uv
nt.links.new(uvn1.outputs["UV"], orig_tex.inputs["Vector"])
t2 = nt.nodes.new("ShaderNodeTexImage"); t2.image = src_img; t2.extension = "EXTEND"
uvn2 = nt.nodes.new("ShaderNodeUVMap"); uvn2.uv_map = "proj"
nt.links.new(uvn2.outputs["UV"], t2.inputs["Vector"])
ca = nt.nodes.new("ShaderNodeVertexColor"); ca.layer_name = "projw"
mix = nt.nodes.new("ShaderNodeMix"); mix.data_type = "RGBA"
nt.links.new(ca.outputs["Color"], mix.inputs["Factor"])
nt.links.new(orig_tex.outputs["Color"], mix.inputs[6])
nt.links.new(t2.outputs["Color"], mix.inputs[7])
emi = nt.nodes.new("ShaderNodeEmission")
nt.links.new(mix.outputs[2], emi.inputs["Color"])
outn = [n for n in nt.nodes if n.type == "OUTPUT_MATERIAL"][0]
nt.links.new(emi.outputs["Emission"], outn.inputs["Surface"])
target = bpy.data.images.new("textura_guardiao", size, size, alpha=False)
tn = nt.nodes.new("ShaderNodeTexImage"); tn.image = target
nt.nodes.active = tn
for n in nt.nodes: n.select = False
tn.select = True

sc = bpy.context.scene
sc.render.engine = "CYCLES"; sc.cycles.device = "CPU"; sc.cycles.samples = 1
bpy.context.view_layer.objects.active = obj
obj.select_set(True)
sc.render.bake.margin = 8
bpy.ops.object.bake(type="EMIT", margin=8, use_clear=True)
target.filepath_raw = str(out / "textura.png"); target.file_format = "PNG"; target.save()
print("textura salva", out / "textura.png")

# material final: só a textura nova, sem a projeção
for n in list(nt.nodes):
    if n.type not in ("OUTPUT_MATERIAL", "BSDF_PRINCIPLED"):
        nt.nodes.remove(n)
bsdf = [n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"][0]
fin = nt.nodes.new("ShaderNodeTexImage"); fin.image = bpy.data.images.load(str(out / "textura.png")); fin.image.pack()
nt.links.new(fin.outputs["Color"], bsdf.inputs["Base Color"])
nt.links.new(bsdf.outputs["BSDF"], outn.inputs["Surface"])
me.uv_layers.remove(me.uv_layers["proj"])
me.color_attributes.remove(me.color_attributes["projw"])
bpy.ops.wm.save_as_mainfile(filepath=str(out / "base_proj.blend"))
print("ok")
