/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const SUPPORTED_ALGORITHMS = ['SHA-1', 'SHA-256', 'SHA-384', 'SHA-512'];

function resolveAlgorithm(input) {
  if (!input) return 'SHA-256'; // default
  const upper = input.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (upper === 'SHA1')   return 'SHA-1';
  if (upper === 'SHA256') return 'SHA-256';
  if (upper === 'SHA384') return 'SHA-384';
  if (upper === 'SHA512') return 'SHA-512';
  // Try matching as-is
  const match = SUPPORTED_ALGORITHMS.find(a => a.replace('-', '') === upper);
  if (match) return match;
  throw new Error(`Unsupported algorithm "${input}". Supported: SHA-1, SHA-256, SHA-384, SHA-512.`);
}

async function digestMessage(message, algorithm) {
  const msgUint8 = new TextEncoder().encode(message);
  const hashBuffer = await crypto.subtle.digest(algorithm, msgUint8);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
}

window['ai_edge_gallery_get_result'] = async (data) => {
  try {
    const jsonData = JSON.parse(data);
    const text = jsonData['text'];
    if (!text && text !== '') throw new Error('No text provided.');
    const algorithm = resolveAlgorithm(jsonData['algorithm']);
    const hash = await digestMessage(text, algorithm);
    return JSON.stringify({ result: `${algorithm}: ${hash}` });
  } catch (e) {
    console.error(e);
    return JSON.stringify({ error: `Failed to calculate hash: ${e.message}` });
  }
};
