# Emulsion 3.12 — grouped timeline editing and timing protection

Continues the 3.11 native Android source. This implementation adds a selection and command foundation for larger video edits. Photo editing and the established rendering/DSP pipeline are retained.

## Using the workflow

1. Tap **Select** beside Add in the timeline toolbar. The current item enters the selection, if one is selected. Tap other timeline items to toggle them. Highlights show all selected items; individual trim/transform handles are hidden during group selection.
2. The inspector now describes the entire selection. Use Select all, Clear, Fit selection or Done. The count button opens selection and protection actions. Android Back also finishes selection.
3. For B-roll, titles, visualizers and imported audio, tap **Move**, then drag any selected item's body. The entire selection translates together. Use **Exact selection start** for a numeric position. The earliest item reaches zero as a group; spacing remains intact. Enabled magnets exclude every selected item's own edges.
4. For selections containing main footage, **Move** opens earlier/later commands. Selected main clips must form an adjacent block. Their internal order remains intact, and graphics/audio included in the selection follow the same offset. This respects the existing magnetic main-track model.
5. **Duplicate after** places copies after the selected time range, or inserts copied main clips immediately after the selected main block. Copies are selected and unlocked for further editing. The original selection keeps its locks. Main-clip duplication also requires adjacent selected main clips.
6. **Delete** removes the selected items together, with confirmation for multiple items. Main-track removal ripples surviving footage. Existing connected-item rules remain: surviving anchors follow their footage; items whose parent clip is removed are held/re-pinned by the existing model. A timing lock can block the whole operation.
7. Finish selection to edit individual properties. **More → Lock timing** protects the selected item. A visible inspector control unlocks it. In selection mode, lock/unlock all selected items together; the inspector shows how many are locked.

## What timing locks protect

| Protected | Still editable |
| --- | --- |
| Timeline start and duration | Color, grading and creative effects |
| Source In/Out and source replacement | Picture position, scale, rotation and opacity |
| Constant speed, speed curves and freeze-frame source | Transform and effect animation |
| Connected/fixed timing relationship | Sound level, gain keys, fades and mix choices |
| Deletion, splitting and timing changes from other clips' ripple edits | Selection, preview, export and creating an independent copy where it does not disturb a lock |

Main-clip transition settings are protected when that clip is locked. A change elsewhere is blocked if it moves a locked clip or connected item, even if that locked item is outside the selection. The notice identifies the item that requires unlocking. Undo/redo can intentionally restore earlier lock states.

Timing locks are per-item project data. Stable named tracks, track-header locks, track mute/solo and linked/detached audio have not been added in this release. Auto-packed display lanes remain the current layout model.

## Implementation and consistency

- `TimelineBatch` builds candidate projects for group move/reorder/duplicate/delete. Validation and lock checks happen before the editor adds an undo snapshot. A rejected group command leaves the source project intact.
- Single-item duplication, deletion and main-track reordering use those same commands, eliminating divergent implementations. Individual secondary items now duplicate after their existing range rather than silently overlapping in place.
- A group drag derives every update from its initial snapshot, preserving relative spacing. One drag produces one undo step. A canceled group drag restores its starting project and the prior redo stack.
- Motion keys, volume keys, source offsets, speed curves, fade envelopes and audio-control phase remain attached to their local/source timelines. Whole-item movement does not trim or reset them.
- Duplicates get new timeline/media identities and independent keys, curves and effect data. Explicit audio-control references within the duplicated selection point to the copied audio/footage. Project-wide beat-guide choices stay project-wide. Copied connections to copied main clips follow the copied parent.
- The editor also checks accepted project timing after ordinary changes, covering indirect ripple effects from legacy timing controls. Rejected ordinary edits restore the previous accepted project; a wholly rejected command removes its pending undo entry and restores redo.
- Selection is temporary UI state. It is pruned after deletions and history changes and is not a saved reusable group. Batch trim, attribute paste, cross-project clipboard and nested sequences remain future work.
- Existing three-point edits, source viewer, exact time entry, snapping preferences, versions, Trash and export pipeline remain available.

## Persistence and update compatibility

This APK is versionName **3.12**, versionCode **41**, package `com.christopher.strata.preview`, signed with the existing preview key. It updates earlier Emulsion Test builds without changing their private project location. No permission is added.

Photo format stays v4. Videos use v14 when unlocked and v15 when timing locks are present. The loader accepts v15; older project files default missing lock fields to false. The existing newer-version gate prevents older applications from silently rewriting a locked v15 project. Copy, undo snapshots, JSON mapping and named recovery snapshots retain lock flags.

## Validation

**410 assertions pass** across ten suites. The 70 new BatchTest cases exercise production commands, group boundaries and snapping, independent IDs and copied keys, anchor repair, mixed main/graphics/audio reordering, protected ripple edits, lock serialization and older defaults. Tests also compare actual evaluated audio-driven effect values before/after moving or copying a group, including post-volume control, and verify copied speed-ramp mapping.

The existing 340 recovery, precision, editing, automation, encoder-finish, geometry and mask checks also pass. Build, alignment and signature verification succeed; the signing certificate matches the previous preview. Logs and per-suite counts are in VALIDATION.md and adjacent files.

No Android phone/emulator was available. These tests execute Java model/command/evaluation/filesystem code and scripted codec boundaries. They do not drive Android widgets or verify GLES rendering, touch behavior, MediaPlayer seeking or complete hardware audiovisual exports.

## Phone walkthrough

1. Update over 3.11. Reopen an old photo and an old video. Verify neither starts with unexpected timing locks.
2. Enter Select, toggle main clips, B-roll, captions, visualizers and music; try All/Clear/Done and Back. Ensure all highlights/counts match and individual-item inspector controls stay hidden during group editing.
3. Select only secondary items. Move them by drag, negative and positive numeric offsets, and near zero. Verify spacing, source ranges, gain/transform keys and fades before and after save/reopen/export.
4. Test magnets on/off and beat/edge target choices. The moving selection must not snap to its own old edges. Pinch, vertical scrolling and cancellation must not leave a hidden move mode or partial canceled edit.
5. Select adjacent main clips with B-roll and music; move earlier/later, duplicate and undo/redo. Test transitions and ramps. A nonadjacent main selection must explain why a block operation is unavailable.
6. Duplicate an audio-reactive B-roll/music group. Verify the copies react to copied sources and that changing the copied sound/effect does not alter the originals. Check preview and exported output.
7. Lock a clip late in the timeline; trim, change speed, insert, duplicate or delete earlier main footage. Edits that move the lock must be blocked in full. An equal-duration reorder that leaves the lock unchanged may proceed.
8. Lock B-roll/title/audio items. Check source/timing edits and deletion are blocked, including connected-item movement from an unlocked parent clip. Confirm color, transforms and levels remain editable. Unlock via the visible inspector control.
9. Save a named version with locks, reopen and restore it as a separate project. Delete/restore the project through Trash. Lock flags and source connections should persist.
10. Confirm a grouped gesture is one undo action and that blocked commands preserve redo. Exercise large selections, small screens, TalkBack, background/resume and a complete audiovisual export on the phone.

## Remaining next-stage work

Persistent named tracks and track headers; track visibility/mute/solo; independent linked audio and J/L cuts; general parameter/keyframe lanes; video masks/keying; mixer/EQ/dynamics; photo geometry, multi-layer operations and managed RAW/color workflows. The 130-note competitive audit remains the broader backlog.
