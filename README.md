# Freehold

[![Sync and release](https://github.com/Xopher00/freehold/actions/workflows/sync-and-release.yaml/badge.svg)](https://github.com/Xopher00/freehold/actions/workflows/sync-and-release.yaml)
[![Latest release](https://img.shields.io/github/v/release/Xopher00/freehold)](https://github.com/Xopher00/freehold/releases/latest)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

An Android app that runs language, image and speech models on the device, offline.

## Why

Most assistant apps send each prompt to a cloud service. Freehold runs open models on
the phone, and no prompt leaves the device. Other programs can use the same models through
a local HTTP API that is compatible with OpenAI clients.

Freehold is a fork of [`google-ai-edge/gallery`](https://github.com/google-ai-edge/gallery),
merged with [`jegly/Box`](https://github.com/jegly/Box). Google's project provides the app,
the LiteRT runtime and the model catalogue. The Box fork added image generation, audio
transcription and GGUF inference. This fork adds the API server, model discovery on
Hugging Face and an app lock.

## Install

You need an arm64 device with Android 12 or later.

1. Download `app-release.apk` from the
   [latest release](https://github.com/Xopher00/freehold/releases/latest).
2. Install it with `adb install app-release.apk`, or open the file on the device.

Releases are signed with this fork's own key. The application id is
`io.github.xopher00.freehold`. Builds before this id change used `com.google.aiedge.gallery`.
They install as a separate app, so import your models again in the new app.

The Play Store and the App Store carry Google's upstream app. It does not include the
features below that come from the Box fork or from this fork.

To build from source, see [DEVELOPMENT.md](DEVELOPMENT.md).

## Usage

1. Open the app.
2. Download a model from a task page or from the Model Manager, or import a model file.
3. Start a chat, or open another task from the home screen.

To use the models from another program, start the API server and send an
OpenAI-style request:

```bash
adb shell am start -n io.github.xopher00.freehold/com.google.ai.edge.gallery.MainActivity --ez start_api_server true
adb forward tcp:8080 tcp:8080
curl http://127.0.0.1:8080/v1/models -H "Authorization: Bearer <YOUR_KEY>"
```

The API key is on the app's Server screen. [API.md](API.md) documents the network modes,
client setup for Python, Chatbox, Home Assistant, Tasker and Continue.dev, and every route.

## Features

From the upstream app:

* **Chat**, with a thinking mode on models that support it.
* **Ask Image**: questions about a photo from the camera or the gallery.
* **Audio Scribe**: transcription and translation of voice recordings.
* **Prompt Lab**: single-turn prompts with control over temperature, top-k and top-p.
* **Agent skills and MCP**: tools for the model. See [skills/README.md](skills/README.md)
  and [mcp/README.md](mcp/README.md).
* **Mobile Actions** and **Tiny Garden**: device control and a small game, from natural
  language.
* **Model management and benchmarks**.

From the Box fork:

* **Image generation** with stable-diffusion.cpp.
* **Audio transcription** with whisper.cpp.
* **GGUF models** with llama.cpp, beside the LiteRT models.
* **Chat history**.

From this fork:

* **API server**: chat completions, transcription, image generation, embeddings and vision
  routes behind a bearer token. See [API.md](API.md).
* **Model discovery**: search Hugging Face for LiteRT and GGUF models in the app.
* **App lock**: a biometric lock, encrypted storage and an offline mode.

All inference runs on the device. The app uses the network only to download models and
the model catalogue, and for MCP servers or skills that you configure.

## Status

Active development by one maintainer. The test device is a Samsung Galaxy S24
(Exynos 2400). Each release builds automatically from `main` after a daily merge of
upstream changes. The Claude-compatible `/v1/messages` route does not support tools or
streaming yet. [API.md](API.md) lists the current limits.

## Documentation

* [API.md](API.md): the on-device API server.
* [DEVELOPMENT.md](DEVELOPMENT.md): build, native engines, upstream sync and releases.
* [skills/README.md](skills/README.md) and [mcp/README.md](mcp/README.md): tools for the
  model.
* [Upstream wiki](https://github.com/google-ai-edge/gallery/wiki): the upstream app only.

## Contributing

Bug reports and ideas are welcome as
[issues](https://github.com/Xopher00/freehold/issues). For a larger change, open an issue
before you start.

## License

[Apache License 2.0](LICENSE). The upstream code is copyright Google LLC.
