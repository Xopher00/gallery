# model_allowlists

The app does not read these files. They arrived with the upstream import and nothing
in this repository references them.

At run time the app fetches
`https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists/<version>.json`.
That is Google's repository. `<version>` is the app's `versionName` with dots replaced
by underscores, so version 1.0.19 requests `1_0_19.json`. If the fetch fails, the app
uses the last cached copy, and then the bundled
`Android/src/app/src/main/assets/model_allowlist.json`. The URL is a constant in
`Android/src/app/src/main/java/com/google/ai/edge/gallery/relay/model/ModelAllowlistLoader.kt`
and again in `ui/modelmanager/ModelManagerViewModel.kt`.

Two results follow. The models offered in the app are the models Google publishes.
Edits to files here change nothing. And if `versionName` is set past the newest file
Google publishes, the fetch fails and the app falls back with no error shown.

The files are kept as a record of upstream's schema across versions 1.0.4 to 1.0.19.
`model_allowlist.json` at the repository root is an older snapshot from before the
LiteRT-LM format.
