// Effect layer: one of the Fx catalog effects applied to the composite below, then mixed in by
// opacity x mask x Blend If with the layer's blend mode, like every adjustment. Sizes arrive as
// fractions of the image's long side (uLong = long side in viewport px), so results don't
// depend on preview or export resolution. Blur-based effects read their blurred input from
// uMid (premultiplied linear light, made by two fx_blur passes).
uniform float uFx;
uniform vec4 uFxP0;      // params 0..3
uniform vec4 uFxP1;      // params 4..7
uniform vec3 uFxC0;
uniform vec3 uFxC1;
uniform vec3 uFxC2;
uniform float uLong;
uniform sampler2D uMid;

vec4 tap(vec2 offPx, float bias) { return texture2D(uBelow, vUv * uBelowScale + offPx * uTexel, bias); }
vec4 atDocPx(vec2 d) { return texture2D(uBelow, ((d / uDocSize - uTile.xy) / uTile.zw) * uBelowScale); }
float luma(vec3 c) { return dot(c, LW); }
float hash(vec2 p) { p = fract(p * vec2(443.897, 441.423)); p += dot(p, p.yx + 19.19); return fract((p.x + p.y) * p.x); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
vec3 hue(float h) { return clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0); }
vec3 hsl2rgb(float h, float s, float l) {
    vec3 c = hue(h);
    float ch = (1.0 - abs(2.0 * l - 1.0)) * s;
    return l + ch * (c - 0.5);
}
vec3 contrast(vec3 c, float k) { return clamp((c - 0.5) * (1.0 + k) + 0.5, 0.0, 1.0); }
vec3 unpremul(vec4 p) { return p.a > 1e-4 ? p.rgb / p.a : vec3(0.0); }

