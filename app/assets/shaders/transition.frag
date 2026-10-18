// Transition between the outgoing (uA) and incoming (uB) clip renders. uP = progress 0..1.
// Types are stored in projects: only append. 1 dissolve, 2 dip to black, 3 dip to white,
// 4 wipe left-to-right with a soft edge. 3.14: 5 wipe right-to-left, 6 wipe top-down, 7 wipe
// bottom-up, 8-11 push left/right/up/down, 12 zoom in, 13 zoom out, 14 spin, 15 whip (push with
// motion blur), 16 flash, 17 luma fade (bright parts first), 18 iris (circle from the centre).
// vUv.y = 0 is the top of the frame (the app's buffer convention). uRes = viewport px.
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uP;
uniform float uType;
uniform vec2 uRes;

// samples a clip render at frame uv (0..1); outside the frame is transparent (the background shows)
vec4 smp(sampler2D s, vec2 uv) {
    vec4 c = texture2D(s, clamp(uv, 0.0, 1.0) * uBelowScale);
    float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
    return c * inside;
}

// scale about the frame centre (k > 1 magnifies)
vec2 zoomUv(vec2 uv, float k) { return 0.5 + (uv - 0.5) / k; }

// rotate about the centre in pixel space (so circles stay round), then scale
vec2 spinUv(vec2 uv, float ang, float k) {
    vec2 q = (uv - 0.5) * uRes;
    float c = cos(ang), s = sin(ang);
    q = vec2(c * q.x - s * q.y, s * q.x + c * q.y) / k;
    return q / uRes + 0.5;
}

float wipe(float x, float p) { return smoothstep(p * 1.04 - 0.04, p * 1.04, x); }   // 1 = still outgoing

void main() {
    vec2 uv = vUv;
    float p = clamp(uP, 0.0, 1.0);
    float e = smoothstep(0.0, 1.0, p);
    vec4 a = smp(uA, uv), b = smp(uB, uv);
    vec4 o = mix(a, b, p);
    if (uType < 1.5) o = mix(a, b, p);
    else if (uType < 3.5) {
        vec4 dip = uType < 2.5 ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(1.0);
        o = p < 0.5 ? mix(a, dip, smoothstep(0.0, 1.0, p * 2.0)) : mix(dip, b, smoothstep(0.0, 1.0, p * 2.0 - 1.0));
    }
    else if (uType < 4.5) o = mix(b, a, wipe(uv.x, p));
    else if (uType < 5.5) o = mix(b, a, wipe(1.0 - uv.x, p));
    else if (uType < 6.5) o = mix(b, a, wipe(uv.y, p));
    else if (uType < 7.5) o = mix(b, a, wipe(1.0 - uv.y, p));
    else if (uType < 11.5) {
        // push: both pictures travel together; the incoming one follows the outgoing one in
        vec2 d = uType < 8.5 ? vec2(1.0, 0.0) : uType < 9.5 ? vec2(-1.0, 0.0) : uType < 10.5 ? vec2(0.0, 1.0) : vec2(0.0, -1.0);
        vec2 ua = uv + d * e, ub = uv + d * (e - 1.0);
        vec4 pa = smp(uA, ua), pb = smp(uB, ub);
        float onA = step(0.0, ua.x) * step(ua.x, 1.0) * step(0.0, ua.y) * step(ua.y, 1.0);
        o = mix(pb, pa, onA);
    }
    else if (uType < 12.5) {
        // zoom in: the outgoing picture rushes in and fades; the incoming one settles from close up
        vec4 za = smp(uA, zoomUv(uv, 1.0 + 1.6 * e));
        vec4 zb = smp(uB, zoomUv(uv, 1.0 + 0.6 * (1.0 - e)));
        o = mix(za, zb, smoothstep(0.35, 0.65, p));
    }
    else if (uType < 13.5) {
        // zoom out: the outgoing picture shrinks away over the incoming one, which eases out to size
        vec4 za = smp(uA, zoomUv(uv, max(0.05, 1.0 - 0.8 * e)));
        vec4 zb = smp(uB, zoomUv(uv, 1.0 + 0.3 * (1.0 - e)));
        o = mix(zb, za, za.a * (1.0 - smoothstep(0.55, 1.0, p)));
    }
    else if (uType < 14.5) {
        // zoom just enough that the turned picture still covers the frame: cos + aspect * |sin|
        float asp = max(uRes.x / uRes.y, uRes.y / uRes.x);
        float ta = e * 1.5708, tb = (e - 1.0) * 1.5708;
        vec4 sa = smp(uA, spinUv(uv, ta, cos(ta) + asp * abs(sin(ta)) + 0.6 * e));
        vec4 sb = smp(uB, spinUv(uv, tb, cos(tb) + asp * abs(sin(tb)) + 0.6 * (1.0 - e)));
        o = mix(sa, sb, smoothstep(0.35, 0.65, p));
    }
    else if (uType < 15.5) {
        // whip: a fast push left, smeared along the motion
        float blur = sin(3.14159 * p) * 0.18;
        vec4 acc = vec4(0.0);
        for (int i = 0; i < 12; i++) {
            float o2 = (float(i) / 11.0 - 0.5) * blur;
            vec2 ua = uv + vec2(e + o2, 0.0), ub = uv + vec2(e - 1.0 + o2, 0.0);
            vec4 pa = smp(uA, ua), pb = smp(uB, ub);
            acc += mix(pb, pa, step(ua.x, 1.0));
        }
        o = acc / 12.0;
    }
    else if (uType < 16.5) {
        // flash: exposure blows out to white at the cut and comes back down on the new shot
        float f = 1.0 - abs(2.0 * p - 1.0);
        f = f * f;
        vec4 base = p < 0.5 ? a : b;
        o = vec4(min(base.rgb * (1.0 + 3.0 * f) + f * 0.75, 1.0), max(base.a, f));
    }
    else if (uType < 17.5) {
        // luma fade: the incoming picture's bright parts arrive first
        float l = dot(b.rgb, vec3(0.2126, 0.7152, 0.0722));
        float t = p * 1.3 - 0.15;
        o = mix(a, b, smoothstep(1.0 - l - 0.15, 1.0 - l + 0.15, t));
    }
    else {
        // iris: a circle opens from the centre
        vec2 q = (uv - 0.5) * uRes;
        float r = length(q) / (0.5 * length(uRes));
        o = mix(b, a, smoothstep(e * 1.02 - 0.02, e * 1.02, r));
    }
    gl_FragColor = o;
}
