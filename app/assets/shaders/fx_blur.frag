// One separable Gaussian pass for blur-based effect layers (Gaussian Blur, Glow, Tilt-Shift,
// High Pass). Works in linear light with premultiplied alpha so blurs don't darken edges or
// bleed colour from transparent areas. Wide blurs read a prefiltered mip level (texture bias):
// a fixed 17 taps stay smooth at any radius.
uniform sampler2D uSrc;
uniform vec2 uDir;        // (1, 0) or (0, 1)
uniform float uSigma;     // viewport px
uniform float uFirst;     // 1: source is the composite (display-referred, straight alpha)
uniform float uThresh;    // glow: keep only light above this (first pass); < 0 off
uniform vec4 uTilt;       // tilt-shift: focus y (fraction of height), half-width, transition, on
uniform vec2 uTiltN;      // tilt-shift: unit normal of the focus line

vec4 fetch(vec2 uv, float bias) {
    vec4 c = texture2D(uSrc, uv, bias);
    if (uFirst > 0.5) {
        vec3 l = toLin(max(c.rgb, 0.0));
        if (uThresh >= 0.0) l *= smoothstep(uThresh, uThresh + 0.2, dot(l, LW));
        return vec4(l * c.a, c.a);
    }
    return c;
}

void main() {
    float sigma = uSigma;
    if (uTilt.w > 0.5) {
        vec2 dp = docPx() / uDocSize.y;
        vec2 c0 = vec2(0.5 * uDocSize.x / uDocSize.y, uTilt.x);
        float d = abs(dot(dp - c0, uTiltN));
        sigma *= smoothstep(uTilt.y, uTilt.y + uTilt.z, d);
    }
    vec2 uv = vUv * uBelowScale;
    if (sigma < 0.35) { gl_FragColor = fetch(uv, 0.0); return; }
    float stp = max(1.0, sigma * 3.0 / 8.0);
    float bias = max(0.0, log2(stp / 1.5));
    vec4 acc = vec4(0.0);
    float wsum = 0.0;
    for (int i = -8; i <= 8; i++) {
        float x = float(i) * stp;
        float w = exp(-0.5 * x * x / (sigma * sigma));
        acc += w * fetch(uv + uDir * x * uTexel, bias);
        wsum += w;
    }
    gl_FragColor = acc / wsum;
}
