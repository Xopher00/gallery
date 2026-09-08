# Android

The only build target in this repository.

| Path | Contents |
|---|---|
| `src/app/` | Application module. Kotlin, Compose, resources, assets |
| `src/smollm/` | JNI module for llama.cpp, used for GGUF models |
| `src/stablediffusion/` | JNI module for stable-diffusion.cpp |
| `src/whisper/` | JNI module for whisper.cpp |
| `src/{llama,stable-diffusion,whisper}.cpp/` | Submodules with the C and C++ sources |

Kotlin lives under `src/app/src/main/java/com/google/ai/edge/gallery/`. Code under
`relay/` belongs to this fork: the API server, the model registry, device capability,
vision wrappers, sessions and security. The rest is upstream code, with fork additions
placed where upstream puts that kind of file.

See [`../DEVELOPMENT.md`](../DEVELOPMENT.md) for the build.
