// Curves: per-channel curves, then the RGB master curve. LUT is N x 1, r/g/b channels, a = master.
uniform sampler2D uLut;
uniform float uLutN;

float lu(float v) { return (clamp(v, 0.0, 1.0) * (uLutN - 1.0) + 0.5) / uLutN; }

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec3 c = below.rgb;
    vec3 y = vec3(texture2D(uLut, vec2(lu(c.r), 0.5)).r,
                  texture2D(uLut, vec2(lu(c.g), 0.5)).g,
                  texture2D(uLut, vec2(lu(c.b), 0.5)).b);
    y = vec3(texture2D(uLut, vec2(lu(y.r), 0.5)).a,
             texture2D(uLut, vec2(lu(y.g), 0.5)).a,
             texture2D(uLut, vec2(lu(y.b), 0.5)).a);
    gl_FragColor = compositeAdj(below, y, amountAt(ip, below.rgb), uMode);
}
