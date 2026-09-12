// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.runtime.ModelEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun sdModel(name: String, loaded: Boolean): Model =
    Model(name = name).apply { if (loaded) instance = Any() }

private fun nonSdModel(name: String): Model = Model(name = name)

private val sdEngineOf: (Model) -> ModelEngine = { ModelEngine.StableDiffusion }
private val isLoaded: (Model) -> Boolean = { it.instance != null }

class ImageModelSelectionTest {

    @Test
    fun loadedStableDiffusionModelsExcludesNullInstance() {
        val loadedModel = sdModel("loaded", loaded = true)
        val unloadedModel = sdModel("unloaded", loaded = false)

        val result = loadedStableDiffusionModels(
            models = listOf(loadedModel, unloadedModel),
            engineOf = sdEngineOf,
            isLoadedStableDiffusion = isLoaded,
        )

        assertEquals(listOf(loadedModel), result)
    }

    @Test
    fun loadedStableDiffusionModelsExcludesOtherEngines() {
        val loadedSdModel = sdModel("sd", loaded = true)
        val otherEngineModel = nonSdModel("other").apply { instance = Any() }

        val result = loadedStableDiffusionModels(
            models = listOf(loadedSdModel, otherEngineModel),
            engineOf = { if (it.name == "sd") ModelEngine.StableDiffusion else ModelEngine.LlamaCpp },
            isLoadedStableDiffusion = isLoaded,
        )

        assertEquals(listOf(loadedSdModel), result)
    }

    @Test
    fun selectStableDiffusionModelWithNoRequestedNamePicksFirstLoaded() {
        val first = sdModel("first", loaded = true)
        val second = sdModel("second", loaded = true)

        val result = selectStableDiffusionModel(listOf(first, second), requestedName = null)

        assertEquals(first, result)
    }

    @Test
    fun selectStableDiffusionModelWithRequestedNameFindsMatch() {
        val first = sdModel("first", loaded = true)
        val second = sdModel("second", loaded = true)

        val result = selectStableDiffusionModel(listOf(first, second), requestedName = "second")

        assertEquals(second, result)
    }

    @Test
    fun selectStableDiffusionModelWithRequestedNameNotAmongLoadedReturnsNull() {
        val loaded = sdModel("loaded", loaded = true)

        val result = selectStableDiffusionModel(listOf(loaded), requestedName = "unloaded")

        assertNull(result)
    }

    @Test
    fun selectStableDiffusionModelWithNoLoadedModelsReturnsNull() {
        val result = selectStableDiffusionModel(emptyList(), requestedName = null)

        assertNull(result)
    }
}
