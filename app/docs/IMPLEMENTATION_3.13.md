# Emulsion 3.13: reach and quick wins

This is the first release of the implementation plan (`emulsion-implementation-plan.md`, built from the 3.12 workflow-gap notes). It's mostly interface work. The only new project data is markers and a default transition.

## What changed

| Area | Change | Where |
|---|---|---|
| **Selection bar** | The bar above the timeline is now the selected item's own menu. Each button opens the inspector section where that setting lives.<br>• Main clip: Split · Speed · Volume · Effects · Animate · Trim… · More (a photo or freeze frame gets Duration and no Volume)<br>• B-roll: Split · Speed · Volume · Effects · Blend · Animate · Trim… · More<br>• Title: Edit text · Style · Animate · Split · Trim… · More<br>• Visualizer: Style · Echoes · Animate · Split · Trim… · More<br>• Audio: Split · Volume · Fades · Ducking · Trim… · More<br><br>Trim, Times, Trim start and Trim end are merged into **Trim…**, a sheet with trim-to-playhead, source preview and exact times. Select (several items) is shown when nothing is selected, and is in More otherwise. | VideoEditor `barActionsFor` / `barTarget` / `openSection` |
| **Blend modes** | Effects, every grade layer (Light & Color, Curves, Levels, HSL, LUT) and visualizers now offer all 20 modes, the same as B-roll already did. One "Blend: name" chip opens a sheet grouped as Photoshop groups them (Normal, Darken, Lighten, Contrast, Difference, Colour). | VideoEditor `blendChip` |
| **Waveforms** | Main clips and B-roll draw their sound under the thumbnails, mapped through their own speed, ramp and trims. Height follows the clip's volume; a flat line means muted (a ramped clip's own sound is muted). | `Waveform`, TimelineView `footageWave` |
| **Markers** | The marker button (pentagon icon) in the transport adds a marker at the playhead, also while playing, so you can tap along to a song. Markers show as coloured flags on the ruler with a faint line through the tracks.<br><br>Tap a flag for its note, colour (6) and delete. Hold the marker button, or use More → Markers…, for the list, previous / next and "delete all". Markers are a snapping target (Snapping sheet → Markers on/off). They are timeline positions, so ripple edits don't move them. | VProject `markers`, TimelineView, VideoEditor |
| **Transitions** | In a clip's Transition section:<br>• **Apply to all cuts**: one undo step. A cut that would alter or move a timing-locked clip is left as it was, and the message counts those.<br>• **Use for new cuts**: clips added from Add, or by insert / overwrite / append in the source viewer, get that transition on their new cuts. Existing transitions are kept. | Edits `transitionToAll`, `defaultTransition` |
| **Viewer** | • **Full screen**: the transport's ⤢ button, or double-tap the picture. Everything but the picture and transport hides; Back or ⤢ returns.<br>• **Guides** (More → View): Thirds; Title & action safe (SMPTE ST 2046-1: 90 % / 93 %); Social app UI on 9:16 (Reels working margins: 14 % top, 35 % bottom, 6 % sides, marked approximate).<br>• **Save this frame**: the frame at the output size as PNG in Pictures/Emulsion. The snackbar offers **Open in photo editor**, which closes the video project (saved) and starts a photo project from the frame. | ViewGuides, VideoEditor `saveFrame`, Preview `grab`, VComp `outputTo` |
| **Export dialog** | Shows the requested video bitrate (+ 256 kbps audio when there's sound) and an approximate file size, using the exporter's own formula. | Exporter `videoBitrate`, `estimateBytes` |
| **Recent and Favorites** | The effect catalog, the audio-driven presets and Looks have Favorites and Recent (last 8) sections at the top.<br>• Hold an entry to favourite it.<br>• The visible way is the **Add to Favorites** chip in the effect's settings, and the looks manager.<br>• Stored as an app preference, not in projects. | `Catalog`, `Sheet` |

## Format

Video projects use v16 only when they contain markers or a default transition. Otherwise they keep v14 (or v15 with timing locks), so 3.12 can still open them. 3.12 refuses a v16 file rather than dropping its markers. The sanitizer handles damaged markers: those before zero are dropped, colours are clamped, notes are capped at 200 characters, and the list is sorted. It also clamps the default transition.

## Limits

- **Save frame:** the video comes from the decoded frame at full size. Photos and titles come from the preview's sources (titles are rendered up to 1.5× their size, capped at 2048 px), so a very large title may be a little softer than in an export.
- **Social safe-area guide:** the platforms only publish ad guidance, so the guide is approximate. Check in the app.
- **Markers** don't follow ripple edits (as Resolve's timeline markers). Clip markers that move with footage aren't in this release.
- **Favorites** are per catalog and per phone. They aren't synced or saved with projects.

