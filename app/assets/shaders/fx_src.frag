// Copies a rect of a texture (uv space) into the viewport: crops the preview composite for
// effect thumbnails. The composite has mipmaps, so this is also a clean downscale.
uniform sampler2D uComp;
uniform vec4 uRect;

void main() { gl_FragColor = texture2D(uComp, uRect.xy + vUv * uRect.zw); }
