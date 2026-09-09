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
`upstream` remote tracks Google.

`.github/workflows/upstream-drift.yaml` runs daily. It reports drift to a reusable
issue, then attempts a merge. A clean merge that also builds pushes straight to `main`.
A clean merge whose build then fails opens a draft PR. A real conflict opens a reusable
issue instead, with no branch and no PR. `workflow_dispatch` runs it by hand.

The conflict issue includes a diff of each conflicting file against the merge base, for
both the fork's side and upstream's side. This shows what each side changed on its own,
not just the raw conflict markers. The issue body also carries a hidden HTML comment
with both commit SHAs, for the diagnostic workflow below to read.

`.github/workflows/build-diagnostics.yaml` holds two independent jobs. Neither job opens
an issue. Each one only comments on an issue that some other workflow already opened.

`conflict_diagnosis` runs when `upstream-drift.yaml` opens a conflict issue. It resolves
the conflict with `git merge -X ours`, a throwaway pick that favors the fork's side, then
tries to compile the result. A plain text diff cannot show that one side deleted a symbol
the other side still calls. Only a real compile catches that. The job never commits or
pushes this merge. It posts the compile result as a comment on the issue.

`release.yaml` opens a fresh `ci-build-failure` issue on its own failure, one per
failure, since each one ties to a specific commit. `release_failure_diagnosis` runs when
that issue opens. It finds the last commit where `Release APK` passed, then diffs the
files named in the compiler error against that commit, and posts both as a comment.

`build_android.yaml` is upstream's own workflow, kept identical to upstream. It does not
fetch submodules and cannot build this tree. `ci-build.yaml` is the fork's build.

## Model catalogue

The app fetches its list of models from Google's repository at run time, not from
`model_allowlists/` here. See `model_allowlists/README.md`.
