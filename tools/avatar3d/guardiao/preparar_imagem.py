import sys
import numpy as np
from PIL import Image, ImageDraw
from rembg import remove, new_session

src, out = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGB").crop((0, 0, 500, 1000))  # sem a pedra
sess = new_session("isnet-general-use")
cut = remove(im, session=sess)  # RGBA
a = np.array(cut)
mask = Image.new("L", im.size, 255)
d = ImageDraw.Draw(mask)
# lobo
d.polygon([(355,505),(392,505),(400,480),(432,480),(445,462),(500,462),(500,1000),(280,1000),(282,930),
           (295,840),(318,760),(330,690),(335,600),(345,520)], fill=0)
# logo EUNO
d.rectangle((335,15,500,105), fill=0)
m = np.array(mask) > 0
a[..., 3] = np.where(m, a[..., 3], 0)
from scipy import ndimage
solid = a[..., 3] > 128
lab, n = ndimage.label(solid)
if n > 1:
    sizes = ndimage.sum(solid, lab, range(1, n + 1))
    keep = lab == (int(np.argmax(sizes)) + 1)
    keep = ndimage.binary_dilation(keep, iterations=2)
    a[..., 3] = np.where(keep, a[..., 3], 0)
a[..., 3] = np.where(a[..., 3] < 40, 0, a[..., 3])
res = Image.fromarray(a)
bbox = res.getbbox()
res = res.crop(bbox)
# quadrado com margem, fundo transparente
w, h = res.size
s = int(max(w, h) * 1.08)
canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))
canvas.paste(res, ((s - w) // 2, (s - h) // 2), res)
canvas.save(out)
# prévia sobre cinza
prev = Image.new("RGBA", canvas.size, (128, 128, 128, 255))
prev.alpha_composite(canvas)
prev.convert("RGB").save(out.replace(".png", "_preview.png"))
print("bbox", bbox, "final", canvas.size)
