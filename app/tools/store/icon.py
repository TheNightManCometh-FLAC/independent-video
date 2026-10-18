#!/usr/bin/env python3
"""Redraw the launcher art (film frame + sunset) as vectors at any size, for the 512 px Play icon.
Geometry and colours sampled from res/drawable-nodpi/ic_bg.png (432 px). Usage: icon.py out.png [size]"""
import sys, math
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

def render(size=512, ss=4):
    N = size * ss; k = N / 432.0
    y, x = np.mgrid[0:N, 0:N].astype(np.float32) / k          # coords in 432-space
    # background: near-black with a faint lift in the middle
    r = np.hypot(x - 216, y - 216) / 216
    bg = np.stack([11 + 2 * (1 - r), 10 + 2 * (1 - r), 12 + 2 * (1 - r)], -1).clip(0, 255)
    img = Image.fromarray(bg.astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img)
    S = lambda *v: [c * k for c in v]
    d.rounded_rectangle(S(96, 118, 336, 314), radius=20 * k, fill=(30, 28, 31))
    for i in range(6):
        hx = 110 + i * 39.2
        for hy in (126, 292):
            d.rounded_rectangle(S(hx, hy, hx + 16, hy + 14), radius=4 * k, fill=(14, 13, 15))
    # picture: vertical sunset gradient + warm glow around the sun + horizon line + sun disc
    stops = [(150, (238, 165, 61)), (190, (202, 120, 52)), (232, (163, 75, 44)), (260, (98, 50, 38)), (282, (50, 34, 34))]
    ys = np.array([s[0] for s in stops], np.float32); cs = np.array([s[1] for s in stops], np.float32)
    col = np.stack([np.interp(y, ys, cs[:, j]) for j in range(3)], -1)
    glow = np.exp(-(((x - 216) / 70) ** 2 + ((y - 232) / 95) ** 2))
    col = col + glow[..., None] * np.array([70, 55, 30], np.float32)
    hor = np.exp(-((y - 236.5) / 0.9) ** 2) * (np.abs(x - 216) > 0)
    sd = np.hypot(x - 216, y - 232)
    sun = np.clip((36.5 - sd) / 1.2, 0, 1)
    halo = np.exp(-np.maximum(sd - 36, 0) / 8) * 0.12
    col = col * (1 - sun[..., None]) + np.array([255, 253, 238], np.float32) * sun[..., None]
    col = col + halo[..., None] * np.array([255, 200, 120], np.float32) * (1 - sun[..., None])
    col = col * (1 - 0.62 * hor[..., None])   # horizon line, drawn across the sun as in the original
    col = col.clip(0, 255).astype(np.uint8)
    pic = Image.fromarray(col, "RGB")
    m = Image.new("L", (N, N), 0)
    ImageDraw.Draw(m).rounded_rectangle(S(110, 150, 322, 282), radius=8 * k, fill=255)
    img.paste(pic, (0, 0), m)
    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    out = sys.argv[1]; size = int(sys.argv[2]) if len(sys.argv) > 2 else 512
    render(size).save(out)
