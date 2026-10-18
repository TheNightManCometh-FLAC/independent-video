// Image layer or solid fill layer.
uniform sampler2D uSrc;
uniform vec3 uL0;        // img px -> layer px (row 0)
uniform vec3 uL1;        // img px -> layer px (row 1)
uniform vec2 uLSize;     // full-resolution layer size in layer px
uniform vec4 uSrcRect;   // layer px rect that uSrc covers (x, y, w, h)
uniform vec4 uFill;      // rgb, a > 0.5 = solid fill layer
uniform float uCovOut;   // 1: write this layer's coverage (alpha x mask) for layers clipped to it

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec3 cs;
    float a;
    if (uFill.a > 0.5) {
        cs = uFill.rgb;
        a = 1.0;
    } else {
        vec3 h = vec3(ip, 1.0);
        vec2 lp = vec2(dot(uL0, h), dot(uL1, h));
        vec4 t = texture2D(uSrc, (lp - uSrcRect.xy) / uSrcRect.zw);
        // one-layer-pixel soft edge so rotated layers don't stair-step
        float e = min(min(lp.x, uLSize.x - lp.x), min(lp.y, uLSize.y - lp.y));
        float inside = clamp(e + 0.5, 0.0, 1.0);
        a = t.a * inside;
        cs = t.a > 1e-5 ? t.rgb / t.a : vec3(0.0);   // Android bitmaps upload premultiplied
    }
    if (uCovOut > 0.5) { float c = a * maskAt(ip); gl_FragColor = vec4(c); return; }
    gl_FragColor = compositeOver(below, cs, a * amountAt(ip, below.rgb), uMode);
}
