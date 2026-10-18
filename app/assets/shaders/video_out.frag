// Final export pass into the encoder's window surface: composite over the project background,
// clamp, TPDF dither, opaque. Window surfaces have row 0 at the bottom, so flip.
uniform sampler2D uComp;
uniform vec2 uCompScale;
uniform vec3 uBg;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec4 c = texture2D(uComp, vec2(vUv.x, 1.0 - vUv.y) * uCompScale);
    vec3 col = mix(uBg, clamp(c.rgb, 0.0, 1.0), clamp(c.a, 0.0, 1.0));
    float n = hash(gl_FragCoord.xy) + hash(gl_FragCoord.xy + vec2(17.31, 5.17)) - 1.0;
    gl_FragColor = vec4(clamp(col + n / 255.0, 0.0, 1.0), 1.0);
}
