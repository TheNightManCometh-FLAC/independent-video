// Detail layer, pass 2: luminance unsharp mask on the pass-1 result, with threshold (ignore
// fine noise), edge masking (sharpen only edges) and a halo clamp (never overshoot the local
// min/max by more than a hair). Then mixed over the original below by opacity/mask/range.
uniform sampler2D uMid;
uniform vec4 uDet0;   // amount (0..3), radius img px, threshold, masking
uniform vec4 uDet1;

vec3 mid(vec2 o) { return texture2D(uMid, vUv * uBelowScale + o * uTexel).rgb; }

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec3 c = mid(vec2(0.0));
    float amt = uDet0.x;
    float r = uDet0.y * uPxPerDoc;          // radius in viewport px; < 0.2 px is invisible here
    vec3 outc = c;
    if (amt > 0.001 && r > 0.2) {
        float Lc = dot(c, LW);
        float wA = 0.6065, wB = 0.3679;     // gaussian (sigma = r) at distance r and r*sqrt(2)
        float s = 0.0, mn = Lc, mx = Lc, gx = 0.0, gy = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                float L = dot(mid(vec2(float(i), float(j)) * r), LW);
                float w = (i == 0 && j == 0) ? 1.0 : ((i == 0 || j == 0) ? wA : wB);
                s += w * L;
                mn = min(mn, L);
                mx = max(mx, L);
                gx += float(i) * L;
                gy += float(j) * L;
            }
        }
        float d = Lc - s / (1.0 + 4.0 * wA + 4.0 * wB);
        if (uDet0.z > 0.001) {
            float t = uDet0.z * 0.03;
            d *= smoothstep(t * 0.5, t * 1.5, abs(d));
        }
        if (uDet0.w > 0.001) {
            float edge = length(vec2(gx, gy)) / 3.0;
            float m = uDet0.w * 0.12;
            d *= smoothstep(m, m + 0.04, edge);
        }
        float Ln = clamp(Lc + amt * d, mn - 0.03, mx + 0.03);
        outc = c + (Ln - Lc);
    }
    gl_FragColor = compositeAdj(below, outc, amountAt(ip, below.rgb), uMode);
}
