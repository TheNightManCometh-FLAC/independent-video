# Emulsion 3.16 — implementation notes

Feedback addressed: "The speed section doesn't seem very thought out. A few presets instead of full
customizability. The tool bar is somewhat redundant. You hit effects up top and it just opens the
below effects panel. Audio section needs an equalizer."

## Speed
- `SpeedCurve`: per-stretch shapes `seg[]` (SMOOTH = monotone Fritsch–Carlson Hermite in log2 speed,
  LINEAR = straight in log speed, EASE = flat tangents at both ends, HOLD = constant, a hard step at the
  next point). Splits stay exact: halves carry explicit end slopes `st[]`. Range is now 0.1×–10×.
- File form: plain smooth curves keep the 3.14 `[x,v]` form, so ordinary projects don't change
  format; shaped or split curves use `[x,v,shape,s0,s1]`.
- UI (`VideoEditor.speedSection`): Constant / Curve mode; log slider (`Ui.Slider.Map`); quick chips;
  "Last exactly…"; pitch chips (`Clip.tape`); "Start from…" sheet with drawn curve thumbnails;
  point stepping, add at playhead / between, remove; per-point speed and position sliders; shape chips
  and "Use everywhere"; faster/slower, fit to length, mirror, save, manage.
- Tape pitch: preview via `PlaybackParams.setPitch(speed)`, export via `AudioDsp.varispeed`
  (cubic resample). Keep pitch uses the existing WSOLA `AudioDsp.stretch`, tested at 0.1× and 10×.
- Preview limit: MediaPlayer follows 0.25×–4×. Outside that the preview plays the picture but keeps the
  sound quiet (one toast) rather than chopping it with re-seeks; the export is exact.

## Toolbar
- `barActionsFor(selKind, clipKind, freeze, sound, muted)` returns direct actions only; `runBarAction`
  never calls `openSection`/`setTab` (ToolbarTest enforces it). New: `barKey` (key all / remove key at
  playhead), `barFade` (fade in to / out from the playhead), Mute toggle with live label, Copy / Paste
  look on the clip or B-roll grade. Icons added: mute, fade_in, fade_out.

## Equalizer
- `AudioEq`: RBJ biquads (bell, shelves, Butterworth cuts 12/24 dB/oct, notch), transposed DF-II,
  6 bands, 12 presets, JSON with repair. `EqView` draws the response and lets you drag band dots.
- Where: footage clip, B-roll media, each audio item, project master (`VProject.masterEq`).
- Export: per-segment `AudioEq.Chain` applied in float inside `mix()`; master chain before the limiter.
- Preview: `DynamicsProcessing` pre-EQ with 24 log-spaced bands sampled from the item + master response
  (needs MODIFY_AUDIO_SETTINGS). If a phone refuses it, the preview is unequalized and says so once.

## Format
- Video format 19 when any EQ, tape pitch, constant speed outside 0.25–4×, or a non-plain curve is used.

## Tests
All 25 suites pass. New/changed: SpeedCurveTest (27), AudioEqTest (40), ToolbarTest (107, rewritten).
Not verified on a phone: preview EQ accuracy per device, MediaPlayer pitch with tape on, touch feel of
the new speed panel and EqView.
