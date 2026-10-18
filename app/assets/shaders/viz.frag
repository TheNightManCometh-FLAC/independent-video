// Music visualizer, drawn into its own box-sized buffer (vUv.y = 0 is the box TOP); the result is
// then placed like a text layer (move / scale / rotate / blend mode / opacity / keyframes).
// uData (512 x 36, luminance) rows: 0 bar levels (texels 0..uBars-1); 1 waveform, 512 samples
// (0.5 = 0); 2 peak caps; 3..34 bar levels in the past (row 3 = now, each next row uHistDt older);
// 35 overall level over the last uHistSpan seconds (texel 0 = now).
// Output is premultiplied (layer_pixel un-premultiplies).
uniform sampler2D uData;
uniform float uType;     // 0 bars, 1 mirrored bars, 2 radial, 3 waveform, 4 ring, 5 mountain, 6 circle,
                         // 7 LED meter, 8 ridges, 9 waterfall, 10 shockwave
uniform float uBars;
uniform float uGap;      // 0..0.9 of each bar slot
uniform vec3 uC1;        // low / bottom colour
uniform vec3 uC2;        // high / top colour
uniform vec3 uC3;        // third stop (uStops = 3)
uniform float uStops;
uniform float uColorMode;   // 0 classic (per style), 1 by height, 2 across, 3 rainbow, 4 by loudness
uniform float uHueOff;      // rainbow: hue offset (turns), animated with time
uniform float uThick;    // line width (waveform, ring, edges) in box px
uniform float uGlow;     // 0..1
uniform float uInner;    // radial / ring / circle inner radius, fraction of the half size
uniform float uLevel;    // bass level 0..1 (ring, bounce)
uniform vec2 uBoxPx;     // buffer size in px
uniform float uRound;    // bar corner roundness 0..1
uniform float uCaps;     // bars / LED: draw falling peak caps
uniform float uMirrorX;  // horizontal styles: low frequencies in the middle, mirrored out to both sides
uniform float uFlip;     // horizontal styles: grow down from the top
uniform float uFill;     // mountain / circle / ridges: fill opacity
uniform float uBounce;   // the whole picture scales with the bass
uniform float uSegs;     // LED: cells per column
uniform float uRidges;   // ridges: how many lines (<= 32)
uniform float uAmp;      // ridges / mountain: height of the peaks

const float PI = 3.14159265;
const float ROWS = 36.0;

float rowv(float row, float i) { return texture2D(uData, vec2((i + 0.5) / 512.0, (row + 0.5) / ROWS)).r; }
float barAt(float i) { return rowv(0.0, clamp(i, 0.0, uBars - 1.0)); }
float capAt(float i) { return rowv(2.0, clamp(i, 0.0, uBars - 1.0)); }
float waveAt(float u) { return texture2D(uData, vec2(clamp(u, 0.0, 1.0) * (511.0 / 512.0) + 0.5 / 512.0, 1.5 / ROWS)).r * 2.0 - 1.0; }
float levelAgo(float a) { return texture2D(uData, vec2(clamp(a, 0.0, 1.0) * (511.0 / 512.0) + 0.5 / 512.0, 35.5 / ROWS)).r; }

// Catmull-Rom through a row's bars at u (0..1 across all bars): smooth spectrum curves
float smoothRow(float row, float u) {
    float x = clamp(u, 0.0, 1.0) * (uBars - 1.0);
    float i = floor(x), t = x - i;
    float p0 = rowv(row, clamp(i - 1.0, 0.0, uBars - 1.0)), p1 = rowv(row, i);
    float p2 = rowv(row, min(i + 1.0, uBars - 1.0)), p3 = rowv(row, min(i + 2.0, uBars - 1.0));
    float v = 0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t * t + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t * t * t);
    return clamp(v, 0.0, 1.0);
}

vec3 hsv(float h, float s, float v) {
    vec3 k = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), k, s);
}

vec3 pal(float t) {
    t = clamp(t, 0.0, 1.0);
    if (uStops < 2.5) return mix(uC1, uC2, t);
    return t < 0.5 ? mix(uC1, uC2, t * 2.0) : mix(uC2, uC3, t * 2.0 - 1.0);
}

// colour from the style's natural coordinate (classic), height, across, rainbow or loudness
vec3 colorAt(float classic, float h, float u, float v) {
    if (uColorMode < 0.5) return pal(classic);
    if (uColorMode < 1.5) return pal(h);
    if (uColorMode < 2.5) return pal(u);
    if (uColorMode < 3.5) return hsv(fract(u + uHueOff), 0.8, 1.0) * mix(0.8, 1.15, h);
    return pal(v);
}

