// The area a layer actually affects, for the "Affected" overlay: painted mask x Blend If range
// (tested on the image below the layer, as the layer itself does) x clipping. Opacity is left out:
// it scales the whole layer, it doesn't decide where.
void main() {
    vec2 ip = imgPx();
    vec4 below = belowAt();
    float c = clamp(maskAt(ip) * rangeAt(below.rgb) * clipAt(), 0.0, 1.0);
    gl_FragColor = vec4(c, c, c, 1.0);
}
