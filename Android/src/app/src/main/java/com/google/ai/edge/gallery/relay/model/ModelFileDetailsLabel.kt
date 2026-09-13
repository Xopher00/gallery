// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.huggingface.isLiteRtLmFileName

// Fork-owned: not added to Google's ConfigKeys, per the 2026-09-13 decision to keep this row
// out of Google's shared config surface.
internal val MODEL_FILE_DETAILS_LABEL_KEY =
    ConfigKey(
        "model_file_details",
        "Model file details",
        R.string.relay_config_label_model_file_details,
    )

private const val FROM_FILENAME_SUFFIX = " (from file name)"
private const val NOT_SELECTED_SUFFIX = ", not selected"

/** Null when the resolver found nothing to show, so callers can skip adding the row. */
internal fun buildModelFileDetailsText(
    details: ModelFileDetails,
    selectedAccelerators: List<Accelerator>,
): String? {
    val lines = mutableListOf<String>()
    details.contextLength?.let { contextLength ->
        val marker = if (contextLength.fromFilename) FROM_FILENAME_SUFFIX else ""
        lines.add("Context window: ${contextLength.value}$marker")
    }
    details.accelerator?.let { accelerator ->
        val marker = if (accelerator.fromFilename) FROM_FILENAME_SUFFIX else ""
        val notSelected = if (accelerator.value !in selectedAccelerators) NOT_SELECTED_SUFFIX else ""
        lines.add("Accelerator in file: ${accelerator.value.label}$marker$notSelected")
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

// URL imports have no file on disk yet at dialog time, so only the filename rules apply here.
internal fun buildModelFileDetailsTextFromFileName(fileName: String): String? {
    if (!isLiteRtLmFileName(fileName)) return null
    val lines = mutableListOf<String>()
    liteRtLmContextLength(fileName)?.let { contextLength ->
        lines.add("Context window: ${contextLength.value}$FROM_FILENAME_SUFFIX")
    }
    liteRtLmAcceleratorFromFilename(fileName)?.let { accelerator ->
        lines.add("Accelerator in file: ${accelerator.value.label}$FROM_FILENAME_SUFFIX")
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
