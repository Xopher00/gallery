---
name: translator
description: Translates text between languages. Use this when the user wants to translate a word, phrase, or passage into another language.
---

# Translator

## Instructions

The `translator` skill translates text between any pair of languages. **You** perform the translation using your own knowledge, then call `run_js` to display the result and save it to the local history.

### Workflow for every translation request
1. Identify the source language (detect it automatically if not specified — note it as "Auto-detected").
2. Identify the target language from the user's request.
3. **Translate the text yourself.**
4. Call `run_js` with `index.html` to display the result and persist it.

---

### Actions

#### 1. Translate
When a user wants to translate text, call `run_js` with:
- **script name**: `index.html`
- **data**: JSON string with:
  - `action`: `"translate"`
  - `original`: String — the original text exactly as the user provided it
  - `translated`: String — your translation
  - `source_lang`: String — detected or specified source language (e.g. `"English"`, `"Auto-detected"`)
  - `target_lang`: String — target language (e.g. `"French"`, `"Japanese"`)

#### 2. View History
When a user asks to see their translation history, call `run_js` with:
- **script name**: `index.html`
- **data**: JSON string with:
  - `action`: `"history"`

#### 3. Clear History
When a user wants to clear their translation history, call `run_js` with:
- **script name**: `index.html`
- **data**: JSON string with:
  - `action`: `"clear"`

---

### Rules
- Always translate **before** calling `run_js` — never call `run_js` with an empty `translated` field.
- If the user asks to translate into multiple languages, make one `run_js` call per language.
- If the source and target language appear to be the same, ask the user to clarify.
- All history is stored locally on the device.

### Sample Commands
- "Translate 'Good morning' to Spanish"
- "How do you say 'thank you' in Japanese?"
- "Translate this paragraph to French: ..."
- "What is 'Bonjour' in English?"
- "Show my translation history"
- "Clear my translation history"
