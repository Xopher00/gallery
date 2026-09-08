# Virtual Piano

Shows a playable 88-key piano in a webview.

The skill needs no API key and makes no network requests.

## What the skill does

`scripts/index.js` ignores its input and returns a webview URL for `assets/ui.html`.
That page renders a scrolling 88-key keyboard with the Web Audio API. The notes play
from the 88 local samples in `assets/assets/1.mp3` to `88.mp3`.

The piano samples are credited to https://github.com/fuhton/piano-mp3 under the MIT
license. This credit comes from the original README and was not checked against that
project.

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
