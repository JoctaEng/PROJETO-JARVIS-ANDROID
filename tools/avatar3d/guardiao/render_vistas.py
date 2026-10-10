"""Renderiza vistas de um .blend (frente, cabeça de frente, 3/4 da cabeça). Uso: python render_vistas.py arq.blend saida_dir"""
import math
import sys
from pathlib import Path

import bpy

bpy.ops.wm.open_mainfile(filepath=sys.argv[1])
out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
sc = bpy.context.scene
sc.render.engine = "CYCLES"; sc.cycles.device = "CPU"; sc.cycles.samples = 8; sc.cycles.use_denoising = False
for o in list(sc.objects):
    if o.type == "CAMERA":
        bpy.data.objects.remove(o, do_unlink=True)
cd = bpy.data.cameras.new("c"); cam = bpy.data.objects.new("c", cd); sc.collection.objects.link(cam); sc.camera = cam
obj = bpy.data.objects["Guardiao"]
top = max((obj.matrix_world @ v.co).z for v in obj.data.vertices)


def shot(name, loc, rot, ortho, res, persp=False):
    cd.type = "PERSP" if persp else "ORTHO"
    if persp:
        cd.lens = 85
    cd.ortho_scale = ortho
    cam.location = loc; cam.rotation_euler = rot
    sc.render.resolution_x = sc.render.resolution_y = res
    sc.render.filepath = str(out / f"{name}.png")
    bpy.ops.render.render(write_still=True)


hz = top - 0.15
cx = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
shot("frente", (0, -5, 0.9), (math.radians(90), 0, 0), 1.95, 600)
shot("cabeca", (cx, -5, hz), (math.radians(90), 0, 0), 0.34, 600)
r = 1.4
ang = math.radians(25)
shot("cabeca34", (cx + r * math.sin(ang), -r * math.cos(ang), hz), (math.radians(90), 0, ang), 0.34, 600, persp=True)
