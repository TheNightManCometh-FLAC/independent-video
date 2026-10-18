// Final 8-bit pass for export tiles and the histogram: clamp, optional matte, TPDF dither,
// premultiplied output to match Android Bitmap memory.
uniform sampler2D uComp;
uniform float uOpaque;    // 1 = flatten onto uMatte (JPEG)
uniform vec3 uMatte;
uniform float uDither;
uniform vec2 uCompScale;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec4 c = texture2D(uComp, vUv * uCompScale);
    vec3 col = clamp(c.rgb, 0.0, 1.0);
    float a = clamp(c.a, 0.0, 1.0);
    if (uOpaque > 0.5) { col = mix(uMatte, col, a); a = 1.0; }
    float n = hash(gl_FragCoord.xy) + hash(gl_FragCoord.xy + vec2(17.31, 5.17)) - 1.0;
    col = clamp(col + uDither * n / 255.0, 0.0, 1.0);
    gl_FragColor = vec4(col * a, a);
}
