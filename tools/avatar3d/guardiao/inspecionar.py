"""Etapa 1: importa o GLB do TRELLIS, normaliza (1,80 m, pés no chão) e renderiza vistas para achar boca e olhos.

Uso: python inspecionar.py entrada.glb pasta_saida [rot_z_graus]
Gera: base.blend, vistas front/back/side/head (PNG) e mapa.json (pixel -> mundo nas vistas ortográficas).
"""
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector

src, out = Path(sys.argv[1]), Path(sys.argv[2])
rot_z = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
rot_x = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
tex = Path(sys.argv[5]) if len(sys.argv) > 5 else None
out.mkdir(parents=True, exist_ok=True)
HEIGHT = 1.80

bpy.ops.wm.read_factory_settings(use_empty=True)
if src.suffix.lower() == ".obj":
    bpy.ops.wm.obj_import(filepath=str(src), forward_axis="NEGATIVE_Z", up_axis="Y")
    if tex:
        mat = bpy.data.materials.new("Pele")
        mat.use_nodes = True
        nt = mat.node_tree
        bsdf = nt.nodes["Principled BSDF"]
        img = nt.nodes.new("ShaderNodeTexImage")
        img.image = bpy.data.images.load(str(tex))
        img.image.pack()
        nt.links.new(img.outputs["Color"], bsdf.inputs["Base Color"])
        bsdf.inputs["Roughness"].default_value = 1.0
        for o in bpy.context.scene.objects:
            if o.type == "MESH":
                o.data.materials.clear()
                o.data.materials.append(mat)
else:
    bpy.ops.import_scene.gltf(filepath=str(src))
meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
bpy.ops.object.select_all(action="DESELECT")
for o in meshes:
    o.select_set(True)
bpy.context.view_layer.objects.active = meshes[0]
if len(meshes) > 1:
    bpy.ops.object.join()
obj = bpy.context.view_layer.objects.active
obj.name = "Guardiao"
# solta de pais/empties e aplica transformações
bpy.ops.object.parent_clear(type="CLEAR_KEEP_TRANSFORM")
for o in list(bpy.context.scene.objects):
    if o.type != "MESH":
        bpy.data.objects.remove(o, do_unlink=True)
import bmesh
_bm = bmesh.new(); _bm.from_mesh(obj.data)
bmesh.ops.remove_doubles(_bm, verts=_bm.verts, dist=1e-6)
_bm.to_mesh(obj.data); _bm.free(); obj.data.update()
obj.rotation_euler[0] += math.radians(rot_x)
obj.rotation_euler[2] += math.radians(rot_z)
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)

vs = [v.co for v in obj.data.vertices]
mn = Vector((min(v.x for v in vs), min(v.y for v in vs), min(v.z for v in vs)))
mx = Vector((max(v.x for v in vs), max(v.y for v in vs), max(v.z for v in vs)))
s = HEIGHT / (mx.z - mn.z)
obj.scale = (s, s, s)
bpy.ops.object.transform_apply(scale=True)
vs = [v.co for v in obj.data.vertices]
mn = Vector((min(v.x for v in vs), min(v.y for v in vs), min(v.z for v in vs)))
mx = Vector((max(v.x for v in vs), max(v.y for v in vs), max(v.z for v in vs)))
obj.location = (-(mn.x + mx.x) / 2, -(mn.y + mx.y) / 2, -mn.z)
bpy.ops.object.transform_apply(location=True)
vs = [v.co for v in obj.data.vertices]
mn = Vector((min(v.x for v in vs), min(v.y for v in vs), min(v.z for v in vs)))
mx = Vector((max(v.x for v in vs), max(v.y for v in vs), max(v.z for v in vs)))

info = {
    "faces": len(obj.data.polygons),
    "verts": len(obj.data.vertices),
    "bbox_min": list(mn),
    "bbox_max": list(mx),
    "materials": [m.name for m in obj.data.materials],
}

# Render: Cycles na CPU, luz uniforme (mostra a textura como é).
sc = bpy.context.scene
sc.render.engine = "CYCLES"
sc.cycles.device = "CPU"
sc.cycles.samples = 8
sc.cycles.use_denoising = False
sc.render.film_transparent = False
world = bpy.data.worlds.new("w")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (1, 1, 1, 1)
world.node_tree.nodes["Background"].inputs[1].default_value = 1.0
sc.world = world
sc.view_settings.view_transform = "Standard"

cam_data = bpy.data.cameras.new("cam")
cam_data.type = "ORTHO"
cam = bpy.data.objects.new("cam", cam_data)
sc.collection.objects.link(cam)
sc.camera = cam


def render(name, loc, rot, ortho, res):
    cam.location = loc
    cam.rotation_euler = rot
    cam_data.ortho_scale = ortho
    sc.render.resolution_x = res
    sc.render.resolution_y = res
    sc.render.filepath = str(out / f"{name}.png")
    bpy.ops.render.render(write_still=True)
    return {"loc": list(loc), "ortho": ortho, "res": res}


views = {}
D = 5.0
views["front"] = render("front", (0, -D, HEIGHT / 2), (math.radians(90), 0, 0), 1.95, 700)   # olha +Y
views["back"] = render("back", (0, D, HEIGHT / 2), (math.radians(90), 0, math.radians(180)), 1.95, 700)
views["side"] = render("side", (D, 0, HEIGHT / 2), (math.radians(90), 0, math.radians(90)), 1.95, 700)  # olha -X
views["left"] = render("left", (-D, 0, HEIGHT / 2), (math.radians(90), 0, math.radians(-90)), 1.95, 700)
# cabeça (de frente): parte de cima do corpo
hz = mx.z - 0.14
views["head"] = render("head", (0, -D, hz), (math.radians(90), 0, 0), 0.40, 800)
views["head_side"] = render("head_side", (D, 0, hz), (math.radians(90), 0, math.radians(90)), 0.40, 800)
info["views"] = views
(out / "mapa.json").write_text(json.dumps(info, indent=1))
bpy.ops.wm.save_as_mainfile(filepath=str(out / "base.blend"))
print(json.dumps({k: info[k] for k in ("faces", "verts", "bbox_min", "bbox_max", "materials")}))
