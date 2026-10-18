// Healing, step 1 (32 x 1 viewport, one fragment per boundary point): the colour difference
// destination - source around each boundary point, each side averaged over a 3 x 3 grid so
// texture and noise don't leak into the correction. Read back by retouch.frag.
uniform sampler2D uSrc;
uniform vec3 uL0;
uniform vec3 uL1;
uniform vec4 uSrcRect;
uniform vec2 uBnd[32];
uniform float uRad;
uniform vec2 uOff;
uniform float uDiffEnc;   // 1 = store diff*0.5+0.5 (8-bit fallback)

vec3 at(vec2 ip) {
    vec3 h = vec3(ip, 1.0);
    vec2 lp = vec2(dot(uL0, h), dot(uL1, h));
    vec4 t = texture2D(uSrc, (lp - uSrcRect.xy) / uSrcRect.zw);
    return t.a > 1e-5 ? t.rgb / t.a : vec3(0.0);
}

void main() {
    float idx = floor(gl_FragCoord.x);
    vec2 b = vec2(0.0);
    for (int k = 0; k < 32; k++) {
        if (float(k) == idx) b = uBnd[k];
    }
    float s = uRad * 0.12;
    vec3 d = vec3(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 o = vec2(float(i), float(j)) * s;
            d += at(b + o) - at(b + o + uOff);
        }
    }
    d /= 9.0;
    gl_FragColor = uDiffEnc > 0.5 ? vec4(d * 0.5 + 0.5, 1.0) : vec4(d, 1.0);
}
