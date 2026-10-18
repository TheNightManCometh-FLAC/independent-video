# Emulsion 3.15: shapes, text and brush masks everywhere, and the Visuals overhaul

Effects can now be applied through shapes, a brush, or live text in a large font library, in both editors. On video, shapes and text keyframe and follow the music. The Visuals (music visualizer) section was rebuilt.

## What changed

| Area | Change | Where |
|---|---|---|
| **New mask shapes** | Alongside the brush and the Linear / Radial gradients:<br>• **Rectangle**, Rounded, Pill (Roundness)<br>• **Ellipse**<br>• **Polygon** (3–16 sides) and **Star** (points, point depth, roundness)<br>• **20 library shapes**: heart, lightning, arrow, chevron, cloud, speech bubble, drop, moon, cross, flower, ring, shield, leaf, flame, eye, wavy circle, splat, gear, burst, frame<br>• **Freehand**: draw any outline with one finger; it closes itself and becomes an editable shape.<br><br>All of them stack with the paint and the gradients (Add / Subtract / Overlap, Invert, Amount). A mask holds up to 8 shapes, 4 of which can be text or drawn shapes. | `MaskStack`, `MaskShapes`, `common.glsl maskAt` |
| **Live text masks** | A text mask stays text: you can change the words, font, weight (variable fonts), italic, alignment, letter spacing and line spacing at any time. Its box hugs the actual letters, and it stays sharp at full export size. | `TextRaster`, `MaskRaster` |
| **Shared shape settings** | Every shape (except the gradients) has the same set of controls:<br>• **Soft edge**: centred on the outline, with fine control at small values.<br>• **Grow / shrink**.<br>• **Outline only**: makes outlined text and rings.<br>• **Rotation**.<br>• **Reveal**: a wipe left / right / top / bottom / from the centre, for write-on titles. | `MaskPanel` |
| **Handles** | Drag inside a shape to move it. The corner dot scales it evenly; the side and bottom dots stretch it. The blue knob turns it and snaps every 15°. Shared by the photo canvas and the video viewer. | `MaskGizmo` |
| **Font library** | 81 Google Fonts are bundled (OFL / Apache, licences in `assets/fonts/LICENSES.txt`), grouped as Impact, Sans, Serif, Script, Retro, Mono and Grunge. The new font browser draws each row in its own font using your text, and has search, favourites (☆) and Recent. It also lists the phone's fonts and imported files. Titles and text layers use the same library. | `Fonts`, `FontPicker` |
| **Photo editor** | The mask panel tools are now Brush · Linear · Radial · **Shape… · Text… · Draw** · Pan. The "New shapes and gradients" chips (New mask / Add / Subtract / Overlap) apply to every new shape. Effect panels gained **Shape** and **Text** next to Paint it in / Linear / Radial. | `Editor`, `CanvasView`, `AdjPanels` |
| **Video effect masks** | Any effect or adjustment (on a clip, a B-roll, all footage or everything) has a new **Mask** section:<br>• Brush (reveal / hide, size, hardness, flow), shapes, text, freehand, Linear, Radial.<br>• Editing happens on the viewer, with a badge showing the current tool. Two fingers scale and turn the selected shape.<br>• The painted part is stored as strokes in the project, not as an image file.<br><br>Masks cover the output frame; a B-roll grade's mask covers the B-roll and moves with it. | `VideoEditor` mask section, `VideoMasks`, `MaskPaint` |
| **Keyframes on shapes** | **Key here** records the shape at the playhead. After the first key, any change you make (drag or slider) keys that time. You can step to the previous or next key, remove a key, set the ease per segment (Linear, Smooth, Hold, Ease in/out, Overshoot), or Stop animating. Keys are relative to the clip, so they travel with it. | `MaskStack.Comp.keys`, `vmKeysRow` |
| **Music automation** | Every shape appears as an Automation target: "Star 1 · Size", Move across, Move down, Rotation, Amount, Soft edge, Grow, Outline, Reveal. Links follow their shape when the stack is reordered, and are removed if the shape is deleted. | `Music.targets` (T_MASK) |
| **Visuals overhaul** | • **11 styles**: Bars, Mirror, Radial, Wave, Ring, plus **Mountain**, **Circle** (the NCS / Trap Nation look), **LED meter**, **Ridges** (Unknown Pleasures), **Waterfall** (spectrogram) and **Shockwave** (kick-fired rings).<br>• **14 presets**.<br>• Style and preset pickers show picture tiles rendered from the real shader.<br>• **Color**: 14 palettes (2 or 3 colours), colour by Classic / Height / Across / Rainbow (animated) / Loudness.<br>• **Response**: frequency range (Full, Bass, Low end, Vocals, Highs, or exact bands), smoothing across bars, bounce with the bass.<br>• **Shape**: roundness, peak caps, lows in the middle, from the top, fill, peak height, LED cells, ridge count, seconds of history.<br><br>The panel is regrouped into Style / Color / Response / Shape / Echoes / Picture / Motion / Timing. | `viz.frag`, `Visuals`, `VComp.drawViz`, VideoEditor `vizPanel` |

