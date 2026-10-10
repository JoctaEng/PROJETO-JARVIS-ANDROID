#!/usr/bin/env python3
"""
Diminui um modelo VRM/GLB para caber bem no APK: texturas no máximo 1024 px; as sem transparência viram JPEG.
Mantém malhas, ossos, expressões e metadados (licença). Uso: otimizar_vrm.py entrada.vrm saida.vrm [lado_max]
"""
import io
import json
import struct
import sys

from PIL import Image


def main(src, dst, max_side=1024):
    b = open(src, "rb").read()
    assert b[:4] == b"glTF", "não é GLB"
    jlen = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + jlen])
    off = 20 + jlen
    blen = struct.unpack("<I", b[off:off + 4])[0]
    binb = b[off + 8:off + 8 + blen]
    views = j["bufferViews"]
    data = [binb[v.get("byteOffset", 0):v.get("byteOffset", 0) + v["byteLength"]] for v in views]
    for img in j.get("images", []):
        if "bufferView" not in img:
            continue
        i = img["bufferView"]
        im = Image.open(io.BytesIO(data[i]))
        im.load()
        if max(im.size) > max_side:
            im.thumbnail((max_side, max_side), Image.LANCZOS)
        out = io.BytesIO()
        has_alpha = im.mode in ("RGBA", "LA") and im.getchannel("A").getextrema()[0] < 255
        if has_alpha:
            im.save(out, "PNG", optimize=True)
            img["mimeType"] = "image/png"
        else:
            im.convert("RGB").save(out, "JPEG", quality=88, optimize=True)
            img["mimeType"] = "image/jpeg"
        data[i] = out.getvalue()
    newbin = bytearray()
    for v, d in zip(views, data):
        while len(newbin) % 4:
            newbin.append(0)
        v["byteOffset"] = len(newbin)
        v["byteLength"] = len(d)
        v["buffer"] = 0
        newbin += d
    while len(newbin) % 4:
        newbin.append(0)
    j["buffers"] = [{"byteLength": len(newbin)}]
    js = json.dumps(j, separators=(",", ":"), ensure_ascii=False).encode()
    while len(js) % 4:
        js += b" "
    total = 12 + 8 + len(js) + 8 + len(newbin)
    with open(dst, "wb") as f:
        f.write(struct.pack("<4sII", b"glTF", 2, total))
        f.write(struct.pack("<I4s", len(js), b"JSON"))
        f.write(js)
        f.write(struct.pack("<I4s", len(newbin), b"BIN\0"))
        f.write(newbin)
    print(f"{dst}: {len(b) // 1024} KB → {total // 1024} KB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 1024)