uniform float uFrameT;   // video frame time in seconds (0 for photos)

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec3 c = clamp(below.rgb, 0.0, 1.0);
    vec3 o = c;
    float oa = below.a;
    float id = uFx;
    vec4 P = uFxP0, Q = uFxP1;
    float L = luma(c);
    vec2 dp = docPx() / uDocSize;   // 0..1 across the crop
    float longDoc = uLong / uPxPerDoc;

    if (id < 0.5) {                                     // Invert
        o = P.x < 0.5 ? 1.0 - c : setLum(c, 1.0 - lumN(c));
    } else if (id < 1.5) {                              // Sepia
        // toned print: dark umber shadows -> warm cream highlights, lightness kept
        float l = clamp((L - 0.5) * (1.0 + P.y) + 0.5, 0.0, 1.0);
        vec3 s = l < 0.5 ? mix(vec3(0.10, 0.05, 0.02), vec3(0.60, 0.42, 0.25), l * 2.0)
                         : mix(vec3(0.60, 0.42, 0.25), vec3(1.0, 0.95, 0.85), l * 2.0 - 1.0);
        o = mix(vec3(l), clamp(s, 0.0, 1.0), P.x);
    } else if (id < 2.5) {                              // Black & White (channel mix)
        o = contrast(vec3(clamp(dot(c, P.xyz), 0.0, 1.0)), P.w);
    } else if (id < 3.5) {                              // Duotone
        float t = clamp((L - 0.5) * (1.0 + P.x) + 0.5, 0.0, 1.0);
        o = mix(uFxC0, uFxC1, t);
    } else if (id < 4.5) {                              // Gradient map
        float m = P.x;
        float t = L < m ? 0.5 * L / m : 0.5 + 0.5 * (L - m) / (1.0 - m);
        o = t < 0.5 ? mix(uFxC0, uFxC1, t * 2.0) : mix(uFxC1, uFxC2, t * 2.0 - 1.0);
    } else if (id < 5.5) {                              // Split toning
        float m = 0.5 - Q.x * 0.35;
        float wH = smoothstep(m - 0.3, m + 0.3, L);
        vec3 th = hue(P.x / 360.0), ts = hue(P.z / 360.0);
        o = c + (th - luma(th)) * P.y * wH * 0.6 + (ts - luma(ts)) * P.w * (1.0 - wH) * 0.6;
        o = setLum(clamp(o, 0.0, 1.0), lumN(c));
    } else if (id < 6.5) {                              // Photo filter
        vec3 m = c * uFxC0 / max(lumN(uFxC0), 0.05);
        o = mix(c, clamp(m, 0.0, 1.0), P.x);
        if (P.y > 0.5) o = setLum(o, lumN(c));
    } else if (id < 7.5) {                              // Colorize
        float l = P.z > 0.0 ? mix(L, 1.0, P.z) : mix(L, 0.0, -P.z);
        o = hsl2rgb(P.x / 360.0, P.y, l);
    } else if (id < 8.5) {                              // Cross process
        vec3 s = c * c * (3.0 - 2.0 * c);
        vec3 x = vec3(mix(c.r, s.r, 0.9), mix(c.g, s.g, 0.6), 0.16 + c.b * 0.68);
        o = mix(c, x, P.x);
    } else if (id < 9.5) {                              // Bleach bypass
        vec3 ov = mix(2.0 * c * L, 1.0 - 2.0 * (1.0 - c) * (1.0 - L), step(0.5, c));
        vec3 x = mix(vec3(lumN(ov)), ov, 0.5);
        o = mix(c, x, P.x);
    } else if (id < 10.5) {                             // Infrared
        float ir = clamp(c.g * 1.35 + c.r * 0.45 - c.b * 0.55, 0.0, 1.0);
        o = mix(c, vec3(mix(L, ir, P.y)), P.x);
    } else if (id < 11.5) {                             // Posterize
        float n = max(2.0, floor(P.x + 0.5)) - 1.0;
        o = floor(c * n + 0.5) / n;
    } else if (id < 12.5) {                             // Threshold
        o = vec3(smoothstep(P.x - P.y, P.x + P.y + 1e-4, L));
    } else if (id < 13.5) {                             // Solarize
        o = mix(c, 1.0 - c, smoothstep(P.x - 0.02, P.x + 0.02, c));
    } else if (id < 15.5) {                             // Dodge / Burn
        float w = P.y < 0.5 ? 1.0 - smoothstep(0.0, 0.55, L) : (P.y < 1.5 ? 1.0 - smoothstep(0.0, 0.5, abs(L - 0.5)) : smoothstep(0.45, 1.0, L));
        float ev = P.x * (id < 14.5 ? 1.0 : -1.0) * w;
        o = toSrgb(toLin(c) * exp2(ev));
    } else if (id < 16.5) {                             // Vignette (centre/size relative to the crop)
        vec2 ctr = Q.xy;
        vec2 qa = (dp - ctr) * 2.0;
        vec2 qc = qa * uDocSize / max(uDocSize.x, uDocSize.y);
        float d = length(mix(qa, qc, P.z * 0.5 + 0.5));
        float r0 = mix(0.15, 1.25, P.y), f = mix(0.05, 1.3, P.w);
        float v = smoothstep(r0, r0 + f, d);
        vec3 lin = toLin(c);
        lin = P.x < 0.0 ? lin * (1.0 + P.x * v) : mix(lin, vec3(1.0), P.x * v);
        o = toSrgb(lin);
    } else if (id < 17.5) {                             // Glow (screen in linear light)
        vec3 g = texture2D(uMid, vUv * uBelowScale).rgb * P.z;
        vec3 lin = toLin(c);
        o = toSrgb(1.0 - (1.0 - lin) * (1.0 - clamp(g, 0.0, 1.0)) + max(g - 1.0, 0.0) * 0.25);
    } else if (id < 18.5) {                             // Film grain (in doc px, resolution independent)
        // a new pattern every video frame (stills: uFrameT = 0, the same as ever); a function of
        // the frame's time, so scrubbing, preview and export show the same grain at a moment
        vec2 g = docPx() / max(P.y * longDoc, 0.5) + floor(fract(vec2(uFrameT * 12.9898, uFrameT * 78.233)) * 397.0);
        float n = vnoise(g) + vnoise(g * 2.03 + 17.0) * 0.5 - 0.75;
        vec3 nc = vec3(vnoise(g + 31.0), vnoise(g + 57.0), vnoise(g + 83.0)) - 0.5;
        vec3 nn = mix(vec3(n), nc + n * 0.5, P.z);
        float w = 0.3 + 2.8 * L * (1.0 - L);
        o = c + nn * P.x * 0.38 * w;
    } else if (id < 19.5) {                             // Chromatic aberration
        vec2 off = (dp - 0.5) * 2.0 * P.x * uLong;
        o = vec3(tap(off, 0.0).r, c.g, tap(-off, 0.0).b);
    } else if (id < 20.5 || (id > 22.5 && id < 23.5)) { // Gaussian / Tilt-shift: the blurred input
        vec4 b = texture2D(uMid, vUv * uBelowScale);
        o = toSrgb(unpremul(b));
        oa = b.a;
    } else if (id < 21.5) {                             // Motion blur
        float d = P.x * uLong, a = radians(P.y);
        vec2 dir = vec2(cos(a), sin(a));
        float stp = d / 23.0, bias = max(0.0, log2(stp / 1.5));
        vec4 acc = vec4(0.0);
        for (int i = 0; i < 24; i++) {
            vec4 s = tap(dir * (float(i) * stp - d * 0.5), bias);
            acc += vec4(toLin(clamp(s.rgb, 0.0, 1.0)) * s.a, s.a);
        }
        acc /= 24.0;
        o = toSrgb(unpremul(acc));
        oa = acc.a;
    } else if (id < 22.5) {                             // Zoom blur
        vec2 v = (dp - vec2(P.y, P.z)) * uDocSize * uPxPerDoc;   // from the centre, viewport px
        float stp = length(v) * P.x / 23.0, bias = max(0.0, log2(max(stp, 1.0) / 1.5));
        vec4 acc = vec4(0.0);
        for (int i = 0; i < 24; i++) {
            vec4 s = tap(-v * P.x * float(i) / 23.0, bias);
            acc += vec4(toLin(clamp(s.rgb, 0.0, 1.0)) * s.a, s.a);
        }
        acc /= 24.0;
        o = toSrgb(unpremul(acc));
        oa = acc.a;
    } else if (id < 24.5) {                             // High pass (mid grey + detail)
        vec3 b = toSrgb(unpremul(texture2D(uMid, vUv * uBelowScale)));
        o = clamp(0.5 + (c - b), 0.0, 1.0);
    } else if (id < 25.5) {                             // Emboss
        float a = radians(P.y);
        vec2 d = vec2(cos(a), -sin(a)) * max(1.0, uLong * 0.0008);
        float e = (luma(tap(d, 0.0).rgb) - luma(tap(-d, 0.0).rgb)) * P.x * 2.0;
        o = mix(vec3(0.5 + e), c + e, P.z);
    } else if (id < 26.5) {                             // Find edges (Sobel)
        float s = max(1.0, uLong * 0.0006);
        float tl = luma(tap(vec2(-s, -s), 0.0).rgb), t = luma(tap(vec2(0.0, -s), 0.0).rgb), tr = luma(tap(vec2(s, -s), 0.0).rgb);
        float l = luma(tap(vec2(-s, 0.0), 0.0).rgb), r = luma(tap(vec2(s, 0.0), 0.0).rgb);
        float bl = luma(tap(vec2(-s, s), 0.0).rgb), b = luma(tap(vec2(0.0, s), 0.0).rgb), br = luma(tap(vec2(s, s), 0.0).rgb);
        float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl), gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);
        float g = clamp(length(vec2(gx, gy)) * P.x, 0.0, 1.0);
        o = vec3(P.y < 0.5 ? g : 1.0 - g);
    } else if (id < 27.5) {                             // Pencil sketch: colour-dodge of L by its local mean
        float r = max(1.0, P.y * uLong);
        float m = 0.0;
        for (int i = 0; i < 12; i++) {
            float a = float(i) * 0.5236;
            vec2 d = vec2(cos(a), sin(a)) * r * (i < 6 ? 1.0 : 0.5);
            m += luma(tap(d, 0.0).rgb);
        }
        m = (m / 12.0) * 0.85 + L * 0.15;
        float sk = clamp(L / max(m, 1e-3), 0.0, 1.0);
        sk = pow(sk, 1.0 + P.x * 3.0);
        o = mix(vec3(sk), c * (0.35 + 0.65 * sk), P.z);
    } else if (id < 28.5) {                             // Pixelate (cells in doc px)
        float cell = max(1.0, P.x * longDoc);
        vec2 cc = (floor(docPx() / cell) + 0.5) * cell;
        vec4 s = atDocPx(cc);
        o = s.rgb;
        oa = s.a;
    } else if (id < 29.5) {                             // Halftone
        float cell = max(2.0, P.x * longDoc), aa = 0.75 / uPxPerDoc;
        vec3 ink = vec3(0.0);
        for (int k = 0; k < 3; k++) {
            float ang = radians(P.y + (P.z > 0.5 ? float(k) * 30.0 - 15.0 : 0.0));
            mat2 R = mat2(cos(ang), sin(ang), -sin(ang), cos(ang));
            vec2 q = R * docPx();
            vec2 ctrq = (floor(q / cell) + 0.5) * cell;
            vec2 ctr = ctrq * R;   // inverse rotation (R is orthonormal)
            vec3 sc = clamp(atDocPx(ctr).rgb, 0.0, 1.0);
            float v = P.z > 0.5 ? 1.0 - (k == 0 ? sc.r : (k == 1 ? sc.g : sc.b)) : 1.0 - luma(sc);
            float rad = sqrt(v) * cell * 0.72;
            float cov = 1.0 - smoothstep(rad - aa, rad + aa, length(q - ctrq));
            if (k == 0) ink.r = cov; else if (k == 1) ink.g = cov; else ink.b = cov;
            if (P.z < 0.5) { ink = vec3(cov); break; }
        }
        o = 1.0 - ink;
    } else if (id < 30.5) {                             // Oil paint (Kuwahara, 4 sectors)
        float r = max(1.0, P.x * uLong);
        float stp = r / 2.0, bias = max(0.0, log2(stp / 1.5));
        vec3 best = c;
        float bestV = 1e9;
        for (int q = 0; q < 4; q++) {
            vec2 sgn = vec2(q == 0 || q == 2 ? -1.0 : 1.0, q < 2 ? -1.0 : 1.0);
            vec3 m = vec3(0.0);
            float s1 = 0.0, s2 = 0.0;
            for (int j = 0; j < 3; j++) {
                for (int i = 0; i < 3; i++) {
                    vec3 s = clamp(tap(sgn * vec2(float(i), float(j)) * stp, bias).rgb, 0.0, 1.0);
                    float l = luma(s);
                    m += s; s1 += l; s2 += l * l;
                }
            }
            m /= 9.0;
            float var = s2 / 9.0 - (s1 / 9.0) * (s1 / 9.0);
            if (var < bestV) { bestV = var; best = m; }
        }
        o = best;
    } else if (id > 46.5) {                             // Hue shift (47): turn around the grey axis, keep lightness
        float a = radians(P.x), ca = cos(a), sa = sin(a);
        vec3 k = vec3(0.57735);
        vec3 r = c * ca + cross(k, c) * sa + k * dot(k, c) * (1.0 - ca);   // Rodrigues rotation about (1,1,1)
        r = setLum(clamp(r, 0.0, 1.0), lumN(c));
        float l = lumN(r);
        o = clamp(mix(vec3(l), r, 1.0 + P.y), 0.0, 1.0);
    } else {                                            // Round corners (41): alpha only
        vec2 hs = 0.5 * uDocSize;
        float S = min(uDocSize.x, uDocSize.y);
        float r = P.x * S;
        vec2 q = abs(docPx() - hs) - (hs - r);
        float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        float aa = 0.6 / uPxPerDoc + P.y * S;
        oa = below.a * (1.0 - smoothstep(-aa, aa, dist));
    }

    float amt = amountAt(ip, below.rgb);
    vec4 res = compositeAdj(below, o, amt, uMode);
    res.a = mix(below.a, oa, amt);
    gl_FragColor = res;
}
