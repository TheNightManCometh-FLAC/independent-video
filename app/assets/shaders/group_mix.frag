// Closes a pass-through layer group. uBelow holds the image as it was before the group's first
// layer; uTop is the image after all of them. The group's opacity, mask (and Blend If) decide how
// much of the group's work shows: 0 = as if the group were hidden, 1 = fully applied.
uniform sampler2D uTop;

void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    vec4 top = texture2D(uTop, vUv * uBelowScale);
    gl_FragColor = mix(below, top, clamp(amountAt(ip, below.rgb), 0.0, 1.0));
}
