#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
// Coordinate conventions (all layers):
//  vUv      0..1 across the current viewport (a tile or the whole preview)
//  docUv    uTile.xy + vUv*uTile.zw, y = 0 is the TOP of the document
//  doc px   docUv * uDocSize (the cropped document)
//  img px   uncropped image space (base image pixels); layers are placed here
//  mask uv  img px through the layer's mask rows: a canvas mask covers the image, a linked mask
//           covers its layer and moves with it
// Framebuffer row 0 holds the document's top row, so glReadPixels rows match Bitmap rows.
varying vec2 vUv;
uniform vec4 uTile;
uniform vec2 uDocSize;
uniform vec3 uM0;
uniform vec3 uM1;
uniform vec2 uImgSize;
uniform sampler2D uBelow;
uniform sampler2D uMask;
uniform float uMaskOn;
uniform vec3 uMR0;          // img px -> mask uv (row 0)
uniform vec3 uMR1;          // img px -> mask uv (row 1)
uniform float uOpacity;
uniform float uMode;
uniform vec2 uBelowScale;   // valid part of the below texture (export edge tiles are partial)
uniform vec2 uTexel;        // one viewport px in below-texture uv (1 / buffer size)
uniform float uPxPerDoc;    // viewport px per doc px (preview < 1, export/detail up to 1)
// Range mask (Photoshop "Blend If" on the underlying composite), live and resolution-free
uniform float uRangeOn;     // 0 off, 1 luminosity, 2 color
uniform vec4 uRangeL;       // darks fade-in start, darks full, lights full until, lights fade-out end
uniform vec4 uRangeC;       // target rgb, tolerance
uniform float uRangeF;      // color softness
uniform float uRangeInv;
uniform sampler2D uClip;    // clipping: coverage of the base layer below, same viewport as uBelow
uniform float uClipOn;

const vec3 LW = vec3(0.2126, 0.7152, 0.0722);

vec4 belowAt() { return texture2D(uBelow, vUv * uBelowScale); }
vec2 docPx() { return (uTile.xy + vUv * uTile.zw) * uDocSize; }
vec2 imgPx() { vec3 d = vec3(docPx(), 1.0); return vec2(dot(uM0, d), dot(uM1, d)); }
// Editable mask components (MaskStack.java is the Java twin): the painted raster sits at stack
// position uPaintAt with operation uPaintOp; up to 8 shapes, bottom first, each Add (max),
// Subtract (m x (1 - v)) or Overlap (m x v), starting from nothing. Geometry in mask px.
// Types: 0 linear, 1 radial, 2 rectangle, 3 polygon/star, 4 library/freehand shape, 5 text.
// 2-5 are "box" shapes: a signed distance d (mask px, < 0 inside), then grow, outline, a feather
// centred on the edge and a reveal wipe (mbox). 4 and 5 read d from a distance texture uShape0-3.
uniform float uMaskN;       // number of shape components; 0 = the raster alone (as before 3.6)
uniform vec2 uMaskPx;       // mask size px
uniform float uPaintAt;
uniform float uPaintOp;
uniform vec4 uMA[8];        // type, op, invert, amount
uniform vec4 uMB[8];        // linear: full-effect point, no-effect point; others: centre, half size
uniform vec4 uMC[8];        // cos, sin of the rotation, feather, roundness
uniform vec4 uMD[8];        // grow, outline, reveal, reveal direction
uniform vec4 uME[8];        // polygon: sides, inner ratio; texture shapes: box share x, y, mask px per unit, slot
uniform sampler2D uShape0;
uniform sampler2D uShape1;
uniform sampler2D uShape2;
uniform sampler2D uShape3;

float mcomb(float m, float v, float op) {
    return op < 0.5 ? max(m, v) : (op < 1.5 ? m * (1.0 - v) : m * v);
}

float mtex(float slot, vec2 uv) {
    if (slot < 0.5) return texture2D(uShape0, uv).a;
    if (slot < 1.5) return texture2D(uShape1, uv).a;
    if (slot < 2.5) return texture2D(uShape2, uv).a;
    return texture2D(uShape3, uv).a;
}

// n-pointed star, outer radius 1, a point straight up, inner radius m, rounded by rnd (box units)
float mstar(vec2 p, float n, float m, float rnd) {
    float rr = rnd * 0.45;
    float k = 1.0 - rr;
    p /= k;
    float an = 3.14159265 / n;
    float sec = 2.0 * an;
    float th = mod(atan(p.x, -p.y), sec);
    if (th > an) th = sec - th;
    float r = length(p);
    vec2 q = r * vec2(cos(th), sin(th));
    vec2 e = m * vec2(cos(an), sin(an)) - vec2(1.0, 0.0);
    vec2 w = q - vec2(1.0, 0.0);
    float h = clamp(dot(w, e) / dot(e, e), 0.0, 1.0);
    float dist = length(w - e * h);
    float cr = e.x * w.y - e.y * w.x;
    return (cr > 0.0 ? -dist : dist) * k - rr;
}

