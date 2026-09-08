# Mood Music

Generates a music track for a described mood with the Loudly API and returns a webview
with an audio player.

## API key

Required. Register at https://www.loudly.com, then create an app at
https://www.loudly.com/developers/apps. The app passes the key to the skill as its
secret. `scripts/index.html` and `scripts/get_genres.html` read it from the `secret`
argument. Without a key the request fails.

## What the skill does

The model makes two calls in order:

1. `scripts/get_genres.html` gets the current genre list from
   `https://soundtracks.loudly.com/api/ai/genres`.
2. `scripts/index.html` posts the request to
   `https://soundtracks.loudly.com/api/ai/songs` and returns a webview for
   `assets/webview.html` with the track.

The genre in step 2 must match a name from step 1. Other fields: `genre_blend`,
`duration` (30 to 420 seconds, default 120), `energy` (`low`, `high`, `original`)
and `bpm`.

The skill accepts image and audio input. `SKILL.md` tells the model to read a mood from
the media and map it to a genre from step 1.

If the API returns an empty audio URL, `assets/webview.html` plays a demo track from
soundhelix.com and does not report the failure.

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
