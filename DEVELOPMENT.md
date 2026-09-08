# Development

## Build

You need JDK 21, Android SDK platform 37 and NDK 27.2.12479018. The NDK version is set
in `Android/src/{smollm,stablediffusion,whisper}/build.gradle.kts`.

The build needs three git submodules. The native modules compile the submodule sources
through CMake, so a missing submodule fails at configure time.

| Path | Source |
|---|---|
| `Android/src/llama.cpp` | github.com/ggerganov/llama.cpp |
| `Android/src/stable-diffusion.cpp` | github.com/leejet/stable-diffusion.cpp |
| `Android/src/whisper.cpp` | github.com/ggerganov/whisper.cpp |

```bash
git submodule update --init --recursive
cd Android/src
./gradlew assembleRelease
```

The APK is written to `Android/src/app/build/outputs/apk/release/`. Add `-PuseCcache`
to cache the native builds between runs.

The build targets `arm64-v8a` only. The LiteRT and QNN libraries in `jniLibs` exist for
that ABI alone. AGP 9.0.1, Kotlin 2.2.21, `minSdk` 31, `compileSdk` and `targetSdk` 37.

Release builds read `keystore.properties` from `Android/src/`. The file is gitignored.

## Upstream

This repository is a fork of `google-ai-edge/gallery`, merged with `jegly/Box`. The
`upstream` remote tracks Google. Two workflows in `.github/workflows/` support syncing:
`upstream-drift.yaml` reports drift daily to an issue, and `upstream-merge.yaml` opens a
draft pull request for a merge when run by hand. Neither one changes `main`.

`build_android.yaml` is upstream's own workflow, kept identical to upstream. It does not
fetch submodules and cannot build this tree. `ci-build.yaml` is the fork's build.

## Model catalogue

The app fetches its list of models from Google's repository at run time, not from
`model_allowlists/` here. See `model_allowlists/README.md`.
