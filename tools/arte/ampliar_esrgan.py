# Uso: python3 tools/arte/ampliar_esrgan.py RealESRGAN_x4plus.pth entrada.png saida.png [...]
# Pesos (BSD-3): https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth (não versionados)
"""Real-ESRGAN x4plus (RRDBNet) em NumPy puro: carrega o .pth sem torch e amplia 4x."""
import pickle, zipfile, sys, collections
import numpy as np
from PIL import Image

def load_pth(path):
    z = zipfile.ZipFile(path)
    prefix = z.namelist()[0].split('/')[0]
    class Storage:
        def __init__(self, key, dtype): self.key, self.dtype = key, dtype
    def rebuild(storage, offset, size, stride, *a):
        raw = np.frombuffer(z.read(f'{prefix}/data/{storage.key}'), dtype=storage.dtype)
        n = int(np.prod(size)) if size else 1
        arr = raw[offset:offset + n] if all(True for _ in [0]) else raw
        return np.lib.stride_tricks.as_strided(raw[offset:], shape=tuple(size), strides=tuple(s * raw.itemsize for s in stride)).copy()
    class U(pickle.Unpickler):
        def find_class(self, mod, name):
            if name == '_rebuild_tensor_v2': return rebuild
            if name == 'OrderedDict': return collections.OrderedDict
            if name.endswith('Storage'):
                return {'FloatStorage': np.float32, 'HalfStorage': np.float16, 'LongStorage': np.int64}[name]
            return super().find_class(mod, name)
        def persistent_load(self, pid):
            _, dtype, key, loc, numel = pid
            return Storage(key, dtype)
    sd = U(z.open(f'{prefix}/data.pkl')).load()
    return sd.get('params_ema', sd.get('params', sd))

def conv(x, w, b):
    c, h, wd = x.shape
    o = w.shape[0]
    xp = np.pad(x, ((0, 0), (1, 1), (1, 1)))
    cols = np.empty((c * 9, h * wd), np.float32)
    k = 0
    for ci in range(c):
        for dy in range(3):
            for dx in range(3):
                cols[k] = xp[ci, dy:dy + h, dx:dx + wd].reshape(-1); k += 1
    return (w.reshape(o, -1).astype(np.float32) @ cols + b[:, None]).reshape(o, h, wd)

def lrelu(x): return np.where(x > 0, x, 0.2 * x)

def run(P, img):
    g = lambda n: (P[n + '.weight'], P[n + '.bias'])
    x = conv(img, *g('conv_first')); fea = x
    for i in range(23):
        r = x
        for j in (1, 2, 3):
            p = f'body.{i}.rdb{j}.'
            x1 = lrelu(conv(r, *g(p + 'conv1')))
            x2 = lrelu(conv(np.concatenate([r, x1]), *g(p + 'conv2')))
            x3 = lrelu(conv(np.concatenate([r, x1, x2]), *g(p + 'conv3')))
            x4 = lrelu(conv(np.concatenate([r, x1, x2, x3]), *g(p + 'conv4')))
            x5 = conv(np.concatenate([r, x1, x2, x3, x4]), *g(p + 'conv5'))
            r = x5 * 0.2 + r
        x = r * 0.2 + x
        print('bloco', i + 1, '/23', file=sys.stderr, flush=True)
    x = fea + conv(x, *g('conv_body'))
    up = lambda t: t.repeat(2, axis=1).repeat(2, axis=2)
    x = lrelu(conv(up(x), *g('conv_up1')))
    x = lrelu(conv(up(x), *g('conv_up2')))
    x = conv(lrelu(conv(x, *g('conv_hr'))), *g('conv_last'))
    return x

if __name__ == '__main__':
    P = {k: v.astype(np.float32) for k, v in load_pth(sys.argv[1]).items()}
    for src, dst in zip(sys.argv[2::2], sys.argv[3::2]):
        im = np.asarray(Image.open(src).convert('RGB'), np.float32).transpose(2, 0, 1) / 255
        out = run(P, im)
        Image.fromarray((np.clip(out, 0, 1).transpose(1, 2, 0) * 255).round().astype(np.uint8)).save(dst)
        print('ok', dst, flush=True)
