# Emulsion 3.10 — menu and workflow repair

Built on 3.9. This pass audits the photo and video navigation, shared menus and numeric controls, live key actions, B-roll speed and caption I/O. It does not claim every rendering tool has been device-tested.

## Menu organization

| Area | Organization |
| --- | --- |
| Video navigation | Edit, Effects, Titles, Visuals, Audio, Project |
| Video creation | One Add menu: Footage; Sound & graphics. Add stays available without a selection. |
| Selected video item | Frequent Split, Trim, Start here, End here. More groups navigation, arrangement, duplication and deletion. |
| Main clip inspector | Timing, Picture, Motion, Transition when a following clip exists |
| B-roll inspector | Timing, Picture, Motion |
| Effect inspector | Stack for adding/ordering; Settings for values/mix; Automation for audio links, curves and keys |
| Titles | Style, Timing, Motion |
| Visualizers | Style, Echoes, Picture, Timing, Motion |
| Audio | Selected footage's audible sound; separate-track volume, keys and timing; optional beat guide; ducking |
| Project | Canvas, Output, Duration |
| Photo layers | Stack and Properties; More layer actions for arrangement/grouping/copy/delete |
| Photo effects | Settings and Browse, rather than controls above a long catalog |
| Photo selection | Tools, Refine, Use selection |
| Photo mask | Tools, Stack when populated, Refine, Blend range |
| Photo retouch | Brush; Selected stroke when one exists |
| Shared adjustments | Collapsible Light, Color, White balance; Sharpening and Noise reduction |
| Shared sheets | Fixed title/header, bounded scrolling body, correctly completed tile rows |

Inspectors retain task and scroll position separately for each tab and selected item. Opening another menu no longer inherits an unrelated halfway-down scroll position. Selecting an effect opens Settings. Creating an audio-driven effect opens Automation. Empty sections are omitted.

## Redundancy removed

- Split/trim/duplicate/delete commands repeated in individual video inspectors now use the selection toolbar and More menu.
- Main/B-roll audible volume and sound mix now live in Audio. Motion's control-source choice remains separate because it decides what drives the picture, rather than what is heard.
- Video creation commands previously spread between the main inspector and project overflow are in Add.
- Export stays on the visible top-bar button; its overflow duplicate is removed. Photo Crop remains on its own tab rather than duplicated in overflow.
- Photo effect settings and the effect catalog have separate tasks. Layer grouping/deletion/duplication are consolidated into More layer actions.
- Removed the duplicate amount-key command hidden inside automation timing; the visible key toolbar owns key creation.

## Functional repairs

1. **B-roll speed changed the selected footage.** A 100ms cut sped up to 4x was forced to at least 200ms, which extended its source Out. Changes also repeatedly reconstructed source length from rounded playback duration. The speed operation now keeps exact source In/Out and derives playback duration from them.
2. **Exact-number dialogs silently failed or accepted non-finite numbers.** Bad input now keeps the dialog open with an error. NaN, infinity and overflow cannot enter slider state.
3. **Live key actions used stale panel timestamps.** Transform and volume key creation/removal/navigation now read the actual playhead when clicked. Pausing/scrubbing refreshes the audio inspector too. Explicit user key creation outside the selected span is rejected; intentional pre-roll keys left by edits remain supported.
4. **Clearing keys could jump to an old base value.** Clearing amount/volume keys now retains the current evaluated value.
5. **Malformed caption timestamps aborted imports.** Invalid/overflowing timestamp cues are skipped while subsequent valid cues remain usable.
6. **Caption export followed creation order and could double Windows line breaks.** Export sorts a copy chronologically, normalizes line endings and keeps very short cues valid at millisecond precision.
7. **Effect preview scope could be changed mid-preview.** Switching effect targets commits the current preview to its original target before changing scope. Adding an ordinary adjustment also commits an outstanding preview first.
8. **Menu grids and scrolling behaved inconsistently.** An unfinished tile row is padded before adding ordinary rows; long sheets have a bounded content area and a fixed header. Effect, audio-driven-effect and control-source sheets support multiword search by label/category/description, with a visible empty result.
9. **Hidden move/slip modes carried into another selection.** Selecting a different item clears these modes. Timeline Move has an explicit Done move action while enabled.

## Validation and limits

265 assertions passed: 41 new navigation/input/speed/caption/search cases and the existing 224 editing, automation, export, view and mask cases. The APK compiles, aligns and verifies with the original preview key.

No Android device/emulator is available. Native touch layout, keyboard/sheet behavior, playback under device load and complete hardware video export remain device checks. Automated tests cover the actual non-UI implementations and scripted codec/mixer boundaries; they do not render Android widgets or GLES.

## Device walkthrough

- Open an empty project. Confirm Add is visible; add footage, audio and titles through the grouped menu.
- Select main video and B-roll. Inspect Timing/Picture/Motion; cut and trim using the compact toolbar. Open More for positioning, duplication and deletion.
- On Audio, select different main/B-roll items and verify it stays on Audio and edits the intended sound. Verify control sources in Motion/Automation remain independent.
- Open Effects, search for an effect, select it and verify Settings opens. Toggle Settings/Automation/Stack and return to confirm independent scroll restoration. Switch scope during a preview and verify the existing effect remains on its original target.
- In the photo editor, check Layers Stack/Properties, Effects Settings/Browse, selection tasks and mask tasks. Expand/collapse shared Light/Color/White balance controls.
- Try an invalid exact numeric input; confirm it remains editable with an error. Check that the keyboard does not hide the menu's search header.
- During playback, add/remove/jump transform or volume keys; pause and verify the inspector reflects the current frame.
- Change speed on a short B-roll cut; verify its source In/Out do not change. Export and inspect the result.
