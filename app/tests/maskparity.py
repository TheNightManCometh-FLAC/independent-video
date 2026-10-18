#!/usr/bin/env python3
"""Shader vs Java parity for mask components.
Usage: python3 tests/maskparity.py <scene folder from MaskStackTest> <path to glcheck.py>
Renders common.glsl's maskAt for each scene (uniforms_N.txt) over raster.pgm and diffs it
against MaskStack.flatten's expect_N.pgm. Exit 1 if any pixel differs by more than 2/255 (scenes with distance
textures: at most 32/255, on at most 2% of pixels, averaging under 8/255; see below)."""
import os, subprocess, sys
from PIL import Image, ImageChops
d, glcheck = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
common = os.path.join(here, '..', 'assets', 'shaders', 'common.glsl')
r = Image.open(os.path.join(d, 'raster.pgm'))
W, H = r.size
m = Image.new('RGBA', (W, H), (0, 0, 0, 0)); m.putalpha(r); m.save(os.path.join(d, 'mask_rgba.png'))
frag = os.path.join(d, 'maskat.frag')
open(frag, 'w').write('void main() { gl_FragColor = vec4(vec3(maskAt(vUv * uImgSize)), 1.0); }\n')
def shapes(n):
    # 3.15 distance textures of shape/text components (sdf_<scene>_<slot>.pgm), alpha = value
    out = []
    for slot in range(4):
        f = os.path.join(d, 'sdf_%d_%d.pgm' % (n, slot))
        if not os.path.exists(f): continue
        g = Image.open(f)
        t = Image.new('RGBA', g.size, (0, 0, 0, 0)); t.putalpha(g)
        tp = os.path.join(d, 'sdf_%d_%d.png' % (n, slot)); t.save(tp)
        out.append('--tex=uShape%d:%s' % (slot, tp))
    return out
bad = 0; sheet = []
n = 0
while os.path.exists(os.path.join(d, 'uniforms_%d.txt' % n)):
    u = open(os.path.join(d, 'uniforms_%d.txt' % n)).read().split()
    out = os.path.join(d, 'gl_%d.png' % n)
    cmd = ['python3', glcheck, frag, out, str(W), str(H), '--prepend=' + common, '--tex=uMask:' + os.path.join(d, 'mask_rgba.png'),
           ] + shapes(n) + [
           'uMaskOn=1', 'uMR0=%r,0,0' % (1.0 / W), 'uMR1=0,%r,0' % (1.0 / H), 'uMaskPx=%d,%d' % (W, H), 'uImgSize=%d,%d' % (W, H)] + u
    res = subprocess.run(cmd, capture_output=True, text=True)
    if 'OK' not in res.stdout: print(res.stdout, res.stderr); sys.exit(1)
    # glcheck saves framebuffer row 0 at the bottom; the app's row 0 is the mask's top row
    g = Image.open(out).convert('L').transpose(Image.FLIP_TOP_BOTTOM)
    e = Image.open(os.path.join(d, 'expect_%d.pgm' % n))
    diff = ImageChops.difference(g, e)
    mx = diff.getextrema()[1]
    hist = diff.histogram(); over = sum(hist[3:])
    print('scene %d: max diff %d/255, %d px over 2/255' % (n, mx, over))
    # Distance-texture shapes: the GPU filters 8-bit texels with ~8-bit weights, Java in float.
    # On a hard (1 mask px) edge that is a few hundredths of a pixel of edge position, i.e. edge
    # pixels off by a few percent. Analytic shapes must match to 2/255.
    tex = bool(shapes(n))
    if tex:
        import numpy as np
        dd = np.asarray(diff).astype(float)
        mean_bad = dd[dd > 2].mean() if (dd > 2).any() else 0
        print('         (distance textures: mean %.1f/255 over the %d differing px)' % (mean_bad, over))
        if mx > 32 or over > W * H * 0.02 or (over > 50 and mean_bad > 8): bad += 1
    elif mx > 2: bad += 1
    sheet += [e, g]
    n += 1
S = Image.new('L', (W * 2 + 10, (H + 10) * n), 40)
for i in range(n):
    S.paste(sheet[2 * i], (0, i * (H + 10))); S.paste(sheet[2 * i + 1], (W + 10, i * (H + 10)))
S.save(os.path.join(d, 'parity_sheet.png'))
sys.exit(1 if bad else 0)