## Validation

**560 assertions pass**: 150 new, plus the 410 from earlier releases.

| Suite | Checks | What it covers |
|---|---|---|
| ToolbarTest | 83 | Every bar for every selection kind. Every button has its own existing icon. Every target section exists in the inspector source. |
| WaveformTest | 10 | Column mapping at 1×, 2×, ramped, B-roll; volume scaling; half-open spans. This suite caught a shared-boundary bug, which was fixed. |
| MarkerTest | 21 | Order, no duplicates, previous / next, save / reload, deep copy, snapping on / off, damaged-file repair, format v14 / v15 / v16, default transition. |
| TransitionTest | 14 | Apply to all, the length change, locked clips skipped, the result passes the central lock check, defaults on insert / append, existing transitions kept. |
| GuideTest | 10 | Thirds, 90 / 93 % safe areas, social overlay only on tall canvases, cycling. |
| ExportInfoTest | 6 | Bitrate formula shared with the exporter, cap, size estimate, wording. |
| CatalogTest | 6 | Recent order and limit, storage round trip, damaged storage. |

**Shader check:** a glcheck contact sheet of all 20 blend modes through the shared `compositeOver` was rendered and read. No shader was changed.

**Build:** the APK builds and is v2-signed with the preview key (CN=Emulsion Preview). No permissions.

**Not run here:** an Android device or emulator. Touch, layout, MediaPlayer, GLES and MediaStore behaviour need the phone.

## Phone walkthrough

1. Install over 3.12 Test. Open an old video project: no markers, plain cuts, and it saves without changing format (it still opens in 3.12).
2. **Selection bar:** tap a main clip, a photo clip, B-roll, a title, a visualizer and a song in turn. Check each bar, then tap each button: the right tab and section should open, scrolled to the top. Check Trim… for each, and More → Select several items.
3. **Blend:** on an effect, a Curves layer and a visualizer, open Blend and try Multiply, Soft Light and Difference. Compare the preview with an export.
4. **Waveforms:** a performance clip at 1×, 2× and with a ramp; a muted clip; B-roll with sound. Peaks should sit under the drum hits you can see and hear.
5. **Markers:**
   - Play a song and tap the marker button on beats; the flags should land where you tapped.
   - Tap a flag: note, colour, delete. Use Undo.
   - Hold the marker button: the list, previous and next.
   - Drag a clip edge near a marker: it should snap. Turn Markers off in Snapping and try again.
   - Save, reopen and check the markers are still there.
6. **Transitions:**
   - Set Dissolve 0.5 s and use Apply to all cuts. Check the length shortens and Undo restores it.
   - Lock a clip and repeat: the message names how many cuts were left.
   - Use for new cuts, then add footage and insert from the source viewer: the new cuts get the dissolve.
7. **Viewer:**
   - Double-tap the picture while paused, then while playing. Full screen should toggle without changing play state.
   - Leave full screen with Back.
   - Cycle the guides on 16:9 and on 9:16 projects.
   - Save this frame at 1080p and at 4K. Check the PNG in Gallery, then Open in photo editor: the video project should be saved and a photo project open.
   - Watch the preview for stutter right after a 4K save (the buffers are released and rebuilt).
8. **Export dialog:** switch Quality and Codec and watch the size estimate. After the export, compare the real file size with the estimate.
9. **Favorites:** hold an effect tile and reopen the sheet: it's in Favorites. Remove it with the chip in its settings. Pick three effects and check Recent. Do the same with audio-driven presets and Looks.
