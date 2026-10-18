// HSL / color mixer: 8 hue bands (red, orange, yellow, green, aqua, blue, purple, magenta).
// Each band is a tent between its neighbours' centres, so the weights always sum to 1.
uniform vec3 uHsl[8];   // hue shift (-1..1 = +-30 deg), saturation (-1..1), luminance (-1..1)

float cen(int i) {
    if (i == 0) return 0.0;
    if (i == 1) return 30.0;
    if (i == 2) return 60.0;
    if (i == 3) return 120.0;
    if (i == 4) return 180.0;
    if (i == 5) return 225.0;
    if (i == 6) return 270.0;
    return 315.0;
}
float wl(int i) {   // distance to previous centre
    if (i == 0) return 45.0;
    if (i == 1 || i == 2) return 30.0;
    if (i == 3 || i == 4) return 60.0;
    return 45.0;
}
float wr(int i) {   // distance to next centre
    if (i == 0 || i == 1) return 30.0;
    if (i == 2 || i == 3) return 60.0;
    return 45.0;
}

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec3 c = max(below.rgb, 0.0);
    vec3 hsv = rgb2hsv(c);
    float h = hsv.x * 360.0;
    vec3 sh = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float d = mod(h - cen(i) + 180.0, 360.0) - 180.0;
        float w = d < 0.0 ? max(0.0, 1.0 + d / wl(i)) : max(0.0, 1.0 - d / wr(i));
        sh += w * uHsl[i];
    }
    float colorful = smoothstep(0.0, 0.25, hsv.y);
    hsv.x = fract(hsv.x + sh.x * 30.0 / 360.0 + 1.0);
    hsv.y = clamp(hsv.y * (1.0 + sh.y), 0.0, 1.0);
    vec3 o = hsv2rgb(hsv);
    o *= 1.0 + 0.5 * sh.z * colorful;
    gl_FragColor = compositeAdj(below, o, amountAt(ip, below.rgb), uMode);
}
