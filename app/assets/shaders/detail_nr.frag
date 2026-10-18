// Detail layer, pass 1: edge-aware noise reduction. Luminance gets a 5x5 bilateral filter at
// image-pixel spacing; color (chroma) is smoothed over twice that reach, weighted so it never
// bleeds across luminance or hue edges. Output is the cleaned image; pass 2 sharpens it.
uniform vec4 uDet0;   // sharpen amount, radius (img px), threshold, edge masking
uniform vec4 uDet1;   // NR luminance, NR color, -, -

vec3 tap(vec2 o) { return texture2D(uBelow, vUv * uBelowScale + o * uTexel).rgb; }

void main() {
    vec4 c0 = belowAt();
    float nl = uDet1.x, nc = uDet1.y;
    if (nl < 0.001 && nc < 0.001) { gl_FragColor = c0; return; }
    vec3 c = c0.rgb;
    float sp = max(1.0, uPxPerDoc);
    float Y0 = dot(c, LW);
    vec2 C0 = vec2(c.b - Y0, c.r - Y0);
    float sr = 0.01 + 0.12 * nl;           // luminance range sigma
    float sc = 0.015 + 0.2 * nc;           // chroma range sigma
    float ys = 0.0, yw = 0.0, cw = 0.0;
    vec2 cs = vec2(0.0);
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 o = vec2(float(i), float(j));
            float ws = exp(-dot(o, o) / 4.5);
            vec3 s = tap(o * sp);
            float Y = dot(s, LW);
            float dy = Y - Y0;
            float w = ws * exp(-dy * dy / (2.0 * sr * sr));
            ys += w * Y;
            yw += w;
            vec3 s2 = tap(o * sp * 2.0);
            float Y2 = dot(s2, LW);
            vec2 C2 = vec2(s2.b - Y2, s2.r - Y2);
            vec2 dc = C2 - C0;
            float dl = Y2 - Y0;
            float w2 = ws * exp(-dot(dc, dc) / (2.0 * sc * sc) - dl * dl / 0.02);
            cs += w2 * C2;
            cw += w2;
        }
    }
    float Yn = mix(Y0, ys / max(yw, 1e-6), clamp(nl * 1.5, 0.0, 1.0));
    vec2 Cn = mix(C0, cs / max(cw, 1e-6), clamp(nc * 1.5, 0.0, 1.0));
    float r = Cn.y + Yn, b = Cn.x + Yn;
    float g = (Yn - LW.r * r - LW.b * b) / LW.g;
    gl_FragColor = vec4(r, g, b, c0.a);
}
