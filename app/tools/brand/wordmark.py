#!/usr/bin/env python3
"""Independent Video wordmark: white core + red (left) and cyan (right) anaglyph copies with glow.

render(spec, scale) -> dict of RGBA layers {red, cyan, core} (same size, transparent) and a
preview composite on black. The app stacks red, cyan, core as ImageViews and animates the
red/cyan translationX, so each colour layer is drawn centred (no offset baked in).
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops
import sys, os

FONTS = os.environ.get("FONTS", "/home/claude/src/Strata/assets/fonts")
RED = (255, 38, 56)
CYAN = (30, 150, 255)


def font(name, size, wght=None, extra=None):
    f = ImageFont.truetype(os.path.join(FONTS, name + ".ttf"), size)
    if wght is not None:
        axes = {"wght": wght}
        if extra: axes.update(extra)
        try:
            names = [a["axis"] if isinstance(a, dict) else a for a in f.get_variation_axes()]
        except Exception:
            names = []
        vals = []
        for a in f.get_variation_axes():
            tag = {"Weight": "wght", "Width": "wdth", "Optical size": "opsz"}.get(a.get("name", b"").decode() if isinstance(a.get("name"), bytes) else a.get("name"), None)
            vals.append(axes.get(tag, a["default"]))
        f.set_variation_by_axes(vals)
    return f


def tracked(draw, xy, text, f, track, fill):
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=f, fill=fill)
        x += f.getlength(ch) + track
    return x


def width(text, f, track):
    return sum(f.getlength(c) for c in text) + track * (len(text) - 1)


def mask(spec, s):
    """White text mask (L) for a two-line lockup: small tracked top line, big bottom line,
    bottom line tracked so both lines end flush."""
    top, bot = spec.get("top", "INDEPENDENT"), spec.get("bot", "VIDEO")
    ft = font(spec["font_top"], int(spec["size_top"] * s), spec.get("w_top"), spec.get("x_top"))
    fb = font(spec["font_bot"], int(spec["size_bot"] * s), spec.get("w_bot"), spec.get("x_bot"))
    tt = spec["track_top"] * s
    wt = width(top, ft, tt)
    wb0 = width(bot, fb, 0)
    tb = max((wt - wb0) / (len(bot) - 1), spec.get("min_track_bot", 0) * s) if spec.get("flush", True) else spec.get("track_bot", 0) * s
    wb = width(bot, fb, tb)
    W = int(max(wt, wb) + 2 * spec["pad"] * s)
    asc_t, desc_t = ft.getmetrics(); asc_b, desc_b = fb.getmetrics()
    # tight vertical boxes from real glyph bounds
    bt = ft.getbbox(top); bb = fb.getbbox(bot)
    gap = spec["gap"] * s
    H = int((bt[3] - bt[1]) + gap + (bb[3] - bb[1]) + 2 * spec["pad"] * s)
    m = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(m)
    y0 = spec["pad"] * s - bt[1]
    tracked(d, ((W - wt) / 2, y0), top, ft, tt, 255)
    y1 = spec["pad"] * s + (bt[3] - bt[1]) + gap - bb[1]
    tracked(d, ((W - wb) / 2, y1), bot, fb, tb, 255)
    return m


def layers(m, s, glow=1.0):
    def put(c, alpha):
        im = Image.new("RGBA", m.size, c + (0,)); im.putalpha(alpha); return im
    def blur(r, a):
        return m.filter(ImageFilter.GaussianBlur(r * s)).point(lambda v: min(255, int(v * a * glow)))
    def colour(c):
        out = put(c, blur(14, 0.5))                        # wide, faint halo (pad must exceed 3 sigma)
        out = Image.alpha_composite(out, put(c, blur(6, 0.55)))   # tight glow
        return Image.alpha_composite(out, put(c, m))         # sharp copy
    core = Image.alpha_composite(put((255, 255, 255), blur(1.2, 0.25)), put((255, 255, 255), m))
    return colour(RED), colour(CYAN), core


def preview(red, cyan, core, off, bg=(10, 10, 12)):
    W, H = red.size
    P = int(off) + 2
    out = Image.new("RGBA", (W + 2 * P, H), bg + (255,))
    # additive red/cyan behind the core, like two projector images
    acc = Image.new("RGB", out.size, (0, 0, 0))
    for layer, dx in ((red, -off), (cyan, off)):
        tmp = Image.new("RGBA", out.size, (0, 0, 0, 0)); tmp.paste(layer, (P + int(round(dx)), 0))
        rgb = Image.new("RGB", out.size, (0, 0, 0)); rgb.paste(tmp, (0, 0), tmp)
        acc = ImageChops.add(acc, rgb)
    out = Image.alpha_composite(out, Image.merge("RGBA", (*acc.split(), Image.new("L", out.size, 255))))
    base = Image.new("RGB", out.size, bg); out = Image.merge("RGBA", (*ImageChops.lighter(base, out.convert("RGB")).split(), Image.new("L", out.size, 255)))
    tmp = Image.new("RGBA", out.size, (0, 0, 0, 0)); tmp.paste(core, (P, 0))
    return Image.alpha_composite(out, tmp)
