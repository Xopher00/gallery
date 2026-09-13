# Android

The only build target in this repository.

| Path | Contents |
|---|---|
| `src/app/` | Application module. Kotlin, Compose, resources, assets |
| `src/smollm/` | Kotlin API and JNI sources for llama.cpp, used for GGUF models |
| `src/stablediffusion/` | Kotlin API and JNI sources for stable-diffusion.cpp |
| `src/whisper/` | Kotlin API and JNI sources for whisper.cpp |
| `src/native/` | The one native CMake project. Fetches llama.cpp, stable-diffusion.cpp and whisper.cpp at the versions Chimera pins, and builds the native libraries |
| `src/chimera/` | Submodule. Pins the engine versions and carries the stable-diffusion.cpp patches |

Kotlin lives under `src/app/src/main/java/com/google/ai/edge/gallery/`. Code under
`relay/` belongs to this fork: the API server, the model registry, device capability,
vision wrappers, sessions and security. The rest is upstream code, with fork additions
placed where upstream puts that kind of file.

See [`../DEVELOPMENT.md`](../DEVELOPMENT.md) for the build.
