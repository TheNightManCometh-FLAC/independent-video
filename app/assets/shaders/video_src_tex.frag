// A still image (Bitmap texture, top row at t = 0) placed into the output frame; same mapping as
// video_src.frag. Bitmaps upload premultiplied, so colour is un-premultiplied here.
uniform sampler2D uTexI;
uniform vec3 uR0;
uniform vec3 uR1;
uniform vec2 uSrcSize;
uniform vec2 uOutSize;
uniform float uClipA;   // clip opacity (keyframeable)
uniform float uFlash;   // music-driven brightness lift

void main() {
    vec3 h = vec3(vUv * uOutSize, 1.0);
    vec2 sp = vec2(dot(uR0, h), dot(uR1, h));
    vec2 n = sp / uSrcSize;
    float e = min(min(sp.x, sp.y), min(uSrcSize.x - sp.x, uSrcSize.y - sp.y));
    float cov = clamp(e + 0.5, 0.0, 1.0);
    if (cov <= 0.0) { gl_FragColor = vec4(0.0); return; }
    vec4 t = texture2D(uTexI, clamp(n, 0.0, 1.0));
    vec3 c = t.a > 1e-5 ? t.rgb / t.a : vec3(0.0);
    gl_FragColor = vec4(c + (1.0 - c) * uFlash * 0.8, cov * t.a * uClipA);
}
