#!/usr/bin/env python3
"""Turn raw phone screenshots into Google Play phone screenshots.

Why: a Galaxy S23 Ultra screenshot is 1080 x 2316 (or 1440 x 3088), a 2.14 ratio, and Play
rejects anything whose long side is more than twice the short side. Play also wants 1080 x 1920
(9:16) for the large promotional layout, taglines in no more than 20 % of the image, and 24-bit
PNG/JPEG with no alpha. This puts the real screenshot, scaled down unchanged, under a short
caption on the brand dark, at exactly 1080 x 1920.

Usage:
  python3 frame.py shots.txt out_dir [--preview]
shots.txt: one shot per line,  path/to/screenshot.png | Caption text
--preview stamps LAYOUT PREVIEW across the image (for layout checks with stand-in pictures).
"""
import os, sys
from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "..", "assets", "fonts", "Archivo.ttf")
BG = (12, 12, 15)
RED, BLUE = (255, 38, 56), (30, 150, 255)


def font(size, wght, wdth=125):
    f = ImageFont.truetype(FONT, size)
    vals = []
    for a in f.get_variation_axes():
        n = a.get("name"); n = n.decode() if isinstance(n, bytes) else n
        vals.append({"Weight": wght, "Width": wdth}.get(n, a["default"]))
    f.set_variation_by_axes(vals)
    return f


def wrap(d, text, f, maxw):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if d.textlength(t, font=f) <= maxw: cur = t
        else: lines.append(cur); cur = w
    if cur: lines.append(cur)
    return lines


def frame(src, caption, out, preview=False):
    shot = Image.open(src).convert("RGB")
    land = shot.width > shot.height          # a sideways shot gets a 1920 x 1080 canvas
    W, H = (1920, 1080) if land else (1080, 1920)
    CAP_H = 170 if land else 300             # 15.7 % of the height either way (Play's cap: 20 %)
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    # caption: white with a hairline red/blue offset, the wordmark's look at caption size
    f = font(52 if land else 58, 720)
    lines = wrap(d, caption, f, W - 140)[:2]
    lh = 72; y0 = (CAP_H - lh * len(lines)) // 2 + 20
    for i, ln in enumerate(lines):
        tw = d.textlength(ln, font=f); x = (W - tw) / 2; y = y0 + i * lh
        d.text((x - 2, y), ln, font=f, fill=RED)
        d.text((x + 2, y), ln, font=f, fill=BLUE)
        d.text((x, y), ln, font=f, fill=(255, 255, 255))
    # the screenshot itself, scaled (never stretched) into the space below
    maxh, maxw = H - CAP_H - (50 if land else 70), W - 120
    s = min(maxh / shot.height, maxw / shot.width)
    sw, sh = round(shot.width * s), round(shot.height * s)
    shot = shot.resize((sw, sh), Image.LANCZOS)
    x, y = (W - sw) // 2, CAP_H + (H - CAP_H - sh) // 2
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.rounded_rectangle((x - 4, y, x + sw - 4, y + sh), 40, outline=RED, width=5)
    gd.rounded_rectangle((x + 4, y, x + sw + 4, y + sh), 40, outline=BLUE, width=5)
    glow = glow.filter(ImageFilter.GaussianBlur(10))
    img = ImageChops.add(img, Image.eval(glow, lambda v: int(v * 0.55)))   # faint red/blue edge glow
    m = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, sw - 1, sh - 1), 36, fill=255)
    img.paste(shot, (x, y), m)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((x, y, x + sw - 1, y + sh - 1), 36, outline=(60, 62, 70), width=2)
    if preview:
        st = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        sd = ImageDraw.Draw(st)
        pf = font(64, 800)
        for yy in ((300, 600, 900) if land else (700, 1150, 1600)):
            t = "LAYOUT PREVIEW"
            sd.text(((W - sd.textlength(t, font=pf)) / 2, yy), t, font=pf, fill=(255, 210, 0, 200))
        st = st.rotate(24, resample=Image.BICUBIC)
        img = Image.alpha_composite(img.convert("RGBA"), st).convert("RGB")
    img.save(out, optimize=True)
    return out


if __name__ == "__main__":
    spec, outdir = sys.argv[1], sys.argv[2]
    preview = "--preview" in sys.argv
    os.makedirs(outdir, exist_ok=True)
    n = 0
    for line in open(spec, encoding="utf-8"):
        if not line.strip() or line.startswith("#"): continue
        path, cap = [p.strip() for p in line.split("|", 1)]
        n += 1
        print(frame(path, cap, os.path.join(outdir, f"play-screenshot-{n}.png"), preview))
