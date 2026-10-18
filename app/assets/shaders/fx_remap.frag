// Geometry effects: each output pixel fetches the image from another place (doc px -> doc px),
// then mixes in by opacity x mask x Blend If like every adjustment. map() is the twin of
// Remap.map() in Java, which plans export tiles, and must stay identical to it.
// uSrc holds the image under this layer for the doc rect uSrcRect (x, y, w, h in doc px); its
// valid part is uSrcScale of the texture. In a whole-frame render that's simply the layer below.
uniform float uFx;
uniform vec4 uFxP0;
uniform vec4 uFxP1;
uniform sampler2D uSrc;
uniform vec4 uSrcRect;
uniform vec2 uSrcScale;

const float TAU = 6.28318531;

float mrep(float x, float w) {
    float m = x - floor(x / (2.0 * w)) * 2.0 * w;
    return m > w ? 2.0 * w - m : m;
}
vec2 rot2(vec2 q, float a) { float c = cos(a), s = sin(a); return vec2(c * q.x - s * q.y, s * q.x + c * q.y); }

vec2 map(vec2 xy, float id, vec4 P, vec4 Q, float W, float H) {
    float S = min(W, H), L = max(W, H);
    float x = xy.x, y = xy.y, sx = x, sy = y;
    if (id < 31.5) {                                    // Mirror
        float axis = floor(P.x + 0.5);
        bool second = P.y > 0.5;
        if (axis < 0.5 || axis > 1.5) {
            float X = P.z * W;
            if (!second && x > X) sx = 2.0 * X - x;
            if (second && x < X) sx = 2.0 * X - x;
        }
        if (axis > 0.5) {
            float Y = P.w * H;
            if (!second && y > Y) sy = 2.0 * Y - y;
            if (second && y < Y) sy = 2.0 * Y - y;
        }
        sx = mrep(sx, W); sy = mrep(sy, H);
    } else if (id < 32.5) {                             // Flip
        float d = floor(P.x + 0.5);
        if (d < 0.5 || d > 1.5) sx = W - x;
        if (d > 0.5) sy = H - y;
    } else if (id < 33.5) {                             // Reflection
        float Y = P.x * H;
        if (y > Y) {
            float d = y - Y;
            float A = P.z * L, lam = max(1e-3, P.w * L);
            float grow = d / max(1.0, H - Y);
            sx = x + A * sin(TAU * d / lam) * grow;
            sy = Y - d + A * 0.5 * cos(TAU * d / (lam * 0.7)) * grow;
            sx = mrep(sx, W); sy = mrep(sy, H);
        }
    } else if (id < 34.5) {                             // Offset
        float ux = x - P.x * W, uy = y - P.y * H;
        if (P.z < 0.5) { sx = ux - floor(ux / W) * W; sy = uy - floor(uy / H) * H; }
        else { sx = mrep(ux, W); sy = mrep(uy, H); }
    } else if (id < 35.5) {                             // Tile
        float cols = max(1.0, floor(P.x + 0.5)), rows = max(1.0, floor(P.y + 0.5));
        float u = x / W * cols, v = y / H * rows;
        float fu = fract(u), fv = fract(v);
        if (P.z > 0.5) {
            if (mod(floor(u), 2.0) > 0.5) fu = 1.0 - fu;
            if (mod(floor(v), 2.0) > 0.5) fv = 1.0 - fv;
        }
        sx = fu * W; sy = fv * H;
    } else if (id < 36.5) {                             // Kaleidoscope
        float cx = P.w * W, cy = Q.x * H;
        vec2 q = (xy - vec2(cx, cy)) / P.z;
        float n = max(2.0, floor(P.x + 0.5));
        float seg = TAU / n, rot = radians(P.y);
        float r = length(q);
        float a = atan(q.y, q.x) - rot;
        a = a - floor(a / seg) * seg;
        if (a > seg * 0.5) a = seg - a;
        a += radians(Q.z);
        q = r * vec2(cos(a), sin(a));
        float k = floor(Q.y + 0.5);
        for (int i = 0; i < 5; i++) {
            if (float(i) >= k) break;
            q = abs(q) - S * 0.18;
            q = rot2(q, rot + 0.6) * 1.25;
        }
        sx = mrep(cx + q.x, W); sy = mrep(cy + q.y, H);
    } else if (id < 37.5) {                             // Polar / little planet
        float cx = P.w * W, cy = Q.x * H, R = 0.5 * S * P.z;
        float rot = radians(P.y);
        if (P.x < 0.5) {
            vec2 q = xy - vec2(cx, cy);
            float a = atan(q.x, -q.y) + rot;
            float r = length(q);
            sx = fract(a / TAU + 0.5) * W;
            sy = clamp(H * (1.0 - r / R), 0.0, H);
        } else {
            float a = (x / W - 0.5) * TAU - rot;
            float r = (1.0 - y / H) * R;
            sx = mrep(cx + r * sin(a), W); sy = mrep(cy - r * cos(a), H);
        }
    } else if (id < 38.5) {                             // Twirl
        vec2 c = vec2(P.z * W, P.w * H);
        vec2 q = xy - c;
        float R = P.y * S, r = length(q);
        if (r < R) { float t = 1.0 - r / R; q = rot2(q, radians(P.x) * t * t); }
        sx = mrep(c.x + q.x, W); sy = mrep(c.y + q.y, H);
    } else if (id < 39.5) {                             // Pinch / bulge
        vec2 c = vec2(P.z * W, P.w * H);
        vec2 q = xy - c;
        float R = P.y * S, r = length(q);
        if (r < R && r > 1e-6) { float n = r / R; q *= pow(n, 1.0 + max(-0.9, P.x)) / n; }
        sx = mrep(c.x + q.x, W); sy = mrep(c.y + q.y, H);
    } else if (id < 40.5) {                             // Wave / ripple
        float A = P.x * L, lam = max(1e-3, P.y * L), ph = radians(P.z);
        float st = floor(P.w + 0.5);
        if (st < 0.5 || (st > 1.5 && st < 2.5)) sx = x + A * sin(TAU * y / lam + ph);
        if (st > 0.5 && st < 2.5) sy = y + A * sin(TAU * x / lam + ph);
        if (st > 2.5) {
            vec2 q = xy - vec2(Q.x * W, Q.y * H);
            float r = max(1e-6, length(q));
            float d = A * sin(TAU * r / lam - ph);
            sx = x + q.x / r * d; sy = y + q.y / r * d;
        }
        sx = mrep(sx, W); sy = mrep(sy, H);
    } else {                                            // Droste (42)
        vec2 c = vec2(P.w * W, Q.x * H);
        float s = clamp(P.x, 0.1, 0.9);
        vec2 q = rot2(xy - c, radians(P.z));
        vec2 hs = vec2(W, H) * 0.5;
        if (P.y > 0.5) {
            vec2 n = q / hs;
            float lr = log(max(1e-6, length(n))), a = atan(n.y, n.x);
            float f = log(1.0 / s), al = atan(f / TAU), ca = cos(al);
            float u = ca * (lr * cos(al) - a * sin(al)), v = ca * (lr * sin(al) + a * cos(al));
            q = exp(u) * vec2(cos(v), sin(v)) * hs;
        }
        q *= pow(s, Q.y);
        for (int i = 0; i < 24; i++) {
            float nrm = max(abs(q.x) / hs.x, abs(q.y) / hs.y);
            if (nrm >= s) break;
            q /= s;
        }
        sx = mrep(c.x + q.x, W); sy = mrep(c.y + q.y, H);
    }
    return vec2(sx, sy);
}

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    float W = uDocSize.x, H = uDocSize.y;
    vec2 d = docPx();
    vec2 s = map(d, uFx, uFxP0, uFxP1, W, H);
    vec2 uv = (s - uSrcRect.xy) / uSrcRect.zw;
    vec4 src = texture2D(uSrc, clamp(uv, 0.0, 1.0) * uSrcScale);
    vec3 o = src.rgb;
    float oa = src.a;
    if (uFx > 32.5 && uFx < 33.5 && d.y > uFxP0.x * H) {   // reflection: darker and fading with depth
        float grow = (d.y - uFxP0.x * H) / max(1.0, H - uFxP0.x * H);
        o *= (1.0 - uFxP1.x) * (1.0 - uFxP0.y * grow);
    }
    float amt = amountAt(ip, below.rgb);
    vec4 res = compositeAdj(below, o, amt, uMode);
    res.a = mix(below.a, oa, amt);
    gl_FragColor = res;
}
