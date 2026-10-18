// 3D LUT (.cube) as an adjustment: trilinear lookup in a (N*N) x N atlas, x = r + b*N, y = g.
// Hardware bilinear handles r and g; the two nearest blue slices are mixed by hand.
uniform sampler2D uLut3;
uniform float uLutSize;
uniform vec3 uDomMin;   // input domain the grid spans (Cube DOMAIN_MIN / MAX)
uniform vec3 uDomMax;

vec3 lut3(vec3 c) {
    float N = uLutSize;
    c = clamp((c - uDomMin) / max(uDomMax - uDomMin, vec3(1e-6)), 0.0, 1.0) * (N - 1.0);
    float b0 = floor(c.b);
    float b1 = min(b0 + 1.0, N - 1.0);
    float f = c.b - b0;
    float y = (c.g + 0.5) / N;
    vec2 uv0 = vec2((c.r + 0.5 + b0 * N) / (N * N), y);
    vec2 uv1 = vec2((c.r + 0.5 + b1 * N) / (N * N), y);
    return mix(texture2D(uLut3, uv0).rgb, texture2D(uLut3, uv1).rgb, f);
}

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec3 o = uLutSize > 1.5 ? lut3(below.rgb) : below.rgb;
    gl_FragColor = compositeAdj(below, o, amountAt(ip, below.rgb), uMode);
}
