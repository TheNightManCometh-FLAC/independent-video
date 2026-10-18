// Healing/cloning, step 2: one stroke chunk, drawn with GL blending onto the current buffer and
// scissored to the stroke's box. It copies the target photo layer's own pixels from p + uOff.
// Healing adds the boundary mismatch measured by retouch_bnd.frag (destination - source just
// outside the stroke), interpolated inward with Shepard weights, so the patch takes on the
// surrounding tone and colour while keeping the source's texture.
uniform sampler2D uSrc;
uniform vec3 uL0;
uniform vec3 uL1;
uniform vec4 uSrcRect;
uniform vec2 uPts[16];
uniform float uNPts;
uniform float uRad;       // img px
uniform float uFeather;   // 0..1 of the radius
uniform vec2 uOff;        // img px, source = destination + uOff
uniform float uHeal;
uniform vec2 uBnd[32];
uniform float uNBnd;
uniform sampler2D uDiff;  // 32 x 1 boundary differences
uniform float uDiffEnc;

vec2 srcUv(vec2 ip) {
    vec3 h = vec3(ip, 1.0);
    vec2 lp = vec2(dot(uL0, h), dot(uL1, h));
    return (lp - uSrcRect.xy) / uSrcRect.zw;
}
vec4 unpm(vec4 t) { return vec4(t.a > 1e-5 ? t.rgb / t.a : vec3(0.0), t.a); }
float segDist(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
    return length(p - a - ab * t);
}

void main() {
    vec2 ip = imgPx();
    vec2 prev = uPts[0];
    float d = length(ip - prev);
    for (int i = 1; i < 16; i++) {
        if (float(i) >= uNPts) break;
        vec2 cur = uPts[i];
        d = min(d, segDist(ip, prev, cur));
        prev = cur;
    }
    float cov = (1.0 - smoothstep(uRad * (1.0 - 0.6 * uFeather), uRad, d)) * maskAt(ip);   // the layer mask hides strokes too
    if (cov <= 0.0) discard;
    vec4 s = unpm(texture2D(uSrc, srcUv(ip + uOff)));
    vec3 col = s.rgb;
    if (uHeal > 0.5) {
        vec3 corr = vec3(0.0);
        float ws = 0.0;
        for (int k = 0; k < 32; k++) {
            if (float(k) >= uNBnd) break;
            vec2 b = uBnd[k];
            vec3 df = texture2D(uDiff, vec2((float(k) + 0.5) / 32.0, 0.5)).rgb;
            if (uDiffEnc > 0.5) df = df * 2.0 - 1.0;
            vec2 dv = (ip - b) / uRad;
            float w = 1.0 / pow(dot(dv, dv) + 0.02, 1.5);
            corr += w * df;
            ws += w;
        }
        col += corr / max(ws, 1e-6);
    }
    gl_FragColor = vec4(col, cov * s.a * uOpacity);
}
