# Independent Video 1.0 (versionCode 2) — Play release

- **Ships as an Android App Bundle** (`tools/release/build-aab.sh play|kofi`): the APK toolchain's compiled resources are relinked with `aapt2 link --proto-format`, laid out as a bundle module (manifest/, dex/, res/, assets/, resources.pb), packed with bundletool 1.18.1 and signed with the upload key via jarsigner. `bundletool validate` passes, and the universal APK built from the bundle has the right id, version, label and target SDK 36.
- **Two variants, one code base.** `assets/brand/donate.txt` (the Ko-fi URL) turns on a small Ko-fi pill on the home screen and a Ko-fi row in the ⋮ menu. The `play` variant leaves the file out: Play's Payments policy limits in-app links to donation pages that aren't tax-exempt (AnkiDroid was told to remove one in Sept 2026).
- **Back arrows.** Detail sections get a "‹ Effects list / Layers / Effect list" row back to the list they came from (`InspectorSections.markList`). Any bottom sheet opened from another sheet's row shows "‹ <previous sheet>" and Back returns there (`Sheet.opening`/`parent`).
- **Contact.** cporter31415@gmail.com in ⋮ → Contact the developer and in the crash report. `tools/store/privacy-policy.html` is the hostable privacy page (with the Ko-fi link).
- **Copy.** The Video card no longer reads as music-only.
- **Home, final design.** One screen that never scrolls (`FitColumn`: drops the Recent strip, then the Files link, on screens too short for everything). True black (#000), one neutral card style (#0c0c0c, hairline #242424), orange (#ff8a1e) for everything you can press, red/blue only in the wordmark. The wordmark sits in a viewfinder frame (orange corner brackets, `Brand.Viewfinder`). Bottom bar: BAM mark left, Ko-fi right. System bars go black on home and back to the editors' grey in the editors.

# Independent Video 0.9 (formerly Emulsion) — pre-release for Google Play

- **New name and app id.** Shows as **Independent Video**; application id `com.betterappmafia.independentvideo`, versionName 0.9, versionCode 1. 1.0 is reserved for the Play release. The Java package stays `com.christopher.strata` (the manifest names the activity by its full class name; brand images load from `assets/brand`, so nothing depends on the generated R class).
- **New signing key.** Signed with the Independent Video upload key (RSA 4096, valid to 2056, SHA-256 9A:FC:A2:98…62:5D:EE). It is NOT in this source tree; keep the `.jks` and its password backed up. It installs as a new app next to Emulsion Test; projects don't move across (separate app storage).
- **Home screen.** Anaglyph wordmark (white core, red copy left, blue copy right, each with its own glow; converges on launch, then drifts slowly). Two large mode cards, Video and Photo, each with what it does. Canvas mode removed. Recent is one compact strip with **All projects**, which opens the full grid (filters, sort, multi-select, Trash). Better App Mafia mark on a launch splash (tap to skip) and at the bottom of home.
- **Video viewer.** Drag the bar under the picture to resize it (takes from the inspector, then the timeline; double-tap the bar to reset; the size is remembered). The corner eye button hides every toolbar so the picture takes their space (Show toolbars or Back restores). Turning the phone sideways shows the picture full screen; upright again returns to the editor. Leaving full screen while sideways turns the app upright, and rotation re-arms once the phone is held upright (YouTube's pattern). Follows the phone's auto-rotate setting. The rest of the app stays portrait.
- **Store assets.** `tools/store/icon.py` (512 px Play icon, the launcher art redrawn), `tools/store/feature.py` (1024 × 500 feature graphic), `tools/store/frame.py shots.txt outdir` (raw phone screenshots → 1080 × 1920 / 1920 × 1080 Play screenshots with a caption).
- **Tests.** New `BrandTest`, `ViewerSizeTest`; all suites pass (`bash tests/run.sh <Suite>`). Wordmark sources: `tools/brand/` (`python3 tools/brand/export.py <outdir>`).

# Emulsion 3.16.1 — crash fix

- **More on a main clip no longer crashes.** Its "Move later" row (and "Move later by one clip" in a multi-selection's Move sheet) asked for an icon named `next` that the icon set never defined, so opening the menu threw `IllegalArgumentException: no icon next`. The icon now exists (the Back chevron mirrored).
- **A missing icon can no longer crash the app.** `IconDrawable` draws nothing and writes a "missing icon" breadcrumb instead of throwing.
- **New test `IconRefsTest`** reads every source file and checks every icon name used in menus, tiles, chips, tabs, bar buttons and icon arrays (289 references). `ToolbarTest` only checked the bar's own buttons, which is how this slipped through. Run it with `bash tests/run.sh IconRefsTest`.
- Install: `Emulsion-3.16.1-preview.apk`, "Emulsion 3.16.1 Test", versionCode 46, same preview key, so it updates 3.16 Test. No project format change.

# Emulsion 3.16 — real speed control, an action toolbar, equalizers

- **Speed, fully adjustable.** Constant speed from 0.1× to 10× on a log slider (½× and 2× sit the same distance from 1×), quick chips, "Last exactly…" to fit a clip to a length, and Keep pitch / Tape (pitch follows speed). Curves start from a picture sheet (presets, your saved curves, or flat) and every point can be set exactly: speed, position, and the shape of the change to the next point (Smooth, Linear, Ease, Hold for hard speed cuts). Whole-curve tools: faster/slower, fit to length, mirror, save. B-roll gets the same 0.1–10× slider and pitch choice.
- **Toolbar does things.** The bar above the timeline no longer duplicates the tabs: Split, Delete, Duplicate, Freeze, Key ◆ (keys or unkeys everything at the playhead), Mute, Copy look / Paste look, Fade in to here / Fade out from here, visualizer Preset…, Trim…, More.
- **Equalizers.** A 6-band parametric EQ (low/high cut at 12 or 24 dB/oct, shelves, bells, notch) with a draggable curve and 12 presets on footage sound, B-roll sound, each audio track, and a master EQ on the whole mix. The export runs the exact filters; the preview uses the phone's 24-band equalizer.
- Projects that use these save as video format 19, so 3.15 refuses them instead of silently dropping the EQ or speed shapes.

# Emulsion 3.15 — shapes, text and brush masks everywhere; Visuals overhaul

Effects can now be applied through **shapes** (rectangles, ellipses, polygons, stars, 20 library shapes, freehand outlines), the **brush**, or **live text** in **81 bundled fonts**, in the photo editor and on every video effect and adjustment. On video, shapes and text **keyframe** and follow the **music** (Automation targets for size, position, rotation, reveal...). Every shape has Soft edge, Grow, Outline only and Reveal (write-on wipes). The **Visuals** section was rebuilt: 11 styles (Mountain, Circle, LED meter, Ridges, Waterfall and Shockwave are new), 14 presets, picture tiles, 3-colour palettes with five colour modes, frequency ranges, peak caps, mirroring and bass bounce.

See `docs/IMPLEMENTATION_3.15.md` for details, limits and the phone walkthrough. Install: `Emulsion-3.15-preview.apk`, "Emulsion 3.15 Test", versionCode 44, same preview key, so it updates 3.14 Test.

Build without the SDK: the android-apk-builder toolchain (aapt2, dx, API 30 stubs) builds this tree with the preview key converted to PEM. With the SDK, `build.sh` works as before. New suites: `VideoMaskTest`, `VisualsTest`, `MaskShapesTest <folder>` (then `tests/maskparity.py <folder> glcheck.py`); `tests/vizthumbs.py` re-renders the visualizer tiles.

# Emulsion 3.14 — presets and looks

Looks are now real presets. Applying one adds it as a **look group** in the grade stack with a **Strength** slider (0–100 %), On/Bypassed, Earlier/Later, Duplicate and Ungroup; its layers stay editable inside it. Choose **On top of the grade** or **Replace the grade** while previewing, and **Paste on top** / **Paste, replacing** for copied grades.

What's new:
- **12 built-in looks** (Teal & Orange, Bleach, Faded film, Night neon, Warm 16 mm, Mono contrast, VHS, Cold steel, Pastel, Crushed blacks, Sunset, Cross process).
- **Previews on the current frame** for every look and effect tile.
- **14 new transitions** (Wipe ×4, Push ×4, Zoom in/out, Spin, Whip, Flash, Luma fade, Iris) in a picker with previews.
- **Put a cut, or a transition's middle, on the nearest beat**.
- **Saved speed curves** that fit any clip.

See `docs/IMPLEMENTATION_3.14.md` for details, limits and the phone walkthrough.

# Emulsion 3.13 — reach and quick wins

The bar above the timeline is now the **selected item's menu**: Split · Speed · Volume · Effects · Animate · Trim… · More for a clip, with matching bars for B-roll, titles, visualizers and audio. Each button opens the setting where it lives. Trim, Times, Trim start and Trim end are merged into **Trim…**.

What's new:
- **All 20 blend modes** on effects, grade layers and visualizers.
- **Waveforms** on main clips and B-roll.
- **Markers**: tap along while playing; note, colour, jump, snap.
- **Transitions**: Apply to all cuts, and a default for new cuts.
- **Viewer**: full screen (double-tap the picture), guides (thirds, title/action safe, social safe zones) and **Save this frame** to Pictures, with "Open in photo editor".
- **Export dialog**: shows bitrate and an estimated file size.
- **Recent and Favorites** in the effect, audio-driven preset and look catalogs.

See `docs/IMPLEMENTATION_3.13.md` for details, limits and the phone walkthrough.

# Emulsion 3.12 — grouped timeline editing and timing locks

**Select** in the timeline toolbar starts multi-selection. Tap items to toggle them; Select all/Clear and Fit selection work across main footage, B-roll, titles, visualizers and audio. The inspector switches to group actions so individual-item controls cannot silently affect only one member of a group.

Move B-roll/graphics/audio together by dragging or by entering the selection's earliest start. Relative spacing, source ranges, keys and fades are retained. An adjacent block of main clips uses Move earlier/later on the magnetic timeline; selected graphics and audio follow the same offset. Duplicate after and Delete use atomic candidate edits, shared with single-item duplicate/delete/reorder. Copied audio-control links and connections remap to copied sources where applicable.

**Lock timing** protects placement, source range, speed, connections and deletion, including changes caused indirectly by ripple edits. Color, transforms, effect animation and audible levels remain editable. Locks appear in timeline labels, inspector status and More. Group commands validate before changing history; a central check protects other timing-edit paths too.

Per-item timing protection ships here. Named tracks, track headers, per-track mute/solo and linked/detached audio remain upcoming work. See `docs/IMPLEMENTATION_3.12.md` for usage, limits and phone checks.

## Retained from 3.11

First implementation stage from the editor audit. Main footage and B-roll now share a visible **Times** action for exact source In/Out, output duration and placement. Source range entry has labeled fields, frame nudges and a speed-aware duration preview. The preview can focus on the selected range and loop it. Handle taps no longer jump trim points. **Trim end** and source **Out** both keep the frame under the playhead, with an exclusive end boundary.

**Snap on/off** is visible beside transport controls; hold it or use the project menu to choose item-edge and beat targets. The dragged item's own edges are excluded. Frame alignment stays active with magnets off.

Deleted projects stay in **Trash** until explicitly removed. Restore works after restarting and handles folder collisions without overwriting another project. **Versions** is available from both editor project menus and project cards. It saves named local recovery snapshots with independent photo assets/masks and file-integrity checks. Restoring opens a separate project and preserves the current edit. Video/audio sources and installed fonts/LUTs remain linked; a version is not a portable media archive. Each version uses additional local storage.

See `docs/IMPLEMENTATION_3.11.md` for scope, remaining audit work and phone checks.

Continues the existing native Java/OpenGL photo and video editor from 3.9.

Inspectors are now grouped by task. Video navigation is **Edit / Effects / Titles / Visuals / Audio / Project**. Each selected item has a compact cutting toolbar; **Add** owns creation and **More** owns navigation, arrangement, duplication and deletion. Effects use **Stack / Settings / Automation**. Audible footage sound is edited on **Audio**. Inspectors remember their task and scroll position independently for each selected item.

Photo panels separate layer stack/properties, effect browsing/settings, selection tools/refinement/actions and mask tools/stack/refinement/blend range. Shared Light/Color/White balance and Detail controls have collapsible groups. Long menu sheets keep their title visible; video effect and audio-source catalogs support search.

Additional repairs cover exact-number validation, B-roll speed changing a short cut's source interval, stale playhead timestamps in key actions, key clearing reverting to an old value, malformed caption imports, chronological caption exports, preview target changes and tile menu layout. See `docs/WORKFLOW_AUDIT_3.10.md` for the exact menu map, defects and device walkthrough.

The 3.9 B-roll splitting, preview trim screen, exact In/Out, edit-stable automation and continuous split fades remain. The 3.8.1 export EOS fix is retained.

## Install

`Emulsion-3.14-preview.apk` installs as **Emulsion 3.14 Test**, package `com.christopher.strata.preview`, versionCode 43. Video format 17 is written only when a project has a look group or a 3.14 transition; format 16 only for markers or a default transition. The original supplied preview key is unchanged, so it updates previous Emulsion Test builds and keeps their private projects. The production app remains a separate package and requires its original production signing key.

Photo projects remain format 4. Unlocked video projects retain format 14; videos with timing locks use format 15 so older apps refuse to silently discard those locks. This release reads existing projects with unlocked defaults for missing lock fields. The included preview development key exists to continue producing compatible Test builds.

## Build

Without the Android SDK, the apk-builder toolchain (aapt2, dx, API 30 stubs) builds this tree too. Convert `signing/preview-development.jks` to PEM (keytool to PKCS12, then openssl) so the preview key is kept. With the SDK:


Requires Java 17 with compiler/jar modules, Python 3, Android SDK API 35+ and build tools with aapt, zipalign, d8.jar and apksigner.jar. No Gradle or external Java dependencies.

```bash
export ANDROID_JAR=/path/to/android-sdk/platforms/android-35/android.jar
export ANDROID_BUILD_TOOLS=/path/to/android-sdk/build-tools/35.0.0
bash build.sh preview
for suite in ToolbarTest WaveformTest MarkerTest TransitionTest GuideTest ExportInfoTest CatalogTest BatchTest RecoveryTest PrecisionTest NavigationTest WorkflowTest AutomationTest EditsTest EncoderFinishTest ViewTest MaskStackTest; do
  bash tests/run.sh "$suite"
done
```

For production, supply `KEYSTORE`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` for the original signing identity and run `bash build.sh production`.

**560 assertions passed**, plus resource packaging, compilation, DEX conversion, alignment and APK signature checks. No Android device/emulator was available. Native touch/keyboard, playback, lifecycle and complete hardware export verification remain phone checks; desktop tests do not render Android UI or GLES. See `docs/VALIDATION.md`.
