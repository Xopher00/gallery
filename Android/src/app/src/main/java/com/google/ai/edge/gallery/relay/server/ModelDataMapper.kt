// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * Ported from mobile-server (com.server.edge.gallery) into this project (relay).
 *
 * Changes from the source:
 *  - package rewritten to com.google.ai.edge.gallery
 *  - binds to 127.0.0.1 only (was 0.0.0.0)
 *  - inference now goes through model.runtimeHelper.runInference (relay's per-runtime
 *    dispatch: LiteRT-LM / llama.cpp / AICore) instead of a hardcoded LlmChatModelHelper
 *  - bearer API key auth required on all v1 routes; health is open
 */
package com.google.ai.edge.gallery.relay.server

import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model

// Builds the OpenAI-shaped model DTO, surfacing the runtime this model actually loaded
// under and its configured accelerators so a client can tell what it is getting.
internal fun Model.toModelData(server: OpenAiServer): ModelData {
    val runtime = server.modelRegistry.engineOf(this).wireName
    // Stored ACCELERATOR preference -- independent of what the engine actually runs on (a
    // per-request override can reinit without durably changing this; see ChatHandler snapshot/restore).
    val preferredAccelerator = this
        .getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = honestDefaultAcceleratorLabel(this))
        .trim()
        .lowercase()
    // Actually-running accelerator -- only meaningful while instance != null (getEngineAccelerator
    // is never cleared on cleanup); falls back to the stored preference when there's no live engine.
    val actualAccelerator = if (this.instance != null) {
        server.modelRegistry.getEngineAccelerator(this.name)?.trim()?.lowercase() ?: preferredAccelerator
    } else {
        preferredAccelerator
    }
    // Every accelerator this model SUPPORTS, from Model.accelerators (populated at import
    // time from ConfigKeys.COMPATIBLE_ACCELERATORS -- see ModelRegistry.createModelFromImportedModelInfo).
    val compatibleAccelerators = this.accelerators.map { it.label.lowercase() }

    return ModelData(
        id = this.name,
        created = System.currentTimeMillis() / 1000,
        runtime = runtime,
        accelerator = actualAccelerator,
        preferred_accelerator = preferredAccelerator,
        compatible_accelerators = compatibleAccelerators,
        // See ModelData.accelerators for why this duplicates `accelerator` above -- it
        // follows the engine's actual accelerator too, same as `accelerator` does.
        accelerators = listOf(actualAccelerator),
        status = if (this.instance != null) "loaded" else "available",
    )
}
