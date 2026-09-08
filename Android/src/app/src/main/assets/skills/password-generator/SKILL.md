---
name: password-generator
description: Generate a secure random password. Use when the user asks to generate, create, or make a password.
---

# Password Generator

## Instructions

Call the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following fields:
  - `length`: Number. Password length. Default: 16. Max: 128.
  - `uppercase`: Boolean. Include uppercase letters (A-Z). Default: true.
  - `lowercase`: Boolean. Include lowercase letters (a-z). Default: true.
  - `numbers`: Boolean. Include digits (0-9). Default: true.
  - `symbols`: Boolean. Include symbols (!@#$%^&*). Default: true.
  - `count`: Number. How many passwords to generate. Default: 1. Max: 10.

## Examples

- "Generate a password" → length: 16, all true, count: 1
- "Make me a 24-character password with no symbols" → length: 24, symbols: false
- "Generate 5 strong passwords" → count: 5, all true
- "Simple password, letters and numbers only, 12 chars" → length: 12, symbols: false
- "Very strong 32-char password" → length: 32, all true
