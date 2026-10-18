#!/usr/bin/env python3
"""3.14: renders the built-in looks through the app's real shaders (glcheck chain) for review.
Usage: python3 tests/lookpipe.py spec.json out_dir [photo.png ...]
spec.json comes from `bash tests/run.sh BuiltinLooks assets/looks spec.json`. Each pass reads the
previous output as an FBO texture (`:fbo`), exactly as the app's ping-pong does."""
import json, os, subprocess, sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SH = os.path.join(HERE, "..", "assets", "shaders")
GLCHECK = os.path.expanduser("~/tools/glcheck.py")

def run_pass(p, below, below_fbo, out, W, H, work):
    args = ["python3", GLCHECK, os.path.join(SH, p["prog"] + ".frag"), out, str(W), str(H),
            "--prepend=" + os.path.join(SH, "common.glsl"),
            "--tex=uBelow:" + below + (":fbo" if below_fbo else "")]
    if "lut" in p:
        lut = p["lut"]; n = len(lut) // 4
        img = Image.new("RGBA", (n, 1))
        img.putdata([tuple(max(0, min(255, round(lut[i * 4 + c] * 255))) for c in range(4)) for i in range(n)])
        lp = os.path.join(work, "lut.png"); img.save(lp)
        args.append("--tex=uLut:" + lp)
    u = dict(uTile=[0, 0, 1, 1], uDocSize=[W, H], uM0=[1, 0, 0], uM1=[0, 1, 0], uImgSize=[W, H],
             uBelowScale=[1, 1], uTexel=[1.0 / W, 1.0 / H], uPxPerDoc=[1], uMaskOn=[0], uRangeOn=[0],
             uClipOn=[0], uMaskN=[0], uLong=[max(W, H)], uFrameT=[0])
    u.update(p["u"])
    for k, v in u.items():
        args.append(k + "=" + ",".join(str(float(x)) for x in v))
    r = subprocess.run(args, capture_output=True, text=True)
    if "OK" not in r.stdout:
        sys.exit("pass failed: " + p["prog"] + "\n" + r.stdout + r.stderr)

def main():
    spec = json.load(open(sys.argv[1])); out = sys.argv[2]; photos = sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    rows = []
    for ph in photos:
        im = Image.open(ph).convert("RGBA"); W, H = im.size
        row = [("Original", im.convert("RGB"))]
        for look in spec:
            below, fbo = ph, False
            for i, p in enumerate(look["passes"]):
                o = os.path.join(out, "%s_%d.png" % (look["file"], i))
                run_pass(p, below, fbo, o, W, H, out)
                below, fbo = o, True
            row.append((look["name"], Image.open(below).convert("RGB").transpose(Image.FLIP_TOP_BOTTOM)))
        rows.append(row)
    tw, th = rows[0][0][1].size
    cols = len(rows[0])
    per = 7
    lines = (cols + per - 1) // per
    sheet = Image.new("RGB", (per * (tw + 6), len(rows) * lines * (th + 18)), (24, 24, 26))
    d = ImageDraw.Draw(sheet)
    y = 0
    for row in rows:
        for li in range(lines):
            for ci, (name, img) in enumerate(row[li * per:(li + 1) * per]):
                x = ci * (tw + 6)
                sheet.paste(img, (x, y)); d.text((x + 2, y + th + 3), name, fill=(230, 230, 230))
            y += th + 18
    sheet.save(os.path.join(out, "looks_sheet.png"))
    print("OK", os.path.join(out, "looks_sheet.png"))

main()