float mreveal(float mode, vec2 n) {
    if (mode < 0.5) return (n.x + 1.0) * 0.5;
    if (mode < 1.5) return (1.0 - n.x) * 0.5;
    if (mode < 2.5) return (n.y + 1.0) * 0.5;
    if (mode < 3.5) return (1.0 - n.y) * 0.5;
    return min(1.0, length(n) * 0.7071);
}

float mbox(float d, float s, vec2 n, float feather, vec4 g) {
    d -= g.x * s;
    if (g.y > 0.0) d = abs(d) - g.y * s * 0.5;
    float w = max(feather * feather * s, 1.0);
    float v = 1.0 - smoothstep(-0.5 * w, 0.5 * w, d);
    if (g.z < 0.9999) {
        float e = 0.06;
        v *= 1.0 - smoothstep(g.z * (1.0 + e) - e, g.z * (1.0 + e), mreveal(g.w, n));
    }
    return v;
}

float maskAt(vec2 ip) {
    if (uMaskOn < 0.5) return 1.0;
    vec3 h = vec3(ip, 1.0);
    vec2 muv = vec2(dot(uMR0, h), dot(uMR1, h));
    float paint = texture2D(uMask, muv).a;
    if (uMaskN < 0.5) return paint;
    vec2 p = muv * uMaskPx;
    float m = 0.0;
    for (int k = 0; k < 8; k++) {
        if (float(k) == uPaintAt) m = mcomb(m, paint, uPaintOp);
        if (float(k) < uMaskN) {
            vec4 a = uMA[k];
            vec4 b = uMB[k];
            vec4 c = uMC[k];
            float v;
            if (a.x < 0.5) {
                vec2 d = b.zw - b.xy;
                v = 1.0 - smoothstep(0.0, 1.0, dot(p - b.xy, d) / max(dot(d, d), 1e-6));
            } else {
                vec2 q = p - b.xy;
                q = vec2(c.x * q.x + c.y * q.y, -c.y * q.x + c.x * q.y);
                if (a.x < 1.5) {
                    float r = length(q / max(b.zw, vec2(1e-3)));
                    v = 1.0 - (c.z < 1e-4 ? step(1.0, r) : smoothstep(1.0 - c.z, 1.0, r));
                } else {
                    vec4 e = uME[k];
                    float s = min(b.z, b.w);
                    vec2 n = q / b.zw;
                    float d;
                    if (a.x < 2.5) {
                        float rr = c.w * s;
                        vec2 qq = abs(q) - b.zw + rr;
                        d = length(max(qq, 0.0)) + min(max(qq.x, qq.y), 0.0) - rr;
                    } else if (a.x < 3.5) {
                        d = mstar(n, e.x, e.y, c.w) * s;
                    } else {
                        d = (0.5 - mtex(e.w, 0.5 + 0.5 * n * e.xy)) * e.z;
                    }
                    v = mbox(d, s, n, c.z, uMD[k]);
                }
            }
            if (a.z > 0.5) v = 1.0 - v;
            m = mcomb(m, v * a.w, a.y);
        }
    }
    if (uPaintAt > 7.5) m = mcomb(m, paint, uPaintOp);
    return m;
}

float ramp(float a, float b, float x) { return b - a < 1e-4 ? step(a, x) : smoothstep(a, b, x); }
vec3 ycc(vec3 c) { float y = dot(c, LW); return vec3(y, (c.b - y) * 0.6, (c.r - y) * 0.75); }
float rangeAt(vec3 c) {
    if (uRangeOn < 0.5) return 1.0;
    c = clamp(c, 0.0, 1.0);
    float r;
    if (uRangeOn < 1.5) {
        float L = dot(c, LW);
        float lo = uRangeL.y < 1e-4 ? 1.0 : ramp(uRangeL.x, uRangeL.y, L);
        float hi = uRangeL.z > 0.9999 ? 1.0 : 1.0 - ramp(uRangeL.z, uRangeL.w, L);
        r = lo * hi;
    } else {
        vec3 d = (ycc(c) - ycc(uRangeC.rgb)) * vec3(0.45, 1.0, 1.0);
        r = 1.0 - ramp(uRangeC.a, uRangeC.a + uRangeF, length(d));
    }
    return uRangeInv > 0.5 ? 1.0 - r : r;
}
// Total effect strength of a layer at this pixel.
float clipAt() { return uClipOn > 0.5 ? texture2D(uClip, vUv * uBelowScale).a : 1.0; }
float amountAt(vec2 ip, vec3 below) { return uOpacity * maskAt(ip) * rangeAt(below) * clipAt(); }

