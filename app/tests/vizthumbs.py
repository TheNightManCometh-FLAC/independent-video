#!/usr/bin/env python3
"""Renders the visualizer style and preset tiles (assets/vizthumbs/<key>.png) through the real
viz.frag with a synthetic song frame, so the pictures in the app match what the styles draw.
Usage: python3 tests/vizthumbs.py <folder with thumbs.txt from VisualsTest> <glcheck.py>"""
import math, os, subprocess, sys
import numpy as np
from PIL import Image

d, glcheck = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
sh = os.path.join(here, '..', 'assets', 'shaders')
out = os.path.join(here, '..', 'assets', 'vizthumbs')
os.makedirs(out, exist_ok=True)

def spec(n, t):
    o = []
    for i in range(n):
        u = i / max(1, n - 1)
        base = 0.85 * math.exp(-u * 1.6) + 0.25 * math.exp(-((u - 0.45) / 0.12) ** 2) + 0.18 * math.exp(-((u - 0.75) / 0.08) ** 2)
        wob = 0.18 * math.sin(i * 1.7 * 48 / max(n, 1) + t * 3.1) + 0.12 * math.sin(i * 0.6 * 48 / max(n, 1) - t * 5.3)
        o.append(max(0.0, min(1.0, base + wob)))
    return o

def data(n, path):
    a = np.zeros((36, 512), np.uint8)
    cur = spec(n, 0)
    for i in range(n):
        a[0, i] = int(cur[i] * 255); a[2, i] = int(min(1, cur[i] + 0.12) * 255)
    for i in range(512):
        w = 0.5 + 0.42 * math.sin(i / 512 * 2 * math.pi * 5) * math.exp(-((i - 256) / 220) ** 2) + 0.06 * math.sin(i * 0.9)
        a[1, i] = int(max(0, min(1, w)) * 255)
        v = max(0, math.cos(i / 511 * 1.2 * 2 * math.pi * 2)) ** 10 * 0.95 + 0.08
        a[35, i] = int(min(1, v) * 255)
    for k in range(32):
        sp = spec(n, -k * 0.09)
        for i in range(n): a[3 + k, i] = int(sp[i] * 255)
    rgba = np.stack([a, a, a, np.full_like(a, 255)], -1)
    Image.fromarray(rgba, 'RGBA').save(path)

S = 144
for line in open(os.path.join(d, 'thumbs.txt')):
    parts = line.split()
    key, shape, unis = parts[0], parts[1], parts[2:]
    n = int([u for u in unis if u.startswith('uBars=')][0].split('=')[1])
    dp = os.path.join(d, 'tdata_%d.png' % n)
    if not os.path.exists(dp): data(n, dp)
    w, h = (S, S) if shape == 'sq' else (S, 96)
    tmp = os.path.join(d, 'thumb_tmp.png')
    cmd = ['python3', glcheck, os.path.join(sh, 'viz.frag'), tmp, str(w), str(h), '--prepend=' + os.path.join(sh, 'common.glsl'),
           '--tex=uData:' + dp, 'uBoxPx=%d,%d' % (w, h)] + unis
    r = subprocess.run(cmd, capture_output=True, text=True)
    if 'OK' not in r.stdout: print(key, r.stdout, r.stderr); sys.exit(1)
    im = np.asarray(Image.open(tmp).transpose(Image.FLIP_TOP_BOTTOM)).astype(float) / 255
    bg = np.zeros((S, S, 3)); bg[:] = (0x15 / 255, 0x17 / 255, 0x1c / 255)
    y0 = (S - h) // 2
    reg = bg[y0:y0 + h]
    bg[y0:y0 + h] = im[..., :3] + reg * (1 - im[..., 3:4])   # premultiplied over the tile colour
    Image.fromarray((np.clip(bg, 0, 1) * 255).astype(np.uint8)).save(os.path.join(out, key + '.png'), optimize=True)
    print('ok', key)
