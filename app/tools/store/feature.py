#!/usr/bin/env python3
"""Play feature graphic, 1024 x 500, 24-bit PNG (no alpha): anaglyph wordmark on the brand dark,
faint film perforations along the edges. Usage: feature.py <assets/brand dir> out.png"""
import sys
from PIL import Image, ImageDraw, ImageChops, ImageFilter
import numpy as np
brand, out = sys.argv[1], sys.argv[2]
W, H = 1024, 500
y, x = np.mgrid[0:H, 0:W].astype(np.float32)
r = np.hypot((x - W / 2) / (W * 0.6), (y - H / 2) / (H * 0.75))
base = np.stack([8 + 10 * np.clip(1 - r, 0, 1), 8 + 9 * np.clip(1 - r, 0, 1), 11 + 14 * np.clip(1 - r, 0, 1)], -1)
img = Image.fromarray(base.clip(0, 255).astype(np.uint8), "RGB")
d = ImageDraw.Draw(img)
for yy in (22, H - 22 - 14):                       # perforations, very faint
    for i in range(0, W // 40 + 1):
        d.rounded_rectangle((i * 40 + 6, yy, i * 40 + 26, yy + 14), radius=3, fill=(24, 24, 28))
red = Image.open(f"{brand}/wm_red.png"); blue = Image.open(f"{brand}/wm_blue.png"); core = Image.open(f"{brand}/wm_core.png")
ww = 760; hh = round(core.height * ww / core.width)
red, blue, core = [im.resize((ww, hh), Image.LANCZOS) for im in (red, blue, core)]
off = round(ww * 0.005); ox, oy = (W - ww) // 2, (H - hh) // 2 - 6
def add(layer, dx):
    global img
    tmp = Image.new("RGBA", img.size, (0, 0, 0, 0)); tmp.paste(layer, (ox + dx, oy))
    rgb = Image.new("RGB", img.size, (0, 0, 0)); rgb.paste(tmp, (0, 0), tmp)
    img = ImageChops.add(img, rgb)
add(red, -off); add(blue, off)
tmp = Image.new("RGBA", img.size, (0, 0, 0, 0)); tmp.paste(core, (ox, oy))
img = Image.alpha_composite(img.convert("RGBA"), tmp).convert("RGB")
img.save(out)
