#!/usr/bin/env python3
"""Effects masked by text and shapes on a real photo, through common.glsl's maskAt and the
app's own distance-texture code (MaskRaster.sdf via tests/MaskDemo). Text is drawn by PIL here
(Android's canvas on the phone). Writes maskdemo.jpg.
Usage: python3 tests/maskdemo.py <out dir> <glcheck.py> <classpath with MaskDemo + app classes>"""
import os, subprocess, sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps
from skimage import data

out, glcheck, cp = sys.argv[1], sys.argv[2], sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
common = os.path.join(here, '..', 'assets', 'shaders', 'common.glsl')
fonts = os.path.join(here, '..', 'assets', 'fonts')
W, H = 960, 540

def java(*a):
    return subprocess.run(['java', '-cp', cp, 'com.christopher.strata.MaskDemo'] + list(a), capture_output=True, text=True).stdout.strip()

def text_sdf(text, font_file, tag):
    """Returns (sdf png path, aspect, sx, sy, pad, cw, ch) the way MaskRaster lays a text texture out."""
    f = ImageFont.truetype(os.path.join(fonts, font_file), 200)
    x0, y0, x1, y1 = f.getbbox(text)
    aspect = (x1 - x0) / (y1 - y0)
    cw, ch, pad = map(int, java('layout', str(aspect)).split())
    w, h = cw + 2 * pad, ch + 2 * pad
    Wc, Hc = 2 * w, 2 * h
    big = ImageFont.truetype(os.path.join(fonts, font_file), 200 * 2 * ch // (y1 - y0))
    bx0, by0, bx1, by1 = big.getbbox(text)
    cov = Image.new('L', (Wc, Hc), 0)
    ImageDraw.Draw(cov).text((2 * pad - bx0, 2 * pad - by0), text, font=big, fill=255)
    cov = cov.resize((Wc, Hc))
    cp_ = os.path.join(out, 'cov_%s.pgm' % tag); cov.save(cp_)
    sp = os.path.join(out, 'sdf_%s.pgm' % tag)
    java('sdf', cp_, str(pad), sp)
    g = Image.open(sp)
    t = Image.new('RGBA', g.size, (0, 0, 0, 0)); t.putalpha(g)
    tp = os.path.join(out, 'sdf_%s.png' % tag); t.save(tp)
    return tp, aspect, cw / w, ch / h, pad, cw, ch

def mask(comps, texs):
    """comps: list of 20-float uniform vectors (MaskStack.uniforms layout); raster empty and below."""
    frag = os.path.join(out, 'maskat.frag')
    open(frag, 'w').write('void main() { gl_FragColor = vec4(vec3(maskAt(vUv * uImgSize)), 1.0); }\n')
    blank = os.path.join(out, 'blank.png'); Image.new('RGBA', (8, 8), (0, 0, 0, 0)).save(blank)
    u = ['uMaskN=%d' % len(comps), 'uPaintOp=0', 'uPaintAt=0']
    for k, c in enumerate(comps):
        for j, n in enumerate(['uMA', 'uMB', 'uMC', 'uMD', 'uME']):
            u.append('%s[%d]=%s' % (n, k, ','.join(repr(float(x)) for x in c[j * 4:j * 4 + 4])))
    tx = ['--tex=uMask:' + blank] + ['--tex=uShape%d:%s' % (i, p) for i, p in enumerate(texs)]
    res = os.path.join(out, 'm.png')
    cmd = ['python3', glcheck, frag, res, str(W), str(H), '--prepend=' + common] + tx + [
        'uMaskOn=1', 'uMR0=%r,0,0' % (1.0 / W), 'uMR1=0,%r,0' % (1.0 / H), 'uMaskPx=%d,%d' % (W, H), 'uImgSize=%d,%d' % (W, H)] + u
    r = subprocess.run(cmd, capture_output=True, text=True)
    if 'OK' not in r.stdout: print(r.stdout, r.stderr); sys.exit(1)
    return np.asarray(Image.open(res).convert('L').transpose(Image.FLIP_TOP_BOTTOM)).astype(float)[..., None] / 255

def text_comp(tex, cx, cy, hx, op=0, feather=0.0, outline=0.0, reveal=1.0, mode=0, angle=0, slot=0):
    tp, aspect, sx, sy, pad, cw, ch = tex
    hy = hx / aspect
    unit = 2 * pad * math.sqrt((2 * hx / cw) * (2 * hy / ch))
    a = math.radians(angle)
    return [5, op, 0, 1, cx, cy, hx, hy, math.cos(a), math.sin(a), feather, 0, 0, outline, reveal, mode, sx, sy, unit, slot]

def poly(cx, cy, r, n, inner, rnd=0.0, op=0, feather=0.0, invert=0, angle=0):
    a = math.radians(angle)
    return [3, op, invert, 1, cx, cy, r, r, math.cos(a), math.sin(a), feather, rnd, 0, 0, 1, 0, n, inner, 0, 0]

photo = Image.fromarray(data.coffee()).resize((W, int(W * 400 / 600))).crop((0, 30, W, 30 + H))
P = np.asarray(photo).astype(float) / 255
gray = np.repeat(np.asarray(ImageOps.grayscale(photo)).astype(float)[..., None] / 255, 3, -1)
inv = 1 - P
blur = np.asarray(photo.filter(ImageFilter.GaussianBlur(14))).astype(float) / 255
duo = np.stack([0.15 + 0.85 * gray[..., 0], 0.05 + 0.4 * gray[..., 0] ** 1.5, 0.35 + 0.6 * gray[..., 0] ** 0.7], -1)

glitch = text_sdf('GLITCH', 'Creepster.ttf', 'a')
noise = text_sdf('NOISE', 'Anton.ttf', 'b')
neon = text_sdf('Night', 'Pacifico.ttf', 'c')
panels = []
m1 = mask([text_comp(glitch, W / 2, H / 2, W * 0.42)], [glitch[0]])
panels.append(('Invert inside text (Creepster)', P * (1 - m1) + inv * m1))
m2 = mask([text_comp(noise, W / 2, H / 2, W * 0.36, outline=0.08, reveal=0.7)], [noise[0]])
panels.append(('Duotone, outline only, reveal 70% (Anton)', P * (1 - m2) + duo * m2))
m3 = mask([poly(W / 2, H / 2, H * 0.42, 5, 0.42, rnd=0.15, invert=1)], [])
panels.append(('Black & white outside a rounded star', gray * m3 + P * (1 - m3)))
m4 = mask([text_comp(neon, W / 2, H * 0.5, W * 0.38, feather=0.3, angle=-8)], [neon[0]])
panels.append(('Blur and darken everywhere but script text (Pacifico)', blur * 0.4 * (1 - m4) + P * m4))
m5 = mask([poly(W * 0.3, H / 2, H * 0.4, 64, math.cos(math.pi / 64), feather=0.5), text_comp(glitch, W * 0.62, H / 2, W * 0.3, op=0)], [glitch[0]])
panels.append(('Soft ellipse + text, added together', P * (1 - m5) + inv * m5))
m6 = mask([poly(W / 2, H / 2, H * 0.45, 6, math.cos(math.pi / 6)), text_comp(glitch, W / 2, H / 2, W * 0.3, op=1)], [glitch[0]])
panels.append(('Hexagon minus text (Subtract)', P * (1 - m6) + duo * m6))
sheet = Image.new('RGB', (2 * W // 2 + 30, 3 * (H // 2 + 34) + 10), (30, 30, 34))
d = ImageDraw.Draw(sheet)
for i, (name, img) in enumerate(panels):
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).resize((W // 2, H // 2), Image.LANCZOS)
    x = 10 + (i % 2) * (W // 2 + 10); y = 10 + (i // 2) * (H // 2 + 34)
    sheet.paste(im, (x, y + 22)); d.text((x, y + 4), name, fill=(240, 240, 240))
sheet.save(os.path.join(out, 'maskdemo.jpg'), quality=90)
print('ok')
