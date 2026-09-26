# Freehold API Server

Freehold includes an OpenAI-compatible HTTP server that runs on the device. This
document covers how to start it, the network modes, and how to connect clients
to it.

## Start the server

Two ways:

**In the app.** Open the Server screen and start the server. The API key is
shown on the Server screen.

**Headless, over adb:**

```bash
adb shell am start -n io.github.xopher00.freehold/com.google.ai.edge.gallery.MainActivity --ez start_api_server true
```

To load a model at start, add `--es load_model <MODEL_ID>` and, optionally,
`--es accelerator <cpu|gpu|npu>`:

```bash
adb shell am start -n io.github.xopher00.freehold/com.google.ai.edge.gallery.MainActivity --ez start_api_server true --es load_model <MODEL_ID> --es accelerator gpu
```

The activity class keeps its source package, so the component name is
`com.google.ai.edge.gallery.MainActivity` even though the application id is
`io.github.xopher00.freehold`.

## Network modes

The mode is chosen on the Server screen. The port is `8080` in every mode.

- **Loopback (this device only)** — the default. Binds `127.0.0.1`. To reach
  the server from a computer, run `adb forward tcp:8080 tcp:8080` and use
  `http://127.0.0.1:8080`.
- **LAN (same network)** — binds all interfaces, so any device on the same
  Wi-Fi can reach the server at the device's address on that network, port
  `8080`.
- **Specific interface** — binds one chosen network interface's address, for
  example a VPN interface. If that interface is down, the server refuses to
  start rather than widening to all interfaces.

Every mode still requires the API key.

## Smoke test

`/health` needs no auth:

```bash
curl http://127.0.0.1:8080/health
```

Everything else needs the bearer header. Without it, the server returns `401`:

```bash
curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer <YOUR_KEY>"
```

## Client recipes

The recipes below assume loopback mode with `adb forward tcp:8080 tcp:8080`
active on the computer. In LAN mode, replace `127.0.0.1` with the device's
address on the network. Replace `<YOUR_KEY>` and `<MODEL_ID>` everywhere.

### Python (`openai` SDK)

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:8080/v1",
    api_key="<YOUR_KEY>",
)

reply = client.chat.completions.create(
    model="<MODEL_ID>",
    messages=[{"role": "user", "content": "Hello"}],
)
print(reply.choices[0].message.content)
```

### curl

Non-streaming:

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer <YOUR_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "<MODEL_ID>",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

Streaming — set `"stream": true`. The response is SSE and ends with
`data: [DONE]`:

```bash
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer <YOUR_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "<MODEL_ID>",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

### Chatbox

Add a custom provider of the OpenAI-API-compatible type and set:

- `base_url`: `http://127.0.0.1:8080/v1`
- `api_key`: `<YOUR_KEY>`
- `model`: `<MODEL_ID>`

### Home Assistant

In the OpenAI Conversation integration, configure a custom endpoint:

- base_url: `http://<device-address>:8080/v1`
- api_key: `<YOUR_KEY>`
- model: `<MODEL_ID>`

This only works if the Home Assistant instance can reach the device: use LAN
mode, or a forwarded port.

### Tasker

Use the HTTP Request action:

- URL: `http://127.0.0.1:8080/v1/chat/completions` when Tasker runs on the
  same device as the server; this works in loopback mode.
- URL: `http://<device-address>:8080/v1/chat/completions` when Tasker runs on
  another device; this needs LAN mode or specific-interface mode.
- Header: `Authorization: Bearer <YOUR_KEY>`
- Header: `Content-Type: application/json`
- Body: the same JSON as the curl recipe above (`model` and `messages`).

### Continue.dev

In `config.json`, add a provider entry:

```json
{
  "models": [
    {
      "title": "Freehold",
      "provider": "openai",
      "model": "<MODEL_ID>",
      "apiBase": "http://127.0.0.1:8080/v1",
      "apiKey": "<YOUR_KEY>"
    }
  ]
}
```

## Audio transcription and image generation

### `POST /v1/audio/transcriptions`

Multipart form upload. Form fields: `file` (required), `model`, optional
`language`, optional `response_format` — `json` (the default) or `text`.

```bash
curl http://127.0.0.1:8080/v1/audio/transcriptions \
  -H "Authorization: Bearer <YOUR_KEY>" \
  -F file=@audio.wav \
  -F model=<MODEL_ID> \
  -F response_format=text
```

### `POST /v1/images/generations`

JSON body: `prompt`; `size` (the only supported value is `512x512`, which is
also the default); `n` (must be `1`); `response_format` (defaults to
`b64_json`; `url` is rejected — the server never hosts generated images over
HTTP).

```bash
curl http://127.0.0.1:8080/v1/images/generations \
  -H "Authorization: Bearer <YOUR_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "a lighthouse at dusk",
    "size": "512x512",
    "n": 1
  }'
```

## Troubleshooting

- **Connection refused** — the server is not started, the network mode does
  not match how you are connecting, or the `adb forward` is not active.
- **`401`** — the key is wrong, or it was regenerated after your client saved
  it. The current key is on the Server screen.
- **`404` "Unknown model"** — the `model` field must match an id from
  `GET /v1/models` exactly.
- **`429`** — the model is busy with another request. Retry.
- **`503`** — the server is at capacity, or the device is too hot. Retry
  later.
- **`501`** — the request used a feature listed under [Limits](#limits).

One chat model is loaded at a time, so requests are served one after another,
not in parallel.

## Limits

Currently registered routes:

| Method | Path |
|:--|:--|
| GET | `/health` |
| GET | `/v1/models` |
| GET | `/v1/models/{modelId}` |
| POST | `/v1/models/{id}/load` |
| POST | `/v1/models/{id}/unload` |
| POST | `/v1/models/{id}/benchmark` |
| POST | `/v1/chat/completions` |
| POST | `/v1/completions` |
| POST | `/v1/messages` |
| POST | `/v1/audio/transcriptions` |
| POST | `/v1/images/generations` |
| POST | `/v1/vision/detect` |
| POST | `/v1/vision/segment` |
| POST | `/v1/vision/ocr` |
| POST | `/v1/embeddings` |
| POST | `/v1/images/edits` |
| POST | `/v1/agent/run` |
| GET | `/v1/agent/tools` |

Notes:

- Audio transcription, image generation and embeddings are real, working
  routes.
- `/v1/messages` exists and returns a real single-turn text reply, but returns
  `501` on any `tools` array or `stream: true` today. A build to add both is
  in progress.
- Tool/function calling on `/v1/chat/completions` is not implemented. This
  fork's only tool-calling mechanism is the separate `/v1/agent/run`
  endpoint over the app's own compiled-in tools, not OpenAI-style `tools` on
  chat completions.
