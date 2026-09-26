# Development

## Build

You need JDK 21, Android SDK platform 37 and NDK 27.2.12479018. The NDK version is set
in `Android/src/{app,smollm,stablediffusion,whisper}/build.gradle.kts`.

The build needs one git submodule, `Android/src/chimera`
(github.com/shakfu/chimera). Its `scripts/manage.py` pins the versions of the three
engines the native build uses: llama.cpp, stable-diffusion.cpp and whisper.cpp.
`Android/src/native/CMakeLists.txt` reads those pins and fetches the three engines
with CMake's `FetchContent` into `Android/src/native/.deps`, which is gitignored and
reused between builds. The first build needs network access to fetch them; later
builds do not, as long as `.deps` survives. To move all three engines to a newer
release, move the Chimera submodule to a newer commit.

`apply-patches.cmake` patches stable-diffusion.cpp at fetch time, with two patches
from `Android/src/chimera/scripts/patches/` and one from `Android/src/native/patches/`.
A patch that no longer applies fails the CMake configure step instead of being
silently skipped.

```bash
git submodule update --init
cd Android/src
./gradlew assembleRelease
```

The APK is written to `Android/src/app/build/outputs/apk/release/`. Add `-PuseCcache`
to cache the native builds between runs.

The build targets `arm64-v8a` only. The LiteRT and QNN libraries in `jniLibs` exist for
that ABI alone. AGP 9.0.1, Kotlin 2.2.21, `minSdk` 31, `compileSdk` and `targetSdk` 37.

Release builds read `keystore.properties` from `Android/src/`. The file is gitignored.

## Upstream sync and releases

This repository is a fork of `google-ai-edge/gallery`, merged with `jegly/Box`. The
`upstream` remote tracks Google.

`.github/workflows/sync-and-release.yaml` runs every day at 03:17 UTC, on each push to
`main` that changes `Android/` or `.github/`, and on demand. Each run does these steps:

1. Fetch `upstream/main` and try to merge it.
2. If the merge is clean, build a signed release APK.
3. If the build passes, push the merge to `main` and publish a GitHub release with the APK.
4. If the build of a clean merge fails, push the merge to a `sync/upstream-<sha>` branch,
   build the fork's own `main` again, and release that build.
5. If the merge has conflicts, abort it. The run opens or updates one issue with the
   `upstream-merge-conflict` label. The issue shows, for each conflicting file, what each
   side changed against the merge base.
6. If a build fails, the run opens or updates one issue with the `build-failure` label.
7. When a later run succeeds, it closes the issues that no longer apply.

Release tags have the form `v<versionName>-<versionCode>-<short sha>`. The workflow signs
the release with a keystore from the repository secrets.

`.github/workflows/ci-build.yaml` runs on each pull request to `main` and on demand. It
builds a release APK, runs the JVM unit tests, checks that R8 kept a service file the app
needs at launch, and uploads the APK as an artifact. CI has no `keystore.properties`, so
this build is signed with the debug key.

## Model catalogue

The app fetches its list of models from Google's repository at run time, not from
`model_allowlists/` here. See `model_allowlists/README.md`.
