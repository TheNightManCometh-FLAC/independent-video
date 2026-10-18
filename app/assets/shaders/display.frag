// Draws the composite into the screen with the view transform, checkerboard for transparency,
// the full-resolution detail render where one is valid, and an optional red mask overlay.
// 3.5.4: a before/after split (uBefore left of uSplit), clipping warnings (red highlights,
// blue shadows) and a magnifier loupe for the sample tools.
uniform sampler2D uComp;
uniform sampler2D uDet;
uniform vec2 uScreen;     // surface size px
uniform vec2 uViewOff;    // screen px of doc origin (top-left)
uniform float uViewScale; // screen px per doc px
uniform vec3 uBg;
uniform float uMaskVis;     // 1 painted mask / selection (uMask), 2 affected area (uEff)
uniform sampler2D uEff;     // coverage of the selected layer, same layout as uComp
uniform float uDetOn;
uniform vec4 uDetRect;    // doc px rect the detail render covers
uniform vec4 uDetMap;     // detail uv = xy + (doc px - rect.xy) * zw
uniform sampler2D uBefore;  // the original (compare composite), same layout as uComp
uniform float uSplit;       // screen x px of the before/after divider; < 0 = no split
uniform float uClipVis;     // 1 = paint clipped highlights red and crushed shadows blue
uniform vec4 uLoupe;        // centre x, y (screen px), radius px, magnification; z <= 0 = off
uniform vec2 uLoupeAt;      // the screen point the loupe magnifies

const float HI = 254.5 / 255.0;   // rounds to 255 in an 8-bit export
const float LO = 0.5 / 255.0;     // rounds to 0

vec3 shade(vec2 sp) {
    vec2 dp = (sp - uViewOff) / uViewScale;
    vec2 uv = dp / uDocSize;
    // sample everything unconditionally: reads inside per-pixel branches can get the wrong mip level
    vec4 ca = texture2D(uComp, uv);
    vec4 cbf = texture2D(uBefore, uv);
    vec4 cd = texture2D(uDet, uDetMap.xy + (dp - uDetRect.xy) * uDetMap.zw);
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return uBg;
    float ch = mod(floor(sp.x / 14.0) + floor(sp.y / 14.0), 2.0);
    vec3 cb = mix(vec3(0.36), vec3(0.5), ch);
    bool before = uSplit >= 0.0 && sp.x < uSplit;
    vec4 c = before ? cbf : ca;
    if (!before && uDetOn > 0.5 && dp.x >= uDetRect.x && dp.y >= uDetRect.y
            && dp.x <= uDetRect.x + uDetRect.z && dp.y <= uDetRect.y + uDetRect.w) c = cd;
    float a = clamp(c.a, 0.0, 1.0);
    vec3 col = mix(cb, clamp(c.rgb, 0.0, 1.0), a);
    if (uClipVis > 0.5 && a > 0.5) {
        // every channel at the top: solid red (no detail left); some channels: a lighter red
        float nh = step(HI, c.r) + step(HI, c.g) + step(HI, c.b);
        if (nh > 2.5) col = vec3(1.0, 0.1, 0.08);
        else if (nh > 0.5) col = mix(col, vec3(1.0, 0.1, 0.08), 0.5);
        else if (max(c.r, max(c.g, c.b)) <= LO) col = vec3(0.12, 0.35, 1.0);
    }
    float m = 1.0;
    if (uMaskVis > 1.5) m = texture2D(uEff, uv).r;   // affected area: untouched parts red
    else if (uMaskVis > 0.5) {
        vec3 d = vec3(dp, 1.0);
        m = maskAt(vec2(dot(uM0, d), dot(uM1, d)));
    }
    if (!before) col = mix(col, vec3(1.0, 0.12, 0.1), 0.55 * (1.0 - m));
    return col;
}

void main() {
    vec2 sp = vec2(gl_FragCoord.x, uScreen.y - gl_FragCoord.y);
    // inside the loupe: a magnified view of the point under the finger (CanvasView draws the ring and sample box)
    if (uLoupe.z > 0.0 && length(sp - uLoupe.xy) < uLoupe.z) sp = uLoupeAt + (sp - uLoupe.xy) / uLoupe.w;
    gl_FragColor = vec4(shade(sp), 1.0);
}
