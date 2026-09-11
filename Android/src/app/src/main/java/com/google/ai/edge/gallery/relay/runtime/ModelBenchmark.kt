/*
 * Box: runtime-level model benchmarking, below the UI and the API server. Engines produce samples
 * here; front ends only aggregate and render, so no interface owns the capability.
 */

package com.google.ai.edge.gallery.relay.runtime

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.runtime.llamacpp.LlamaCppEngine
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import com.jegly.offlineLLM.smollm.SmolLM
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val TAG = "BoxModelBenchmark"

/**
 * One benchmark run of one model. Mirrors litertlm `BenchmarkInfo` field for field so that path is
 * an identity map and the existing ValueSeries/proto/viewer pipeline is reused unchanged.
 */
data class BenchmarkSample(
  val initTimeSeconds: Double,
  val timeToFirstTokenSeconds: Double,
  val prefillTokenCount: Int,
  val decodeTokenCount: Int,
  val prefillTokensPerSecond: Double,
  val decodeTokensPerSecond: Double,
)

/** What to measure. Each engine ignores what it cannot honor. */
data class BenchmarkSpec(
  val prefillTokens: Int,
  val decodeTokens: Int,
  val accelerator: String,
)

/**
 * Time-to-first-token is not interchangeable across engines: LiteRT-LM's includes tokenization,
 * llama.cpp's is a pure decode of a synthetic token batch. Same shape, different content.
 */
object ModelBenchmarkRunner {
  private val inFlight = Mutex()

  /** [runCount] samples, one at a time, calling [onSample] as each completes. */
  suspend fun run(
    context: Context,
    model: Model,
    spec: BenchmarkSpec,
    runCount: Int,
    onSample: (index: Int, sample: BenchmarkSample) -> Unit,
  ): List<BenchmarkSample> {
    // Fail fast rather than queue behind another benchmark, as the server's busy guard does.
    check(inFlight.tryLock()) { "A benchmark is already running" }
    // One scratch dir for the whole set, deleted once at the end: later runs reuse what the
    // first run wrote there, which is what the first-init vs steady-init split measures.
    val scratchDir = File(context.cacheDir, "benchmark_${System.currentTimeMillis()}")
    val ownScratch = scratchDir.mkdirs()
    val cacheDirPath = if (ownScratch) scratchDir.absolutePath else context.cacheDir.absolutePath
    try {
      check(!TurnUsageStore.isInFlight(model.name)) {
        "Cannot benchmark ${model.name} while it is generating. Wait for the reply to finish."
      }
      evictLoadedCopy(model)
      val samples = mutableListOf<BenchmarkSample>()
      for (i in 0 until runCount) {
        Log.d(TAG, "benchmark run ${i + 1}/$runCount for '${model.name}'")
        val sample = runOne(context, model, spec, cacheDirPath)
        samples.add(sample)
        onSample(i, sample)
      }
      return samples
    } finally {
      if (ownScratch) scratchDir.deleteRecursively()
      inFlight.unlock()
    }
  }

  /** One sample. Each engine loads and releases the model itself, so init time is real. */
  suspend fun runOne(
    context: Context,
    model: Model,
    spec: BenchmarkSpec,
    cacheDirPath: String,
  ): BenchmarkSample =
    when (val engine = model.engineFor(taskId = null)) {
      ModelEngine.LiteRtLm -> benchmarkLiteRtLm(context, model, spec, cacheDirPath)
      ModelEngine.LlamaCpp -> benchmarkLlamaCpp(context, model, spec)
      else -> throw UnsupportedOperationException("No benchmark path for $engine")
    }

  // A private engine, never the chat one: benchModel wipes the KV cache, and a GGUF conversation
  // cannot be reset afterwards. Init is timed here because native load precedes benchModel.
  private suspend fun benchmarkLlamaCpp(
    context: Context,
    model: Model,
    spec: BenchmarkSpec,
  ): BenchmarkSample =
    withContext(Dispatchers.Default) {
      val engine = LlamaCppEngine()
      val params =
        SmolLM.InferenceParams(
          // n_batch is tied to the context size, so the prefill must fit inside it.
          contextSize = (spec.prefillTokens + spec.decodeTokens + 64).coerceAtLeast(2048).toLong(),
          numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
          storeChats = false,
        )
      val loadStart = System.nanoTime()
      // If cancelled while the native load was in flight, the finally block below already
      // unwound -- unload here or the engine leaks.
      suspendCancellableCoroutine { cont ->
        engine.loadModel(
          modelPath = model.getPath(context = context),
          params = params,
          onSuccess = { if (cont.isActive) cont.resume(Unit) else engine.unloadModel() },
          onError = { e -> if (cont.isActive) cont.resumeWithException(e) else engine.unloadModel() },
        )
      }
      val initSeconds = (System.nanoTime() - loadStart) / 1e9
      try {
        val r = engine.benchModel(pp = spec.prefillTokens, tg = spec.decodeTokens)
        BenchmarkSample(
          initTimeSeconds = initSeconds,
          timeToFirstTokenSeconds = r.prefillSeconds,
          prefillTokenCount = spec.prefillTokens,
          decodeTokenCount = spec.decodeTokens,
          prefillTokensPerSecond = r.prefillTokensPerSecond,
          decodeTokensPerSecond = r.decodeTokensPerSecond,
        )
      } finally {
        engine.unloadModel()
      }
    }

  // Benchmarking loads its own copy from disk, so a resident one would double peak memory.
  private suspend fun evictLoadedCopy(model: Model) {
    if (model.instance == null) return
    Log.d(TAG, "evicting resident '${model.name}' before benchmarking")
    suspendCancellableCoroutine { cont ->
      model.runtimeHelper.cleanUp(model) { cont.resumeWith(Result.success(Unit)) }
    }
  }

  @OptIn(ExperimentalApi::class)
  private suspend fun benchmarkLiteRtLm(
    context: Context,
    model: Model,
    spec: BenchmarkSpec,
    cacheDirPath: String,
  ): BenchmarkSample =
    withContext(Dispatchers.Default) {
      val info =
        benchmark(
          modelPath = model.getPath(context = context),
          backend = backendFor(context, spec.accelerator),
          prefillTokens = spec.prefillTokens,
          decodeTokens = spec.decodeTokens,
          cacheDir = cacheDirPath,
        )
      BenchmarkSample(
        initTimeSeconds = info.initTimeInSecond,
        timeToFirstTokenSeconds = info.timeToFirstTokenInSecond,
        prefillTokenCount = info.lastPrefillTokenCount,
        decodeTokenCount = info.lastDecodeTokenCount,
        prefillTokensPerSecond = info.lastPrefillTokensPerSecond,
        decodeTokensPerSecond = info.lastDecodeTokensPerSecond,
      )
    }

  private fun backendFor(context: Context, accelerator: String): Backend =
    when (accelerator.lowercase()) {
      "gpu" -> Backend.GPU()
      "npu",
      "tpu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
      else -> Backend.CPU()
    }
}
