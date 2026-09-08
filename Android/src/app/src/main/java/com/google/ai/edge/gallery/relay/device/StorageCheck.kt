// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.device

// A size of 0 means unknown/unresolved and must never warn (see ModelManagerViewModel choke point).
fun needsStorageWarning(requiredBytes: Long, freeBytes: Long): Boolean =
  requiredBytes > 0L && requiredBytes > freeBytes
