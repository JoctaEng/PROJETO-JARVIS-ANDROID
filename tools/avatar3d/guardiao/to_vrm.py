"""Etapa 3: GLB (esqueleto + morph targets) -> VRM 1.0 que o avatar do Euno (three-vrm) entende.

Uso: python to_vrm.py entrada.glb saida.vrm
- humanoid: liga os ossos pelo nome (hips, spine, chest, neck, head, braços, pernas)
- expressions: aa ih ou ee oh blink happy sad angry surprised relaxed -> morph target de mesmo nome
- materiais "unlit" (a textura do TRELLIS/TripoSR já traz a luz da imagem)
"""
import sys

from pygltflib import GLTF2

src, dst = sys.argv[1], sys.argv[2]
g = GLTF2().load(src)

node_by_name = {n.name: i for i, n in enumerate(g.nodes)}
BONES = ["hips", "spine", "chest", "neck", "head"]
for side in ("left", "right"):
    BONES += [f"{side}Shoulder", f"{side}UpperArm", f"{side}LowerArm", f"{side}Hand",
              f"{side}UpperLeg", f"{side}LowerLeg", f"{side}Foot"]
human = {}
for b in BONES:
    if b not in node_by_name:
        raise SystemExit(f"osso faltando: {b}")
    human[b] = {"node": node_by_name[b]}

# mesh com morph targets
mesh_node = None
target_names = []
for i, n in enumerate(g.nodes):
    if n.mesh is not None:
        m = g.meshes[n.mesh]
        names = (m.extras or {}).get("targetNames") or []
        if names:
            mesh_node, target_names = i, names
            break
if mesh_node is None:
    raise SystemExit("nenhum mesh com morph targets")

PRESETS = ["aa", "ih", "ou", "ee", "oh", "blink", "happy", "sad", "angry", "surprised", "relaxed"]
preset = {}
for name in PRESETS:
    if name not in target_names:
        raise SystemExit(f"forma faltando: {name}")
    e = {"morphTargetBinds": [{"node": mesh_node, "index": target_names.index(name), "weight": 1.0}],
         "isBinary": False, "overrideBlink": "none", "overrideLookAt": "none", "overrideMouth": "none"}
    if name in ("happy", "relaxed", "angry", "sad"):
        e["overrideBlink"] = "blend"
    preset[name] = e

g.extensions = g.extensions or {}
g.extensions["VRMC_vrm"] = {
    "specVersion": "1.0",
    "meta": {
        "name": "Guardião",
        "version": "1.0",
        "authors": ["Euno"],
        "copyrightInformation": "Personagem do Euno; escultura gerada por IA (código MIT) e montada no Blender.",
        "licenseUrl": "https://vrm.dev/licenses/1.0/",
        "avatarPermission": "onlyAuthor",
        "allowExcessivelyViolentUsage": False,
        "allowExcessivelySexualUsage": False,
        "commercialUsage": "personalNonProfit",
        "allowPoliticalOrReligiousUsage": False,
        "allowAntisocialOrHateUsage": False,
        "creditNotation": "required",
        "allowRedistribution": False,
        "modification": "prohibited",
    },
    "humanoid": {"humanBones": human},
    "expressions": {"preset": preset, "custom": {}},
}
used = set(g.extensionsUsed or [])
used.update(["VRMC_vrm", "KHR_materials_unlit"])
g.extensionsUsed = sorted(used)
for m in g.materials:
    m.extensions = m.extensions or {}
    m.extensions["KHR_materials_unlit"] = {}
    if m.pbrMetallicRoughness:
        m.pbrMetallicRoughness.metallicFactor = 0.0
        m.pbrMetallicRoughness.roughnessFactor = 1.0
    m.doubleSided = True
g.save_binary(dst)
print("VRM salvo:", dst, "| mesh node", mesh_node, "| formas", target_names)