vec3 toLin(vec3 c) {
    vec3 a = abs(c);
    vec3 l = mix(a / 12.92, pow((a + 0.055) / 1.055, vec3(2.4)), step(0.04045, a));
    return sign(c) * l;
}
vec3 toSrgb(vec3 l) {
    vec3 a = abs(l);
    vec3 c = mix(a * 12.92, 1.055 * pow(a, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, a));
    return sign(l) * c;
}

// ---- W3C compositing / Photoshop blend modes ----
float lumN(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clipColor(vec3 c) {
    float l = lumN(c);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-6);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-6);
    return c;
}
vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lumN(c))); }
float satOf(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
vec3 setSat(vec3 c, float s) {
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    float d = mx - mn;
    if (d < 1e-6) return vec3(0.0);
    return (c - mn) * s / d;
}
vec3 bDodge(vec3 b, vec3 s) { return min(vec3(1.0), b / max(1.0 - s, 1e-5)); }
vec3 bBurn(vec3 b, vec3 s) { return 1.0 - min(vec3(1.0), (1.0 - b) / max(s, 1e-5)); }
vec3 bHard(vec3 b, vec3 s) {
    vec3 m = b * 2.0 * s;
    vec3 sc = 1.0 - (1.0 - b) * (1.0 - (2.0 * s - 1.0));
    return mix(m, sc, step(0.5, s));
}
vec3 bSoftD(vec3 b) { return mix(((16.0 * b - 12.0) * b + 4.0) * b, sqrt(b), step(0.25, b)); }
vec3 bSoft(vec3 b, vec3 s) {
    return mix(b - (1.0 - 2.0 * s) * b * (1.0 - b), b + (2.0 * s - 1.0) * (bSoftD(b) - b), step(0.5, s));
}
vec3 bVivid(vec3 b, vec3 s) {
    return mix(bBurn(b, 2.0 * s), bDodge(b, 2.0 * s - 1.0), step(0.5, s));
}

// Modes in Photoshop menu order:
// 0 Normal 1 Darken 2 Multiply 3 Color Burn 4 Linear Burn 5 Lighten 6 Screen 7 Color Dodge
// 8 Add 9 Overlay 10 Soft Light 11 Hard Light 12 Vivid Light 13 Linear Light 14 Difference
// 15 Exclusion 16 Hue 17 Saturation 18 Color 19 Luminosity
vec3 blendMode(vec3 b, vec3 s, float m) {
    if (m < 0.5) return s;
    b = clamp(b, 0.0, 1.0);
    s = clamp(s, 0.0, 1.0);
    if (m < 1.5) return min(b, s);
    if (m < 2.5) return b * s;
    if (m < 3.5) return bBurn(b, s);
    if (m < 4.5) return max(b + s - 1.0, 0.0);
    if (m < 5.5) return max(b, s);
    if (m < 6.5) return b + s - b * s;
    if (m < 7.5) return bDodge(b, s);
    if (m < 8.5) return min(b + s, 1.0);
    if (m < 9.5) return bHard(s, b);
    if (m < 10.5) return bSoft(b, s);
    if (m < 11.5) return bHard(b, s);
    if (m < 12.5) return bVivid(b, s);
    if (m < 13.5) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0);
    if (m < 14.5) return abs(b - s);
    if (m < 15.5) return b + s - 2.0 * b * s;
    if (m < 16.5) return setLum(setSat(s, satOf(b)), lumN(b));
    if (m < 17.5) return setLum(setSat(b, satOf(s)), lumN(b));
    if (m < 18.5) return setLum(s, lumN(b));
    return setLum(b, lumN(s));
}

// Pixel layer (image or fill) over what is below. Colors straight (not premultiplied).
vec4 compositeOver(vec4 below, vec3 cs, float sa, float mode) {
    float ab = below.a;
    vec3 cb = below.rgb;
    vec3 mixed = (1.0 - ab) * cs + ab * blendMode(cb, cs, mode);
    float ao = sa + ab * (1.0 - sa);
    vec3 co = sa * mixed + (1.0 - sa) * ab * cb;
    return vec4(ao > 1e-6 ? co / ao : vec3(0.0), ao);
}

// Adjustment layer: recolors what is below, never adds coverage.
vec4 compositeAdj(vec4 below, vec3 adj, float amt, float mode) {
    vec3 r = blendMode(below.rgb, adj, mode);
    return vec4(mix(below.rgb, r, amt), below.a);
}
