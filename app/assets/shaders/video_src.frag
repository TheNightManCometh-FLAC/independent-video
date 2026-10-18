#extension GL_OES_EGL_image_external : require
// A decoded video frame (SurfaceTexture, external OES) placed into the output frame.
// uR0/uR1 map output px (top-down) to source px in its displayed orientation; uST is the
// SurfaceTexture transform (includes the container's rotation). Outside the source: alpha 0.
uniform samplerExternalOES uTexO;
uniform mat4 uST;
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
    vec2 tc = (uST * vec4(clamp(n.x, 0.0, 1.0), 1.0 - clamp(n.y, 0.0, 1.0), 0.0, 1.0)).xy;
    vec3 c = texture2D(uTexO, tc).rgb;
    gl_FragColor = vec4(c + (1.0 - c) * uFlash * 0.8, cov * uClipA);
}
