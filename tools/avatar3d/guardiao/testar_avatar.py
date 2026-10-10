"""Testa um .vrm na página real do avatar do Euno (assets/avatar3d) num Chromium sem tela.

Uso: python testar_avatar.py pasta_assets_avatar3d modelo_relativo saida_dir [busto|corpo]
Tira fotos: neutro, aa, ih, ou, ee, oh, piscar, happy, sad, angry, surprised, relaxed; e monta uma grade.
"""
import functools
import http.server
import json
import socketserver
import sys
import threading
import time
from pathlib import Path

from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright

assets, model, out = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
framing = sys.argv[4] if len(sys.argv) > 4 else "busto"
zoom = sys.argv[5] if len(sys.argv) > 5 else None  # "x0,y0,x1,y1" em fração da tela
out.mkdir(parents=True, exist_ok=True)

Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(assets))
Handler.log_message = lambda *a, **k: None
httpd = socketserver.TCPServer(("127.0.0.1", 0), Handler)
port = httpd.server_address[1]
threading.Thread(target=httpd.serve_forever, daemon=True).start()

shots = [
    ("neutro", "idle", {}, None),
    ("A (aa)", "speaking", {"aa": 1}, None),
    ("I (ih)", "speaking", {"ih": 1}, None),
    ("U (ou)", "speaking", {"ou": 1}, None),
    ("E (ee)", "speaking", {"ee": 1}, None),
    ("O (oh)", "speaking", {"oh": 1}, None),
    ("olhos fechados", "sleeping", {}, None),
    ("feliz", "idle", {}, "happy"),
    ("triste", "idle", {}, "sad"),
    ("bravo", "idle", {}, "angry"),
    ("surpreso", "idle", {}, "surprised"),
    ("relaxado", "idle", {}, "relaxed"),
]
with sync_playwright() as p:
    b = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"])
    page = b.new_page(viewport={"width": 1260, "height": 1560} if zoom else {"width": 420, "height": 520})
    logs = []
    page.on("console", lambda m: logs.append(f"{m.type}: {m.text}"))
    page.on("pageerror", lambda e: logs.append(f"pageerror: {e}"))
    page.goto(f"http://127.0.0.1:{port}/index.html?model={model}")
    t0 = time.time()
    while time.time() - t0 < 120:
        st = json.loads(page.evaluate("window.Avatar ? window.Avatar.status() : '{}'") or "{}")
        if st.get("loaded") or st.get("error"):
            break
        time.sleep(0.5)
    print("status:", st, "em", round(time.time() - t0, 1), "s")
    if not st.get("loaded"):
        print("\n".join(logs[-20:]))
        raise SystemExit(1)
    page.evaluate(f"window.Avatar.setFraming('{framing}')")
    files = []
    for label, mode, mouth, emo in shots:
        page.evaluate(f"window.Avatar.setMode('{mode}'); window.Avatar.setMouth({json.dumps(mouth)}); "
                      f"window.Avatar.setEmotion({json.dumps(emo)}, 1)")
        time.sleep(1.6 if emo else 0.9)
        # pausa o piscar automático nas fotos de boca/emoção (força o valor)
        if mode != "sleeping":
            page.evaluate("window.Avatar.value && null")
        f = out / f"{len(files):02d}.png"
        page.screenshot(path=str(f), omit_background=True)
        files.append((label, f))
        vals = page.evaluate("JSON.stringify(['aa','ih','ou','ee','oh','blink','happy','sad','angry','surprised','relaxed'].map(n => [n, +(window.Avatar.value(n)||0).toFixed(2)]))")
        print(label, vals)
    print("\n".join(l for l in logs if "error" in l.lower())[-2000:])
    b.close()
httpd.shutdown()

# grade 4 x 3 sobre fundo escuro (como no celular)
tw, th = 210, 260
grid = Image.new("RGB", (tw * 4, (th + 22) * 3), (24, 28, 38))
d = ImageDraw.Draw(grid)
for i, (label, f) in enumerate(files):
    im = Image.open(f).convert("RGBA")
    if zoom:
        x0, y0, x1, y1 = [float(t) for t in zoom.split(",")]
        im = im.crop((int(x0 * im.width), int(y0 * im.height), int(x1 * im.width), int(y1 * im.height)))
    im = im.resize((tw, th), Image.LANCZOS)
    x, y = (i % 4) * tw, (i // 4) * (th + 22)
    grid.paste(im, (x, y + 22), im)
    d.text((x + 6, y + 5), label, fill=(230, 230, 230))
name = f"grade_{framing}{'_zoom' if zoom else ''}.png"
grid.save(out / name)
print("grade:", out / name)
