// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

// The engine reports KV-budget overflow as a thrown exception's message, not a distinct error type.
fun isContextOverflow(message: String?): Boolean =
    message != null &&
        (message.contains("Input token ids are too long") ||
            message.contains("Exceeding the maximum number of tokens allowed"))
