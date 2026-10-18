# Workflow repairs in 3.9

| Failure | Repair |
| --- | --- |
| Split silently returned for B-roll | Dispatch by selection kind; B-roll gets an independent copy with continuous source ranges, keys and sound |
| Start/end editing required small off-screen timeline edges | Dedicated trim preview with draggable marks and exact time entry; one-touch playhead trims, edge jumps and Fit clip |
| Selecting an overlay hid the main clip action strip | Shared action strip for main clips, B-roll, titles, visualizers and audio |
| Dragging selected B-roll moved it while trying to scrub | Explicit Move toggle; default body drag scrubs |
| Short clip end handle selected its start | Nearest-edge hit testing |
| Seeking the ruler deselected the current item | Ruler seeks while retaining selection |
| Head trims at the timeline end could change the drag delta as the playhead clamped | Delta measured from finger displacement, independent of playhead movement |
| First amount key silently did nothing | Insert a key rather than capturing changes in an empty animation |
| Amount keys had no visible navigation | Result curve, key indicators, previous/next/add/delete controls |
| Trimming/splitting reset Step/Drift and moved ranges off their footage | Preserve source phase, shift keys and ranges, retain intentional negative starts through load sanitation |
| Splitting introduced new fades and sound gaps | Preserve the original fade envelope across both pieces and use it in preview and export |

The original export EOS fix, routing choices and audible mix modes are retained.

## Quick device check

1. Update the existing Emulsion Test installation. Open an existing project with main video, B-roll and an audio track.
2. Select B-roll and use Fit clip. Scrub its middle and Split. Verify the main track stays put and the source picture is continuous across the cut. Undo and redo.
3. Use Trim on the right B-roll half. Preview, move In/Out and tap the times for exact entry. Cancel once, then repeat and Apply. Verify the source interval and timeline interval shown in the inspector.
4. Use Start here and End here on both a main clip and B-roll. Verify main footage ripples and B-roll keeps the remaining picture in place.
5. Scrub while B-roll is selected. Enable Move and move its body, then disable Move and scrub again. Try exact Position.
6. Open Automate. Choose an existing effect setting and an audible source. Use Level, amount above zero, threshold zero. Add the first amount key, scrub ahead and change its amount. Check key navigation and the result curve.
7. Compare a Step or Drift effect before/after a cut or head trim, then save/reopen. Verify phase and amount-key timing remain continuous.
8. Cut inside a long B-roll fade. Export with B-roll sound enabled. Check picture/audio at that cut and confirm the full export finishes on the phone.

These interactive checks remain outstanding because this environment had no Android device or emulator.
