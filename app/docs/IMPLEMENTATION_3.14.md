# Emulsion 3.14: presets and looks

This is the second release of the implementation plan. It turns looks from "paste some layers" into real presets: built-in looks, a Strength control, previews on every tile, more transitions, and saved speed curves.

## What changed

| Area | Change | Where |
|---|---|---|
| **Looks as groups** | Applying a look adds its layers as one **look group** in the grade stack, shown as "★ Look · name · N%" with its layers indented under it. Selecting the group gives:<br>• **Strength** (0–100 %): how much of the look shows, mixed against the picture as it was before the look.<br>• **On / Bypassed**.<br>• **Earlier / Later**: moves the whole look past its neighbours.<br>• **Duplicate** and **Ungroup** (the layers stay, at full strength).<br>• ✕ deletes the look with its layers.<br><br>The layers inside stay individually editable; selecting one shows which look it belongs to. | `GradeGroups`, VComp `openGroup` / `groupMix`, `group_mix.frag` |
| **On top or replace** | While a look is previewing, the bar offers **On top of the grade** (the default: it's added above what's there) and **Replace the grade**. Copy grade → **Paste on top** / **Paste, replacing** works the same way. | VideoEditor `relook`, `pasteGrade` |
| **Built-in looks** | 12 looks ship with the app: Teal & Orange, Bleach, Faded film, Night neon, Warm 16 mm, Mono contrast, VHS, Cold steel, Pastel, Crushed blacks, Sunset, Cross process. They are made from the app's own layers (Light & Color, Curves, HSL, grain), so they can be opened up and changed. Tuned on portraits, food and product shots (see `looks-3.14.jpg`). | `assets/looks`, `tests/BuiltinLooks.java` |
| **Look picker** | Order is Favorites → Recent → Built in → Your looks. All are tiles showing **the current frame with that look applied**. Hold a tile to favourite it. | `LookUi`, VideoEditor `thumbInto`, Preview `thumb` |
| **Effect previews** | Effect tiles in the catalog also show the current frame with that effect. Renders are 160 px on the long side and cached. | VideoEditor `fxThumbBase` |
| **Transitions** | 14 new types, for 19 in all: Wipe ←↓↑ (the old Wipe is now "Wipe →"), Push ←→↑↓, Zoom in, Zoom out, Spin, Whip, Flash, Luma fade, Iris. A clip's Transition section keeps **Cut** and **Dissolve** one tap away; **More transitions…** opens a grouped picker (Basic, Wipe, Push, Motion, Light) with a preview of each. | `transition.frag`, VideoEditor `transitionSheet` |
| **Cut on the beat** | With beat sync on, a cut gets **Put this cut on the nearest beat**; with a transition, **Center on the nearest beat** puts the transition's middle on it. It's a roll edit: total length stays the same, and media limits apply. If no beat is in reach it says so instead of half-moving. | VideoEditor `centerOnBeat` |
| **Saved speed curves** | A clip with a speed curve gets **Save curve…**. Saved curves appear as ★ chips in every clip's Speed section, and **Manage…** renames or deletes them. Points are relative to the clip, so a curve fits any length. | `Presets` (kind `speed`) |

## Format

Video projects are written as **v17** only when they contain a look group or one of the new transitions. Everything else keeps its old format, so 3.13 can still open it. 3.13 refuses a v17 file rather than flattening its looks or turning a Spin into a wipe. On load, groups are normalized: an empty or broken group is removed, and layers pointing at a missing group are released.

Speed curves and favourites are app-wide (in app storage), not inside projects.

## Limits

- **Copy grade** keeps look groups only from video clip to video clip. Copying between the photo and video editors carries the layers without their group.
- **Grain and Detail:** Detail layers are photo-only and are skipped on video (the message says so).
- **Save look** from a stack that contains groups saves the layers flattened (each at full strength). Saving a look that contains another look is for a later release.
- **Previews** render on the frame under the playhead, so if it's black or a title card, the tiles are too. Move the playhead to a representative frame first.
- **Transition previews** are fixed sample images (rendered by the real shader at build time), not your footage.
- **Whip** pushes left only. **Luma fade** uses the brightness of the incoming clip.
- **Built-in looks** can't be renamed, deleted or hidden. To change one, apply it, edit its layers, and Save look as your own.
- **Layers inside a look** move only within it; Ungroup first to move one out.

## Validation

**610 assertions pass**: 50 new, plus the 560 from earlier releases.

| Suite | Checks | What it covers |
|---|---|---|
| GradeGroupTest | 21 | Add a look as a group, block moves past neighbours and groups, remove, duplicate with fresh ids, ungroup, normalize damaged stacks, format v17. |
| BuiltinLooks | 12 | Generates the 12 look files and reloads each through the app's own `Layer.fromJson` (the files were also checked as valid JSON). |
| BeatCutTest | 11 | 19 transition types with the old ids unchanged, picker groups cover every type once, format bumps, cut and dissolve land on the beat, length kept, no beats, out of reach. |
| PresetTest | 6 | Name cleaning, a saved curve reloading identically, and one curve fitting clips of different lengths. The file save / list / rename / delete code isn't covered by the desktop shim (it can't parse JSON text), so it's in the phone walkthrough. |

**Shader checks (glcheck, Mesa):**
- `group_mix` at strength 0, 0.5 and 1: exact.
- All 18 transitions at 25 %, 50 % and 75 % (`transitions-3.14.jpg`). Spin first showed empty corner wedges; its zoom now follows the aspect ratio so the turned picture always covers the frame.
- Every built-in look chained through the real shaders, pass by pass, on two photos (`looks-3.14.jpg`). Teal & Orange, Crushed blacks, Sunset and the grain amount were retuned from these sheets.

**Build:** v2-signed with the preview key (CN=Emulsion Preview), label "Emulsion 3.14 Test", versionCode 43, no permissions.

**Not run here:** an Android device. Touch, GLES on the phone's GPU, thumbnail speed and MediaCodec need the phone.

## Phone walkthrough

1. Install over 3.13 Test. Open an old project; it should save without changing format.
2. **Look picker:** park the playhead on a typical frame, open Grade → Looks. Tiles should show that frame. Scroll Built in; hold one to favourite it and reopen.
3. **Apply a look:** tap Teal & Orange. Try **Replace the grade**, then **On top of the grade**, then Apply. The stack should show the look row with its layers indented.
4. **Strength:** select the look row, drag Strength from 100 % to 0 %; at 0 % the picture should match the clip without the look. Try Bypassed, Earlier / Later past another layer, Duplicate, Ungroup, and ✕ with Undo.
5. **Edit inside:** tap a layer under the look and change it; its hint should name the look.
6. **Export:** export 5 seconds with a look at 50 % and compare with the preview.
7. **Copy grade:** copy from that clip, Paste on top on another clip, then Paste, replacing. Paste to all clips.
8. **Effect previews:** open the effect catalog; tiles should fill in with your frame. Watch for lag on a 4K clip.
9. **Transitions:** on a cut, tap More transitions…, try Push →, Spin, Whip and Iris. Scrub through each. Export one.
10. **Beat:** with a song synced, use "Put this cut on the nearest beat", then on a dissolve "Center on the nearest beat". The total length shouldn't change; Undo restores it.
11. **Speed curves:** make a ramp, Save curve…, apply it to a clip of a different length, rename and delete it in Manage….
12. **Back-compat:** save a project with a look group and try opening it in 3.13 if still installed: it should refuse, not damage it.
