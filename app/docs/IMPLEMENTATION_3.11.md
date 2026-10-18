# Emulsion 3.11 — first implementation stage

Continues the existing 3.10 native Android project. This is the first working release from the 130-note editor audit, not a claim that the entire backlog is implemented.

## Implemented

| Workflow | Behavior | Where to find it |
| --- | --- | --- |
| Exact timing | Source In/Out, output duration and timeline placement have separate labels. Range validation accounts for playback speed. Invalid values keep the dialog open. | Select main footage or B-roll → Times |
| Frame precision | Source In/Out fields have individual one-project-frame nudges and a live resulting-duration readout. Out is exclusive. | Times → Source In / Out |
| Source trimming | Focus range expands a small cut on the scrub bar; Fit whole file restores the overview. Loop range auditions the marked section. A handle tap previews its edge without moving the mark. | Trim |
| Consistent end frame | Trim end and source Mark Out keep the frame under the playhead; both use the following exclusive boundary. | Toolbar Trim end / source Out |
| Duration validation | Exact duration rejects a request longer than the available source instead of silently clamping it and dismissing the dialog. | Times → Output duration |
| Visible magnets | Toggle snapping without leaving the timeline. Item edges and the enabled music beat guide can be selected independently. The selected item does not supply its own edge magnets. | Snap on/off; hold for options, or project More → Snapping |
| Recoverable Trash | Deleted projects and saved versions survive restart. A restored project cannot overwrite a live project or another trashed folder with the same original identity. | Home → Trash, or app More → Trash |
| Permanent removal | Individual projects require explicit permanent-delete confirmation. Failed moves/restores/removals report their actual outcomes. Damaged working files with saved versions remain discoverable. | Trash → project |
| Named versions | An explicit save must succeed before an open editor can create a snapshot. Each version copies current project data and local assets, excluding recursive history and temporary/backup saves. A hidden staging folder is published only after completion. | Editor More → Versions; project card More → Versions |
| Checked recovery | Each snapshot has file checksums. Missing or altered recorded assets refuse to restore. A restored version opens as a separate project; the previous working project remains available. | Versions → saved version → Restore as a new project |
| Version management | Name, list, rename and explicitly delete saved versions. Version creation, restore and asset deletion use background work with the editing surface blocked during the operation. | Versions |
| Consistent project copies | Duplication now uses collision-safe folder allocation and staged copying; it cannot publish an unfinished project or recursively duplicate its version bank. | Existing Duplicate action |
| Product wording | Neutral diagnostic sharing text replaces the stale assistant name. DNG import is described as device-decoded file support. Transport shows readable times rather than repeated output metadata. | Home / diagnostic menu / transport |

## Recovery scope

Versions are local recovery checkpoints. They contain the current document JSON, local photo sources, masks and other project-local files. Later mask/source cleanup in the working project cannot invalidate those copies. A project's version bank follows it into Trash and back. Versions consume additional disk space and remain until deleted; there is no automatic expiry in this release.

External video/audio URIs, imported fonts and LUTs still depend on their existing files or app installation. Those dependencies are not collected into a portable project package. No operating-system storage or microphone permission is added. Restore-as-copy is deliberate: it preserves both editing states and avoids replacing an open workspace's files. Named-version comparison and restore-in-place are future work.

Snapshot file checksums detect missing/changed files recorded in the snapshot. They do not make an already-incomplete source project complete, certify external media availability or validate every decoder. Interrupted hidden staging folders are not listed as valid versions; deletion of the containing project also removes them.

The existing project JSON formats remain photo v4 and video v14. Recovery metadata lives outside the document model, so this release introduces no document-format migration. The preview package/signing identity remains compatible with earlier Test builds. Production signing is still separate.

## Audit progress

| Audit note | Result in this release |
| --- | --- |
| E001 Project recovery | Persistent, visible Trash; no purge at startup; collision-safe restore. |
| E002 Named versions | Named asset-backed checkpoints and verified restore-as-copy. Comparison/restore-in-place remains outside this release. |
| E011 Source In/Out | Existing range editing becomes directly visible, with shared validation and field nudges. |
| E013 Trim feedback | Focus-range preview, loop and non-jumping handle taps. Adjacent-frame touch comparison still needs deeper work/device assessment. |
| E014 Trim semantics | End-frame consistency fixed. A complete explicit Ripple/Roll/Slip/Slide tool family is still pending. |
| E021 Snapping | Visible toggle, target choices and selected-item exclusion. |
| E129 Product language | Stale diagnostic text and misleading RAW-development implication removed from import wording. |

## Validation

340 assertions pass across nine suites. New cases cover actual filesystem snapshots, changed/deleted source assets, checksum failures, path escape rejection, interrupted unpublished saves, linked-directory safety, repeated restores, Trash identity collisions, precision range validation, frame-boundary semantics and snap targets. Existing edit, automation, encoder-finish, photo geometry and mask tests also pass.

The preview APK builds, aligns and verifies. See VALIDATION.md and adjacent logs for suite counts. Tests do not run Android widgets, MediaPlayer, GLES or hardware codecs. No phone/emulator was available, so native layout, source-loop seeking, lifecycle and complete audiovisual exports remain unverified here.

## Phone walkthrough

1. Install the update over Emulsion Test. Confirm existing photo/video projects remain available.
2. Select a main clip and B-roll. Open Times, edit In/Out numerically, nudge each endpoint and check the duration at 0.25×, 1× and 4×. Cancel must leave the range unchanged. Try reversed marks, overlong duration and a range too short at the selected speed; errors must remain editable.
3. Preview a very small marked range from a long file. Toggle Focus range/Fit whole file, tap and drag each handle, frame-step, mark and loop. Test short clips ending at the physical end of the file. Compare exact resulting boundaries after export.
4. Put the playhead on a recognizable frame. Trim end must include it. Split, trim, undo/redo, change speed, save/reopen and export main/B-roll; audio and effects must retain their intended timing.
5. Toggle Snap off. A small drag must remain frame-aligned without jumping to nearby edges/beats. Re-enable edge/beat targets separately and restart to check preference retention.
6. In a masked photo, save version Before removal. Remove the layer/source/mask, save and reopen; restore that version as a new project. The old source and mask must return, and the working edit must still exist separately.
7. Save a video version, edit the cut, restore the version and verify all linked footage/audio. Then revoke a source URI grant to check that the existing missing-media workflow still appears.
8. Delete a project containing versions, restart the app and restore it from Trash. Test Undo after deletion too. Permanent deletion must require confirmation and must remove only the chosen project/version.
9. Test background/resume during version creation and source looping, keyboard overlap, small-screen layout, TalkBack labels and storage exhaustion. File failures must not claim a successful version or replace a working project.

## Next stage

The remaining core work starts with persistent named tracks, multi-selection, track locks and linked audio, then general parameter/keyframe lanes and reliable audio-driver diagnostics. Those foundations precede full video masks/keying, a mixer/EQ/dynamics rack and advanced photo geometry/RAW/color management. The comprehensive editor audit remains the backlog; its original 3.10 status labels have not been rewritten as if these features were all finished.
