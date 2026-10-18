// Levels: per-channel (index 1..3 = R,G,B) then master (index 0).
uniform vec4 uLv[4];   // input black, input white, gamma, -
uniform vec2 uLo[4];   // output black, output white

float lev(float v, vec4 p, vec2 o) {
    float t = clamp((v - p.x) / max(p.y - p.x, 1e-4), 0.0, 1.0);
    t = pow(t, 1.0 / max(p.z, 0.01));
    return mix(o.x, o.y, t);
}

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec3 c = below.rgb;
    c = vec3(lev(c.r, uLv[1], uLo[1]), lev(c.g, uLv[2], uLo[2]), lev(c.b, uLv[3], uLo[3]));
    c = vec3(lev(c.r, uLv[0], uLo[0]), lev(c.g, uLv[0], uLo[0]), lev(c.b, uLv[0], uLo[0]));
    gl_FragColor = compositeAdj(below, c, amountAt(ip, below.rgb), uMode);
}
