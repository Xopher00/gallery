// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import com.google.ai.edge.gallery.data.BackendSpec
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.data.RuntimeType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ggufModel(isLlm: Boolean): Model =
    Model(
        name = "gguf",
        downloadInfo = ModelDownloadInfo(downloadFileName = "model.gguf"),
        isLlm = isLlm,
    )

private fun liteRtLmModel(isLlm: Boolean): Model =
    Model(
        name = "litert",
        downloadInfo = ModelDownloadInfo(downloadFileName = "model.task"),
        backendSpec = BackendSpec(runtimeType = RuntimeType.LITERT_LM),
        isLlm = isLlm,
    )

class ModelBenchmarkSupportTest {

    @Test
    fun llamaCppChatModelSupportsBenchmark() {
        assertTrue(ggufModel(isLlm = true).relaySupportsBenchmark)
    }

    @Test
    fun llamaCppEmbeddingModelDoesNotSupportBenchmark() {
        assertFalse(ggufModel(isLlm = false).relaySupportsBenchmark)
    }

    @Test
    fun liteRtLmChatModelSupportsBenchmark() {
        assertTrue(liteRtLmModel(isLlm = true).relaySupportsBenchmark)
    }

    @Test
    fun nonLlmModelDoesNotSupportBenchmark() {
        assertFalse(liteRtLmModel(isLlm = false).relaySupportsBenchmark)
    }
}
