// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.runtime

// Engine-agnostic request, decoupled from OpenAiModels.kt's wire DTO; the schema travels as a
// raw JSON string so each engine parses it however its own SDK requires.
data class StructuredOutputRequest(val schemaJson: String)
