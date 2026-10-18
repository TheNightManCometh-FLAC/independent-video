# Emulsion 3.14 validation

Built and tested from the 3.13 source on 2026-10-16. The APK was built with the apk-builder toolchain (aapt2, javac with -source 8 against API 30 stubs, dx, v2 signing) and signed with the preview key in `signing/` (CN=Emulsion Preview), so it updates 3.13 and 3.12 Test.

| Suite | Passed | Purpose |
| --- | ---: | --- |
| GradeGroupTest | 21 | 3.14 look groups: add on top, block moves, children kept inside, duplicate/delete/ungroup, repair, paste with fresh ids, save/reload, undo copies, format v17 |
| BuiltinLooks | 12 | 3.14 built-in look files generated and reloaded through Layer.fromJson |
| BeatCutTest | 11 | 3.14 transition table (19, old ids unchanged), picker groups, format v17, cut / transition centre on the nearest beat by roll |
| PresetTest | 6 | 3.14 preset names, speed curve round trip, relative fit (file I/O is checked on the phone) |
| ToolbarTest | 83 | 3.13 selection bar per item kind; distinct existing icons; every target section exists |
| WaveformTest | 10 | 3.13 footage waveform columns through speed, ramps, trims and B-roll mapping |
| MarkerTest | 21 | 3.13 markers: order, de-duplication, jumps, persistence, undo copies, snapping, repair, format v16; default transition |
| TransitionTest | 14 | 3.13 Apply to all cuts with lock protection; default transition on inserted/appended clips |
| GuideTest | 10 | 3.13 viewer guides: thirds, SMPTE 90/93 % safe areas, 9:16 social overlay |
| ExportInfoTest | 6 | 3.13 export bitrate shared with the exporter, size estimate |
| CatalogTest | 6 | 3.13 Recent / Favorites storage |
| BatchTest | 70 | Atomic group edits, copied media/routing/anchors/keys/ramps, evaluated audio-control parity, indirect ripple locks and lock persistence |
| RecoveryTest | 45 | Real filesystem snapshots and restoration; independent photo/mask assets; checksums; failed/staged copies; persistent Trash and collision handling; linked-directory safety |
| PrecisionTest | 30 | Exact source range validation, output duration, frame-boundary trim semantics and configurable magnet targets |
| NavigationTest | 41 | Task/scroll state, finite numeric input, source-stable B-roll speed, resilient/chronological caption I/O, menu search |
| WorkflowTest | 83 | B-roll cuts and timing, speed/source/audio continuity, independent IDs and undo copies, animation channels, automation phase/ranges/keys through splits and trims, save/open sanitation, fade continuity and actual export mix, time entry at 24/25/30/60 fps |
| AutomationTest | 59 | Source routing, features, silence gates, reactions, bypass, keyed amounts, ranges, audio modes, persistence, analysis caches and actual export mix |
| EditsTest | 18 | Main-track insert, overwrite, replace, speed mapping and connected titles |
| EncoderFinishTest | 17 | Existing one-time audio/video EOS regression, repeat drains, cancellation and genuine stall failures |
| ViewTest | 27 | Existing view/geometry/histogram regressions |
| MaskStackTest | 20 | Existing mask stack and persistence regressions |
| Total | **610** | **0 failed** |

Each suite compiled the app's actual Java sources. Test-only JSON/codec shims are not included in the APK. Logs are adjacent to this file.

Build succeeded: resource packaging; Java 8 source/target compilation using Java 17; D8 conversion; ZIP alignment; APK signing; signature verification; alignment verification. Manifest (3.12 build): preview package `com.christopher.strata.preview`, versionCode 41, versionName 3.12. 3.13 is versionCode 42; 3.14 is versionCode 43 (label "Emulsion 3.14 Test", no permissions). Signing key is unchanged from the original preview archive; APK signing certificates match the previous delivered preview. Photo format remains 4; video format 17 marks look groups and 3.14 transitions, 16 markers / default transition; video format 15 protects timing locks, while unlocked videos remain format 14.

Shader evidence (glcheck, Mesa surfaceless EGL): group_mix exact at strength 0 / 0.5 / 1; all 18 transitions at 25 / 50 / 75 % (`transitions-3.14.jpg`); the 12 built-in looks chained pass by pass through the real shaders on two photos (`looks-3.14.jpg`).

Scope of evidence: the automated checks exercise the actual pure editing/evaluation/persistence code and actual export mix/drain methods. They do not emulate Android widgets, GLES, device MediaPlayer/MediaCodec or a complete encoded audiovisual export. No device/emulator was available. The APK is a test build with those device checks outstanding, not a claim that every editor feature has been audited or verified.

3.13 additions: a glcheck contact sheet of all 20 blend modes through the shared `compositeOver` (`blendmodes-3.13.png`) was rendered on Mesa GLES2 and read; no shader changed. Video format 16 is written only with markers or a default transition. Logs: `*-3.13.log`.