## Format

- **Photo projects** are written as **v5** only if they use a shape beyond the two gradients, or more than 6 shapes.
- **Video projects** are written as **v18** only if an effect has a mask or a visualizer uses a 3.15 style or option.
- Otherwise files keep their old version, so 3.14 can open them. 3.14 refuses a v5 or v18 file rather than silently dropping masks.

## How text and drawn shapes render

These shapes don't have a formula, so each is drawn once at 2× and turned into a **signed-distance texture**. That's done with one 8-point nearest-edge propagation, averaged down 2×2, so the edge is accurate to about half a texel. The texture is about 300k texels for the shape's box, plus a margin for feather, grow and outline.

The shader scales, rotates, feathers, grows and outlines from that texture. Moving, sizing, rotating or animating a shape never redraws it; only changing the words, font or spacing does. A new texture builds in the background in the preview, which keeps showing the old one meanwhile. Export waits for exact textures. On this workspace's CPU a texture takes about 150 ms.

## Limits

- Up to **4 text or drawn shapes** per mask (one GPU texture unit each). Rectangles, polygons, stars, ellipses and gradients don't count toward that. Up to 8 shapes in all.
- Video masks don't track motion. Animate shapes with keyframes instead. The **painted** part of a video mask doesn't animate.
- **Italic** on the bundled fonts is a slant (the library ships upright files). Bold on a static font is synthetic.
- Fonts are subset to Latin characters, except those with a Reserved Font Name, which ship unmodified.
- Very sharp corners of text soften slightly when a text mask is blown up far beyond the screen (the distance texture is ~1000 px across).
- Visualizer tiles are sample pictures from a synthetic song frame, not your music.

## Validation

**705 assertions pass** across 23 suites: 107 new, plus everything from earlier releases.

| Suite | Checks | What it covers |
|---|---|---|
| MaskShapesTest | 50 | Rectangle, roundness, rotation, soft edge, grow, outline and reveal at exact pixels. Exact hexagon distance. Every library shape builds and covers a sane area. Freehand outline accuracy (< 0.5 px). JSON of every new field and keys. Keyframe evaluation. Texture slot budget. |
| VideoMaskTest | 21 | Stroke model and JSON, repair of damaged strokes, copies owning their data, keys over a clip, music driving Size / Move / Reveal (clamped), format 18, the ellipse within 0.05 px. |
| VisualsTest | 36 | Data rows: bars from a frequency range, peak caps held then falling, history rows, level history for Shockwave, smoothing. Each preset is distinct and recognised. JSON, repair, format 18. |
| MaskStackTest | 20 | 3.6–3.14 masks unchanged. |

**Shader checks (glcheck, Mesa):**
- `maskAt` vs the Java version, 8 scenes (`MaskParity-3.15.log`). Analytic shapes match within 1/255. Texture shapes differ only on a few edge pixels, by a few percent (8-bit texture filtering).
- Every shader compiles with the new header.
- Every visualizer style rendered (`visualizers-3.15.png`). Two glow-streak artefacts (Mountain, Circle) were fixed from those sheets.
- Effects masked by text, a star, an ellipse and a hexagon on a real photo (`masks-3.15.jpg`). These use the app's own distance code; PIL draws the text in place of Android's canvas. The soft edge was made quadratic after this sheet showed text vanishing at moderate softness.

**Build:** v2-signed with the preview key (CN=Emulsion Preview), label "Emulsion 3.15 Test", versionCode 44, no permissions. The APK is 5.7 MB (it was 0.7 MB; the fonts account for the difference).

**Not run here:** an Android device. Touch, the phone's GPU, text drawing by Android's canvas and texture-build speed need the phone.

## Phone walkthrough

1. Install over 3.14 Test and open an old photo and an old video project. Both should save without changing format.
2. **Photo:** add an Effect (e.g. Invert), tap **Text**, type a word. The effect should show only inside the letters. Open Font: browse Grunge, then try Weight on Inter. Drag the corner, side and rotate handles. Try Soft edge, Outline only and Reveal.
3. **Shape…:** add a Heart (the tile shows it), then Subtract a Star. Try **Draw** and trace an object.
4. **Video:** select a clip, add an Effect, and open its **Mask** section. Use **Shape…** → Circle, drag it on the viewer, and pinch it with two fingers.
5. **Keyframes:** on that shape, tap **Key here**, move the playhead 2 s, drag the shape elsewhere and play. It should travel. Set the ease to Overshoot.
6. **Music:** in Automation, add a link to "… · Size" with Bass / Follow. The shape should pump with the kick.
7. **Brush on video:** select Brush and paint, then Hide-paint part away. Undo and redo. Close and reopen the project; the paint should come back.
8. **Text on video:** add Text… "DROP" in Bebas Neue, then animate Reveal from 0 % to 100 % over a second.
9. **Visuals:** Add a visualizer… → tiles. Try **Trap circle**, **Unknown Pleasures**, **Hi-fi meter**, **Heat waterfall** and **Bass shockwave**. Try Rainbow colour, the Bass range and Bounce. Export 10 s and compare with the preview.