// signed distance to an axis-aligned box (centre c, half size h) with corner radius r
float sdBox(vec2 p, vec2 c, vec2 h, float r) {
    vec2 q = abs(p - c) - h + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

vec4 shade(float d, vec3 col, float glowR) {
    float core = clamp(0.5 - d, 0.0, 1.0);
    float glow = uGlow * exp(-max(d, 0.0) / max(glowR, 1.0)) * 0.85;
    float a = max(core, glow * (1.0 - core));
    return vec4(col * a, a);
}

vec4 over(vec4 top, vec4 under) { return top + under * (1.0 - top.a); }

void main() {
    vec2 px = vUv * uBoxPx;              // y down
    float W = uBoxPx.x, H = uBoxPx.y;
    vec2 ctr = vec2(W, H) * 0.5;
    if (uBounce > 0.0) px = ctr + (px - ctr) / (1.0 + uBounce * uLevel * 0.35);
    bool horiz = uType < 1.5 || (uType > 4.5 && uType < 5.5) || (uType > 6.5 && uType < 9.5);
    if (horiz && uMirrorX > 0.5) px.x = abs(px.x - W * 0.5) * 2.0;
    if (horiz && uFlip > 0.5) px.y = H - px.y;
    float glowR = 0.04 * min(W, H) + 2.0;
    vec4 o = vec4(0.0);
    if (uType < 1.5) {
        // ---- bars (bottom-up) or mirrored about the middle, optional falling peak caps
        float slot = W / uBars;
        float bw = max(1.0, slot * (1.0 - uGap));
        float rr = uRound * bw * 0.5;
        float i0 = floor(px.x / slot);
        float best = 1e9;
        float bestV = 0.0;
        for (int k = -2; k <= 2; k++) {
            float i = i0 + float(k);
            if (i < 0.0 || i > uBars - 1.0) continue;
            float v = barAt(i);
            float cx = (i + 0.5) * slot;
            float d;
            if (uType < 0.5) {
                float h = max(bw * 0.5, v * (H - glowR));
                d = sdBox(px, vec2(cx, H - h * 0.5), vec2(bw * 0.5, h * 0.5), min(rr, h * 0.5));
                if (uCaps > 0.5) {
                    float ch = max(2.0, bw * 0.22);
                    float cy = H - max(capAt(i) * (H - glowR), bw * 0.5) - ch * 1.5;
                    d = min(d, sdBox(px, vec2(cx, cy), vec2(bw * 0.5, ch * 0.5), min(rr, ch * 0.5)));
                }
            } else {
                float h = max(bw * 0.5, v * (H - 2.0 * glowR));
                d = sdBox(px, vec2(cx, H * 0.5), vec2(bw * 0.5, h * 0.5), min(rr, h * 0.5));
            }
            if (d < best) { best = d; bestV = v; }
        }
        float t = uType < 0.5 ? 1.0 - px.y / H : abs(px.y / H - 0.5) * 2.0;
        o = shade(best, colorAt(t, t, px.x / W, bestV), glowR);
    } else if (uType < 2.5) {
        // ---- radial bars, mirrored left/right so the lows sit at the top
        vec2 q = px - ctr;
        float R = min(W, H) * 0.5 - glowR;
        float r0 = uInner * R;
        float r = length(q);
        float ang = atan(q.x, -q.y);        // 0 at the top, +-PI at the bottom
        float a = abs(ang) / PI;            // 0..1, folded
        float slot = 1.0 / uBars;
        float i0 = floor(a / slot);
        float best = 1e9;
        float bestT = 0.0, bestV = 0.0;
        for (int k = -1; k <= 1; k++) {
            float i = i0 + float(k);
            if (i < 0.0 || i > uBars - 1.0) continue;
            float v = barAt(i);
            float len = max(2.0, v * (R - r0));
            float ac = (i + 0.5) * slot;
            float hw = slot * (1.0 - uGap) * 0.5;
            float da = (abs(a - ac) - hw) * PI * max(r, 1.0);   // arc px outside the bar's wedge
            float dr = max(r0 - r, r - (r0 + len));                // radial px outside the bar
            float d = length(max(vec2(da, dr), 0.0)) + min(max(da, dr), 0.0);
            if (d < best) { best = d; bestT = clamp((r - r0) / max(R - r0, 1.0), 0.0, 1.0); bestV = v; }
        }
        o = shade(best, colorAt(bestT, bestT, a, bestV), glowR);
    } else if (uType < 3.5) {
        // ---- oscilloscope line: exact-ish distance to the polyline near this pixel (for the glow),
        // slope-corrected vertical distance for the crisp core
        float amp = H * 0.5 - glowR - uThick;
        float u = px.x / W;
        float y = H * 0.5 - waveAt(u) * amp;
        float du = 1.0 / W;
        float slope = (waveAt(u + du) - waveAt(u - du)) * 0.5 * amp;
        float dv = abs(px.y - y);
        // slope correction only right at the line; further out it over-shrinks the distance
        float d = dv / mix(sqrt(1.0 + slope * slope), 1.0, smoothstep(uThick, uThick * 3.0 + 2.0, dv));
        for (int k = -12; k <= 12; k++) {
            float dx = float(k) * glowR * 0.3;
            float yk = H * 0.5 - waveAt(u + dx / W) * amp;
            d = min(d, length(vec2(dx, px.y - yk)));
        }
        o = shade(d - uThick * 0.5, colorAt(u, 1.0 - px.y / H, u, abs(waveAt(u))), glowR);
    } else if (uType < 4.5) {
        // ---- ring: radius breathes with the bass, edge traces a smoothed waveform around the circle
        vec2 q = px - ctr;
        float R = min(W, H) * 0.5 - glowR - uThick;
        float base = uInner * R + uLevel * (1.0 - uInner) * R * 0.6;
        float wamp = (R - base) * 0.25;
        float len = length(q);
        float u = abs(atan(q.x, -q.y)) / PI;                      // mirrored so the seam is invisible
        float e = 0.01;
        float w0 = (waveAt(u - e) + 2.0 * waveAt(u) + waveAt(u + e)) * 0.25;
        float wp = (waveAt(u) + 2.0 * waveAt(u + e) + waveAt(u + 2.0 * e)) * 0.25;
        float wm = (waveAt(u - 2.0 * e) + 2.0 * waveAt(u - e) + waveAt(u)) * 0.25;
        float rr = clamp(base + w0 * wamp, uThick, R);
        float drdth = (wp - wm) * wamp / (2.0 * e * PI);           // px per radian
        float slope = drdth / max(rr, 1.0);
        float dv = abs(len - rr);
        float d = dv / mix(sqrt(1.0 + slope * slope), 1.0, smoothstep(uThick, uThick * 3.0 + 2.0, dv)) - uThick * 0.5;
        o = shade(d, colorAt(clamp(uLevel, 0.0, 1.0), clamp(len / R, 0.0, 1.0), u, uLevel), glowR * (1.0 + uLevel));
    } else if (uType < 5.5) {
        // ---- mountain: a smooth filled spectrum with a bright edge
        float u = px.x / W;
        float top = glowR + uThick;
        float amp = (H - top) * uAmp;
        float v = smoothRow(0.0, u);
        float y = H - v * amp;
        float du = 1.0 / W;
        float slope = (smoothRow(0.0, u + du) - smoothRow(0.0, u - du)) * 0.5 * amp;
        float dv = abs(px.y - y);
        float d = dv / mix(sqrt(1.0 + slope * slope), 1.0, smoothstep(uThick, uThick * 3.0 + 2.0, dv)) - uThick * 0.5;
        for (int k = -8; k <= 8; k++) {   // true distance for the glow, from curve points nearby
            float dx = float(k) * glowR * 0.35;
            float yk = H - smoothRow(0.0, u + dx / W) * amp;
            d = min(d, length(vec2(dx, px.y - yk)) - uThick * 0.5);
        }
        float h = 1.0 - px.y / H;
        vec3 col = colorAt(h, h, u, v);
        o = shade(d, col, glowR);
        if (px.y > y) {
            float f = uFill * mix(1.0, 0.3, clamp((px.y - y) / max(H - y, 1.0), 0.0, 1.0));
            o = over(o, vec4(col * f, f));
        }
    } else if (uType < 6.5) {
        // ---- circle: a smooth radial spectrum (lows at the top), filled, with an inner ring
        vec2 q = px - ctr;
        float R = min(W, H) * 0.5 - glowR - uThick;
        float r0 = uInner * R;
        float len = length(q);
        float u = abs(atan(q.x, -q.y)) / PI;
        float v = smoothRow(0.0, u);
        float rr = r0 + v * (R - r0) * uAmp;
        float e = 0.004;
        float drdth = (smoothRow(0.0, u + e) - smoothRow(0.0, u - e)) * (R - r0) * uAmp / (2.0 * e * PI);
        float slope = drdth / max(rr, 1.0);
        float dv = abs(len - rr);
        float d = dv / mix(sqrt(1.0 + slope * slope), 1.0, smoothstep(uThick, uThick * 3.0 + 2.0, dv)) - uThick * 0.5;
        float h = clamp((len - r0) / max(R - r0, 1.0), 0.0, 1.0);
        vec3 col = colorAt(h, h, u, v);
        o = shade(d, col, glowR);
        o = over(o, shade(abs(len - r0) - uThick * 0.35, col * 0.8, glowR * 0.5));
        if (len > r0 && len < rr) { float f = uFill * mix(0.25, 0.9, h); o = over(o, vec4(col * f, f)); }
    } else if (uType < 7.5) {
        // ---- LED meter: columns of cells, unlit cells faint, a peak cell held above
        float slot = W / uBars;
        float i = clamp(floor(px.x / slot), 0.0, uBars - 1.0);
        float v = barAt(i);
        float segs = max(3.0, uSegs);
        float cellH = (H - 2.0) / segs;
        float j = floor((H - px.y) / cellH);                   // 0 = bottom cell
        vec2 cc = vec2((i + 0.5) * slot, H - (j + 0.5) * cellH);
        vec2 hs = vec2(slot * (1.0 - uGap) * 0.5, cellH * (1.0 - max(0.12, uGap * 0.6)) * 0.5);
        float d = sdBox(px, cc, hs, min(hs.x, hs.y) * uRound);
        float lit = step(j + 0.5, v * segs);
        float cap = uCaps > 0.5 ? step(abs(j - floor(capAt(i) * segs - 0.001)), 0.5) * step(0.02, capAt(i)) : 0.0;
        float h = (j + 0.5) / segs;
        vec3 col = colorAt(h, h, i / max(uBars - 1.0, 1.0), v);
        float core = clamp(0.5 - d, 0.0, 1.0);
        float on = max(lit, cap);
        float a = core * mix(0.1, 1.0, on);
        vec3 c = cap > 0.5 && lit < 0.5 ? mix(col, vec3(1.0), 0.35) : col;
        o = vec4(c * a, a);
        if (on > 0.5) o = over(o, shade(d, c, glowR * 0.5) * 0.6);
    } else if (uType < 8.5) {
        // ---- ridges (Unknown Pleasures): past spectra stacked, newest at the front (bottom),
        // each filled dark below its line so it hides the ones behind
        float K = clamp(uRidges, 4.0, 32.0);
        float u = px.x / W;
        float gap = (H - glowR * 2.0) / (K + 2.0);
        float amp = gap * 5.0 * uAmp;
        for (int kk = 31; kk >= 0; kk--) {
            float k = float(kk);
            if (k > K - 1.0) continue;
            float base = glowR + gap * (K - k + 1.0) + amp * 0.15;
            float v = smoothRow(3.0 + k, u);
            float env = smoothstep(0.0, 0.25, u) * smoothstep(1.0, 0.75, u);   // flat edges, peaks in the middle
            float y = base - v * amp * mix(0.35, 1.0, env);
            float d = abs(px.y - y) - uThick * 0.5;
            float h = clamp(1.0 - k / K, 0.0, 1.0);
            vec3 col = colorAt(h, h, u, v);
            vec4 line = shade(d, col, glowR * 0.35);
            vec4 r = line;
            if (px.y > y && px.y < base + gap * 0.5) r = over(line, vec4(0.0, 0.0, 0.0, uFill));
            o = over(r, o);
        }
    } else if (uType < 9.5) {
        // ---- waterfall: a scrolling spectrogram, now at the top
        float u = px.x / W;
        float age = clamp(px.y / H, 0.0, 1.0) * 31.0;
        float a0 = floor(age), t = age - a0;
        float v = mix(smoothRow(3.0 + a0, u), smoothRow(3.0 + min(a0 + 1.0, 31.0), u), t);
        v = pow(v, 1.4);
        vec3 col = colorAt(v, 1.0 - px.y / H, u, v);
        float a = clamp(v * 1.4, 0.0, 1.0) * mix(1.0, 0.35, age / 31.0 * (1.0 - uGlow));
        o = vec4(col * a, a);
    } else {
        // ---- shockwave: loud moments become rings travelling outward (radius = age), the centre pulses
        vec2 q = px - ctr;
        float R = min(W, H) * 0.5 - glowR;
        float r0 = uInner * R;
        float len = length(q);
        float age = (len - r0) / max(R - r0, 1.0);
        float v = age >= 0.0 && age <= 1.0 ? levelAgo(age) : 0.0;
        v = smoothstep(0.15, 1.0, v);
        float fade = 1.0 - smoothstep(0.6, 1.0, age);
        float ring = v * fade;
        vec3 col = colorAt(1.0 - age, 1.0 - age, clamp(age, 0.0, 1.0), v);
        float a = clamp(ring * mix(0.55, 1.0, uGlow), 0.0, 1.0);
        o = vec4(col * a, a);
        float disc = r0 * (0.85 + 0.25 * uLevel);
        o = over(shade(len - disc, colorAt(1.0, 1.0, 0.0, uLevel), glowR) * vec4(1.0, 1.0, 1.0, 1.0) * uFill, o);
    }
    gl_FragColor = o;
}
