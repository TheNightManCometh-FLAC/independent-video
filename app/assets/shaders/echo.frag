// One echo of a visualizer (After Effects' Echo / Trapcode Echospace, with a transform per
// echo): the visualizer as it was k echoes ago, zoomed and turned about the box centre, hue
// turned and faded. Drawn oldest first into an accumulation buffer with premultiplied blending.
uniform sampler2D uCur;      // the visualizer at the echo's moment
uniform vec2 uCurScale;      // valid part of uCur
uniform float uZoom;         // > 1 the echo is bigger (tunnel), < 1 smaller (implode)
uniform float uRot;          // radians
uniform float uHue;          // radians of hue turn
uniform float uI;            // intensity
uniform float uAspect;       // box width / height

vec3 hueTurn(vec3 c, float a) {
    vec3 k = vec3(0.57735);
    float ca = cos(a), sa = sin(a);
    return c * ca + cross(k, c) * sa + k * dot(k, c) * (1.0 - ca);
}

void main() {
    vec2 p = vUv - 0.5;
    p.x *= uAspect;
    float c = cos(uRot), s = sin(uRot);
    vec2 q = vec2(c * p.x + s * p.y, -s * p.x + c * p.y) / uZoom;
    q.x /= uAspect;
    q += 0.5;
    vec4 o = vec4(0.0);
    if (q.x >= 0.0 && q.y >= 0.0 && q.x <= 1.0 && q.y <= 1.0) {
        o = texture2D(uCur, q * uCurScale);
        // soften the box's own edges so a turned or shrunk echo never shows a rectangle
        vec2 e = min(q, 1.0 - q);
        o *= smoothstep(0.0, 0.06, e.x) * smoothstep(0.0, 0.06, e.y);
    }
    if (uHue != 0.0) { o.rgb = max(hueTurn(o.rgb, uHue), 0.0); o.rgb = min(o.rgb, vec3(o.a)); }
    gl_FragColor = o * uI;
}
