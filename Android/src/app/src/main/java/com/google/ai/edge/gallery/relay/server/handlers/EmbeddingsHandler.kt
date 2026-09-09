// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.runtime.EmbeddingCapable
import com.google.ai.edge.gallery.relay.server.EmbeddingData
import com.google.ai.edge.gallery.relay.server.EmbeddingsRequest
import com.google.ai.edge.gallery.relay.server.EmbeddingsResponse
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Accepts the same string-or-array shape as ChatMessageIn.content; null means the element
// wasn't a plain string, which the caller turns into a 400.
private fun parseEmbeddingsInput(input: JsonElement): List<String>? {
  return when (input) {
    is JsonPrimitive -> if (input.isString) listOf(input.content) else null
    is JsonArray -> input.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    else -> null
  }
}

suspend fun handleEmbeddings(
  call: ApplicationCall,
  request: EmbeddingsRequest,
  modelRegistry: ModelRegistry,
  modelMutexes: ConcurrentHashMap<String, Mutex>,
  loadModel: suspend (String, String?) -> LoadResult,
) {
  val inputs = parseEmbeddingsInput(request.input)
  if (inputs.isNullOrEmpty()) {
    call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "'input' must be a string or array of strings")))
    return
  }

  var model = modelRegistry.getModelByName(request.model)
  if (model == null) {
    call.respond(HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = "Unknown model '${request.model}'")))
    return
  }

  if (model.instance == null) {
    when (val result = loadModel(request.model, null)) {
      is LoadResult.Loaded -> model = modelRegistry.getModelByName(request.model)
      else -> {
        respondLoadError(call, result)
        return
      }
    }
  }
  val loadedModel = model
  if (loadedModel == null) {
    call.respond(HttpStatusCode.ServiceUnavailable, ErrorEnvelope(ErrorBody(message = "Model '${request.model}' failed to load")))
    return
  }

  val guardResult = withBusyGuard(
    mutexes = modelMutexes,
    key = loadedModel.name,
    timeoutMs = NO_BUSY_GUARD_TIMEOUT_MS,
  ) {
    val embedder = loadedModel.instance as? EmbeddingCapable
    if (embedder == null) {
      call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Model '${loadedModel.name}' is not an embedding model")))
      return@withBusyGuard
    }

    try {
      val vectors = inputs.map { embedder.embed(it) }
      call.respond(
        EmbeddingsResponse(
          data = vectors.mapIndexed { index, vector -> EmbeddingData(embedding = vector.toList(), index = index) },
          model = loadedModel.name,
        )
      )
    } catch (e: IllegalStateException) {
      call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = e.message ?: "Model '${loadedModel.name}' is not an embedding model")))
    }
  }
  when (guardResult) {
    is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = "Model is busy")))
    is BusyResult.TimedOut -> {}
    is BusyResult.Ok -> {}
  }
}
