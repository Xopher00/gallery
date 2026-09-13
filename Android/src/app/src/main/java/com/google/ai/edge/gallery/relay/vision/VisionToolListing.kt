// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

// I4a follow-up: vision tools have no ModelRegistry membership (not Model objects, see
// ModelCatalog.kt); this lists them in GET /v1/models too so a client's model picker sees them.
package com.google.ai.edge.gallery.relay.vision

import android.content.Context
import com.google.ai.edge.gallery.relay.server.ModelData

private const val OCR_ENDPOINT = "/v1/vision/ocr"
private const val DETECT_ENDPOINT = "/v1/vision/detect"
private const val SEGMENT_ENDPOINT = "/v1/vision/segment"

data class VisionToolEntry(
    val id: String,
    val kind: String,
    val endpoint: String,
)

object VisionToolListing {
    // Pure function of the file-exists checks so this is testable on the JVM; OCR has none
    // (its recognizer is bundled into the APK), so it is always listed.
    fun entries(detectorFileExists: Boolean, segmenterFileExists: Boolean): List<VisionToolEntry> {
        val result = mutableListOf(VisionToolEntry(id = "ocr", kind = "ocr", endpoint = OCR_ENDPOINT))
        if (detectorFileExists) {
            result.add(
                VisionToolEntry(id = ModelCatalog.DEFAULT_DETECTOR_MODEL, kind = "object_detection", endpoint = DETECT_ENDPOINT)
            )
        }
        if (segmenterFileExists) {
            result.add(
                VisionToolEntry(id = ModelCatalog.DEFAULT_SEGMENTER_MODEL, kind = "segmentation", endpoint = SEGMENT_ENDPOINT)
            )
        }
        return result
    }

    fun entries(context: Context): List<VisionToolEntry> = entries(
        detectorFileExists = ModelCatalog.detectorFile(context, null).exists(),
        segmenterFileExists = ModelCatalog.segmenterFile(context, null).exists(),
    )

    fun findById(context: Context, id: String): VisionToolEntry? =
        entries(context).firstOrNull { it.id == id }

    fun misdirectedTextRequestMessage(entry: VisionToolEntry): String =
        "'${entry.id}' is a vision tool; send it to ${entry.endpoint}"
}

fun VisionToolEntry.toModelData(): ModelData =
    ModelData(id = id, kind = kind, runtime = "vision", status = "available")
