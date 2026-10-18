# Independent Video — source

The Android app: a video editor and a layered photo editor in one, written in plain Java against the Android framework. It uses **no AndroidX, no Kotlin, no Gradle and no third-party libraries**. Everything is the platform APIs plus code in `src/`.

## What's inside

| Area | How it works |
|---|---|
| Rendering | OpenGL ES 3, RGBA16F ping-pong compositing, GLSL shaders in `assets/shaders/`. One renderer drives preview, export tiles and the full-resolution detail view |
| Photo | Layers, blend modes, groups and clipping, editable mask stacks, selections, PatchMatch content-aware fill, guided-filter refine edge, streaming JPEG/PNG encoders for huge exports |
| Video | Multi-track timeline, MediaPlayer preview into SurfaceTextures, frame-accurate MediaCodec export through an EGL recordable surface, AAC mix, MediaMuxer |
| Audio | Streaming windowed-sinc resampler, WSOLA time-stretch, 6-band parametric EQ, ducking, beat tracking and band analysis for visualizers |
| UI | Hand-built views: an SVG-path icon set, bottom sheets, inspector sections, and the anaglyph wordmark on the home screen |

## Layout

```
AndroidManifest.xml   app id com.betterappmafia.independentvideo (Java package com.christopher.strata)
src/                  all app code
res/                  launcher icon and strings only
assets/               shaders, looks, transitions, bundled fonts (OFL, see assets/fonts/LICENSES.txt), brand images
tests/                desktop JUnit-free test suites (plain main methods) + Python render checks
tools/                brand/store image generators, release bundle script
docs/                 implementation notes and test logs by version
CHANGELOG.md          full version history (formerly Strata, then Emulsion)
```

## Build

With the Android SDK (API 35+ platform and build-tools with aapt, d8, zipalign, apksigner), Java 17 and Python 3:

```bash
export ANDROID_JAR=/path/to/android-sdk/platforms/android-35/android.jar
export ANDROID_BUILD_TOOLS=/path/to/android-sdk/build-tools/35.0.0
bash build.sh preview      # "Independent Video x.y Test", signed with your Android debug key
```

`preview` installs next to the Play version (`.preview` package). The `production` mode needs the official signing key, so it is only for Better App Mafia releases.

## Tests

The model, timeline, audio and layout logic is pure Java and runs on desktop:

```bash
ANDROID_JAR=/path/to/android.jar bash tests/run.sh BrandTest
ANDROID_JAR=/path/to/android.jar bash tests/run.sh IconRefsTest
# every suite: see the list in CHANGELOG.md; MaskStackTest and MaskShapesTest take an output folder
```

## Contributing

Bug reports and ideas are welcome as [issues](../../issues) or by email to cporter31415@gmail.com. Pull requests are welcome too. Keep to framework APIs only and Java without lambdas (the build targets old dex tooling). Run the test suites before you send one.

## License

GPL-3.0 (see `LICENSE` at the repository root). You may fork, change and share it. If you distribute a modified version, you must publish its source under the same license. The bundled fonts keep their own SIL Open Font License terms.
