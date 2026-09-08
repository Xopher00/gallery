# Restaurant Roulette

Asks Gemini for up to 10 restaurants for a cuisine and location, then shows them on a
spin wheel in a webview.

## API key

Required. Get a key at https://ai.google.dev/gemini-api/docs/api-key. The app passes
it to the skill as its secret. `scripts/index.js` reads it from the `secret` argument
into `GEMINI_API_KEY`.

## What the skill does

`scripts/index.js` takes `location` and `cuisine`. It sends a prompt to
`gemini-2.5-flash` with a response schema that forces a JSON array of names. It
shuffles the names and passes them to `assets/ui.html` as the wheel segments.

The names come from the model, not from a places API. They are not checked against
real restaurants.

`SKILL.md` tells the model not to pick a winner. The user spins the wheel.

If the Gemini call fails, the wheel still renders. Its segments are then `Error:`, the
error message, `Check` and `Console`.

Copyright 2026 Google LLC

Licensed under the Apache License, Version 2.0 (the "License"); \
you may not use this file except in compliance with the License. \
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software \
distributed under the License is distributed on an "AS IS" BASIS, \
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. \
See the License for the specific language governing permissions and \
limitations under the License.
