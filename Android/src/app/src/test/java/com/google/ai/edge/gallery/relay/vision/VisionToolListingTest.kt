// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionToolListingTest {

    @Test
    fun entriesWithNeitherFileListsOnlyOcr() {
        val entries = VisionToolListing.entries(detectorFileExists = false, segmenterFileExists = false)

        assertEquals(listOf("ocr"), entries.map { it.id })
    }

    @Test
    fun entriesWithDetectorFileAddsDetector() {
        val entries = VisionToolListing.entries(detectorFileExists = true, segmenterFileExists = false)

        assertEquals(listOf("ocr", ModelCatalog.DEFAULT_DETECTOR_MODEL), entries.map { it.id })
        assertEquals("object_detection", entries.first { it.id == ModelCatalog.DEFAULT_DETECTOR_MODEL }.kind)
    }

    @Test
    fun entriesWithSegmenterFileAddsSegmenter() {
        val entries = VisionToolListing.entries(detectorFileExists = false, segmenterFileExists = true)

        assertEquals(listOf("ocr", ModelCatalog.DEFAULT_SEGMENTER_MODEL), entries.map { it.id })
        assertEquals("segmentation", entries.first { it.id == ModelCatalog.DEFAULT_SEGMENTER_MODEL }.kind)
    }

    @Test
    fun entriesWithBothFilesListsAllThree() {
        val entries = VisionToolListing.entries(detectorFileExists = true, segmenterFileExists = true)

        assertEquals(
            listOf("ocr", ModelCatalog.DEFAULT_DETECTOR_MODEL, ModelCatalog.DEFAULT_SEGMENTER_MODEL),
            entries.map { it.id },
        )
    }

    @Test
    fun misdirectedTextRequestMessageNamesTheEndpoint() {
        val entry = VisionToolEntry(id = "ocr", kind = "ocr", endpoint = "/v1/vision/ocr")

        assertEquals(
            "'ocr' is a vision tool; send it to /v1/vision/ocr",
            VisionToolListing.misdirectedTextRequestMessage(entry),
        )
    }

    @Test
    fun toModelDataSetsRuntimeStatusAndKind() {
        val modelData = VisionToolEntry(id = "ocr", kind = "ocr", endpoint = "/v1/vision/ocr").toModelData()

        assertEquals("vision", modelData.runtime)
        assertEquals("available", modelData.status)
        assertEquals("ocr", modelData.kind)
    }
}
