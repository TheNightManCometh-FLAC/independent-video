// Light & Color: white balance, exposure, tone, presence.
uniform vec4 uB0;   // temperature, tint, exposure (EV), contrast      (-1..1 except EV)
uniform vec4 uB1;   // highlights, shadows, whites, blacks             (-1..1)
uniform vec4 uB2;   // vibrance, saturation, -, -                      (-1..1)

float rolloff(float v) {
    return v < 0.8 ? v : 0.8 + 0.2 * (1.0 - exp(-(v - 0.8) / 0.2));
}

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();

    // white balance + exposure in linear light
    vec3 l = toLin(below.rgb);
    float T = uB0.x, G = uB0.y;
    vec3 g = vec3(exp(0.35 * T), 1.0, exp(-0.35 * T)) * vec3(exp(0.12 * G), exp(-0.22 * G), exp(0.12 * G));
    g /= dot(g, LW);
    l *= g * exp2(uB0.z);
    vec3 x = toSrgb(l);

    // tone: smooth polynomial bumps on luminance, applied as a ratio to keep hue
    float L = dot(x, LW);
    float Lc = clamp(L, 0.0, 1.0);
    float iL = 1.0 - Lc;
    float nL = L
        + uB1.y * 0.6 * Lc * iL * iL          // shadows   (peak ~1/3)
        + uB1.x * 0.6 * Lc * Lc * iL          // highlights (peak ~2/3)
        + uB1.w * 0.12 * iL * iL * iL         // blacks    (moves the black point)
        + uB1.z * 0.12 * Lc * Lc * Lc;        // whites    (moves the white point)
    if (uB1.x < 0.0) nL = mix(nL, rolloff(nL), -uB1.x);   // highlight recovery pulls >1 back
    float ratio = nL / max(L, 1e-4);
    x = mix(x + (nL - L), x * ratio, smoothstep(0.02, 0.12, L));

    // contrast: endpoint-fixed S curve, slope at mid-grey 0.5..1.5, always monotonic
    vec3 v = clamp(x, 0.0, 1.0);
    x += 0.5 * uB0.w * (v - 0.5) * 4.0 * v * (1.0 - v);

    // presence
    float L2 = dot(x, LW);
    float s = satOf(clamp(x, 0.0, 1.0));
    float vib = uB2.x * (1.0 - s);
    x = L2 + (x - L2) * (1.0 + uB2.y) * (1.0 + vib);

    gl_FragColor = compositeAdj(below, x, amountAt(ip, below.rgb), uMode);
}
