import shutil, time, traceback
from pathlib import Path
from gradio_client import Client, handle_file

base = Path(__file__).resolve().parent
log = open(base / "trellis1_log.txt", "w", encoding="utf-8", buffering=1)
def say(*a): print(time.strftime("%H:%M:%S"), *a, file=log)
try:
    tok_file = base / "hf_token.txt"
    tok = tok_file.read_text(encoding="utf-8-sig").strip() if tok_file.exists() else None
    say("token:", "sim" if tok else "nao")
    c = Client("trellis-community/TRELLIS", verbose=False, token=tok)
    try: c.predict(api_name="/start_session")
    except Exception as e: say("start_session:", e)
    say("generate_and_extract_glb")
    r = c.predict(handle_file(str(base / "guardiao_input.png")), [], 42, 7.5, 12, 3.0, 12,
                  "stochastic", 0.9, 2048, api_name="/generate_and_extract_glb")
    say("->", r)
    glb = r[1]
    if isinstance(glb, dict): glb = glb.get("path") or glb.get("value")
    shutil.copy(glb, base / "guardiao_trellis.glb")
    vid = r[0]
    if isinstance(vid, dict): vid = vid.get("video")
    if vid: shutil.copy(vid, base / "guardiao_trellis_preview.mp4")
    say("PRONTO", (base / "guardiao_trellis.glb").stat().st_size)
except Exception:
    say("ERRO\n" + traceback.format_exc()[-1500:])
