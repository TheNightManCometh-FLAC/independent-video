// Generated effects: Julia, Mandelbrot, Fractal Folds (Kaliset) and Fractal Noise, coloured with
// a cyclic 3-stop palette - or, in "image" fill, used to fetch the picture from uSrc (the whole
// image under this layer), which turns the photo into the fractal's texture. The plane is tied to
// the crop: centre of the frame = Center X/Y, half the short side = 1 unit at zoom 0.
uniform float uFx;
uniform vec4 uFxP0;
uniform vec4 uFxP1;
uniform vec3 uFxC0;
uniform vec3 uFxC1;
uniform vec3 uFxC2;
uniform sampler2D uSrc;
uniform vec4 uSrcRect;
uniform vec2 uSrcScale;

const float TAU = 6.28318531;

vec3 palette(float t) {
    t = fract(t) * 3.0;
    if (t < 1.0) return mix(uFxC0, uFxC1, smoothstep(0.0, 1.0, t));
    if (t < 2.0) return mix(uFxC1, uFxC2, smoothstep(0.0, 1.0, t - 1.0));
    return mix(uFxC2, uFxC0, smoothstep(0.0, 1.0, t - 2.0));
}
vec4 image(vec2 frac) {   // frac 0..1 across the crop, mirrored at the edges
    vec2 f = abs(fract(frac * 0.5) * 2.0 - 1.0);
    f = 1.0 - f;
    vec2 d = f * uDocSize;
    return texture2D(uSrc, clamp((d - uSrcRect.xy) / uSrcRect.zw, 0.0, 1.0) * uSrcScale);
}
vec2 rot2(vec2 q, float a) { float c = cos(a), s = sin(a); return vec2(c * q.x - s * q.y, s * q.x + c * q.y); }
float h21(vec2 p) { p = fract(p * vec2(234.34, 435.345)); p += dot(p, p + 34.23); return fract(p.x * p.y); }
float vn(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h21(i), h21(i + vec2(1.0, 0.0)), f.x), mix(h21(i + vec2(0.0, 1.0)), h21(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p, float oct, float rough, float style) {
    float a = 0.5, s = 0.0, norm = 0.0;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= oct) break;
        float n = vn(p);
        if (style > 0.5 && style < 1.5) n = abs(n * 2.0 - 1.0);                 // turbulence
        else if (style > 1.5 && style < 2.5) { n = 1.0 - abs(n * 2.0 - 1.0); n *= n; }   // ridged
        s += a * n;
        norm += a;
        a *= rough;
        p = rot2(p, 0.7) * 2.03 + 17.0;
    }
    return s / norm;
}

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec4 P = uFxP0, Q = uFxP1;
    vec2 d = docPx();
    float S = min(uDocSize.x, uDocSize.y);
    vec2 n = (d - 0.5 * uDocSize) / (0.5 * S);          // frame centre 0, short half-side 1
    n.y = -n.y;                                          // maths orientation: imaginary axis up
    vec3 o = below.rgb;
    float oa = below.a;
    bool img = false;
    vec2 imgF = vec2(0.0);

    if (uFx < 43.5 || (uFx > 43.5 && uFx < 44.5)) {   // Julia (43) / Mandelbrot (44)
        bool jul = uFx < 43.5;
        float zoomStops = jul ? P.z : P.z;
        float its = jul ? Q.y : Q.x;
        float cyc = jul ? Q.z : Q.y;
        img = jul ? Q.w > 0.5 : Q.z > 0.5;
        vec2 pl = (jul ? rot2(n, 0.0) : rot2(n, radians(P.w))) / exp2(zoomStops);
        vec2 z, c;
        if (jul) { z = pl * 1.5 + vec2(P.w, Q.x); c = vec2(P.x, P.y); }
        else { z = vec2(0.0); c = pl * 1.5 + vec2(P.x, P.y); }
        float esc = -1.0;
        for (int i = 0; i < 400; i++) {
            if (float(i) >= its) break;
            z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
            if (dot(z, z) > 256.0) { esc = float(i); break; }
        }
        if (esc < 0.0) {
            o = uFxC0 * 0.6;
            imgF = z * 0.5 + 0.5;
        } else {
            float nu = esc + 1.0 - log2(log(length(z)));
            o = palette(nu * 0.035 + cyc);
            imgF = vec2(atan(z.y, z.x) / TAU + cyc, nu * 0.04);
        }
    } else if (uFx < 45.5) {                            // Fractal folds (Kaliset)
        img = Q.z > 0.5;
        vec2 p = rot2(n, radians(Q.x)) / exp2(P.z) * 0.9;
        vec2 fold = vec2(P.x, P.y);
        float acc = 0.0, prev = 0.0;
        for (int i = 0; i < 24; i++) {
            if (float(i) >= P.w) break;
            p = abs(p) / max(dot(p, p), 1e-4) - fold;
            float l = dot(p, p);
            acc += exp(-Q.y * abs(l - prev) * 2.0);
            prev = l;
        }
        float t = acc / max(1.0, P.w);
        o = palette(t * 1.2);
        imgF = p * 0.25 + 0.5;
    } else {                                            // Fractal noise
        float style = floor(P.x + 0.5);
        float L = max(uDocSize.x, uDocSize.y);
        vec2 p = d / max(1.0, P.y * L) + Q.y * 7.31;
        if (Q.z > 0.0) p += Q.z * 2.0 * vec2(fbm(p + 3.1, 4.0, 0.5, 0.0), fbm(p + 9.7, 4.0, 0.5, 0.0)) - Q.z;
        float t = fbm(p, P.z, P.w, style > 2.5 ? 0.0 : style);
        if (style > 2.5) t = 0.5 + 0.5 * sin(p.x * 3.0 + t * 12.0);   // marble veins
        t = clamp((t - 0.5) * (1.0 + Q.x) * (style > 0.5 ? 1.4 : 1.8) + 0.5, 0.0, 1.0);
        o = mix(uFxC0, uFxC1, t);
    }

    if (img) {
        vec4 s = image(imgF);
        o = s.rgb;
        oa = s.a;
    }
    float amt = amountAt(ip, below.rgb);
    vec4 res = compositeAdj(below, o, amt, uMode);
    res.a = mix(below.a, img ? oa : 1.0, amt);
    gl_FragColor = res;
}
