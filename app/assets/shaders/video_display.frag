// Preview: the composed frame letterboxed into the view (uView = x, y, w, h in screen px from
// the top), over the project background; outside it the editor's panel colour.
uniform sampler2D uComp;
uniform vec2 uCompScale;
uniform vec2 uScreen;
uniform vec4 uView;
uniform vec3 uBg;
uniform vec3 uPanel;

void main() {
    vec2 sp = vec2(gl_FragCoord.x, uScreen.y - gl_FragCoord.y);
    vec2 uv = (sp - uView.xy) / uView.zw;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) { gl_FragColor = vec4(uPanel, 1.0); return; }
    vec4 c = texture2D(uComp, uv * uCompScale);
    gl_FragColor = vec4(mix(uBg, clamp(c.rgb, 0.0, 1.0), clamp(c.a, 0.0, 1.0)), 1.0);
}
