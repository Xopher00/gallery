// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import android.content.Context
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.runtime.BenchmarkSample
import com.google.ai.edge.gallery.relay.runtime.BenchmarkSpec
import com.google.ai.edge.gallery.relay.runtime.ModelBenchmarkRunner
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.honestDefaultAcceleratorLabel
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkRequest(
  val prefill_tokens: Int = 128,
  val decode_tokens: Int = 64,
  val runs: Int = 1,
)

@Serializable
data class BenchmarkSampleData(
  val init_time_seconds: Double,
  val time_to_first_token_seconds: Double,
  val prefill_token_count: Int,
  val decode_token_count: Int,
  val prefill_tokens_per_second: Double,
  val decode_tokens_per_second: Double,
)

@Serializable
data class BenchmarkResponse(
  val id: String,
  val samples: List<BenchmarkSampleData>,
)

private fun BenchmarkSample.toData() = BenchmarkSampleData(
  init_time_seconds = initTimeSeconds,
  time_to_first_token_seconds = timeToFirstTokenSeconds,
  prefill_token_count = prefillTokenCount,
  decode_token_count = decodeTokenCount,
  prefill_tokens_per_second = prefillTokensPerSecond,
  decode_tokens_per_second = decodeTokensPerSecond,
)

// Caps kept low enough that a single request can't overheat the phone -- see ModelBenchmarkRunner.
internal fun benchmarkRequestError(req: BenchmarkRequest): String? = when {
  req.prefill_tokens !in 1..4096 -> "prefill_tokens must be between 1 and 4096"
  req.decode_tokens !in 1..1024 -> "decode_tokens must be between 1 and 1024"
  req.runs !in 1..5 -> "runs must be between 1 and 5"
  else -> null
}

suspend fun handleBenchmark(
  call: ApplicationCall,
  id: String,
  request: BenchmarkRequest,
  context: Context,
  modelRegistry: ModelRegistry,
) {
  val tasks = modelRegistry.tasks
  val task = tasks.find { t -> t.models.any { it.name == id } }
  val model = task?.models?.find { it.name == id }
  if (model == null) {
    call.respond(HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = "Unknown model '$id'")))
    return
  }

  benchmarkRequestError(request)?.let { message ->
    call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = message)))
    return
  }

  // Same config-value lookup the benchmark screen's accelerator default resolves to server-side.
  val accelerator = model.getStringConfigValue(
    key = ConfigKeys.ACCELERATOR,
    defaultValue = honestDefaultAcceleratorLabel(model),
  )
  val spec = BenchmarkSpec(
    prefillTokens = request.prefill_tokens,
    decodeTokens = request.decode_tokens,
    accelerator = accelerator,
  )

  try {
    val samples = ModelBenchmarkRunner.run(context, model, spec, request.runs) { _, _ -> }
    call.respond(BenchmarkResponse(id = model.name, samples = samples.map { it.toData() }))
  } catch (e: IllegalStateException) {
    call.respond(HttpStatusCode.Conflict, ErrorEnvelope(ErrorBody(message = e.message ?: "Benchmark already running")))
  } catch (e: UnsupportedOperationException) {
    call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = e.message ?: "No benchmark path for this model")))
  }
}
