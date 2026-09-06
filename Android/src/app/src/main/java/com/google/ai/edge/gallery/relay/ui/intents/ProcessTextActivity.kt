/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Makes the on-device model reachable from the rest of Android without opening the app first.
 * This single lightweight Activity handles two system entry points:
 *  - ACTION_PROCESS_TEXT: text selected in any app ("Ask Box" in the selection menu).
 *  - ACTION_SEND: text or an image shared from any app's share sheet.
 *
 * Deliberately not a Hilt entry point (@AndroidEntryPoint): a fresh `by viewModels()`
 * ModelManagerViewModel here would be a brand-new instance with an empty model/task list
 * (createEmptyUiState()), not the one the user already loaded a model into from MainActivity.
 * Instead this Activity reaches the process-scoped ModelRegistry @Singleton directly via
 * EntryPointAccessors.fromApplication() (modelmanager/ModelRegistry.kt's
 * ModelRegistryEntryPoint) -- no Activity needs to have run first, and no static handoff is
 * needed. "Is there a usable model" is answered by ModelRegistry.getAllModels().isNotEmpty() --
 * the same predicate OpenAiServerService uses to decide whether it can claim to be running -- so
 * the two surfaces cannot disagree about what "can serve" means.
 *
 * Inference itself goes through collectInferenceText (openai/handlers/InferenceCollectors.kt),
 * which wraps `model.runtimeHelper.runInference(...)` (runtime/ModelHelperExt.kt, read-only) --
 * the same per-runtime dispatch point OpenAiServer's ChatHandler.kt uses -- so LiteRT-LM/
 * llama.cpp/AICore models all work here without duplicating any inference logic.
 */
package com.google.ai.edge.gallery.relay.ui.intents

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.modelmanager.ModelRegistry
import com.google.ai.edge.gallery.relay.modelmanager.ModelRegistryEntryPoint
import com.google.ai.edge.gallery.relay.openai.handlers.collectInferenceText
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "ProcessTextActivity"

/** Hard cap on how much incoming text this surface will ever send to the model in one prompt. */
private const val MAX_INPUT_CHARS = 4000

/** Hard cap on the longer side of a shared image after downscaling, to keep decode/inference cheap. */
private const val MAX_IMAGE_DIMENSION_PX = 1024

private data class QuickAction(val labelResId: Int, val promptPrefix: String)

private val QUICK_ACTIONS =
  listOf(
    QuickAction(R.string.process_text_action_summarize, "Summarize the following:\n\n"),
    QuickAction(R.string.process_text_action_explain, "Explain the following simply:\n\n"),
    QuickAction(
      R.string.process_text_action_translate,
      "Translate the following to English:\n\n",
    ),
    QuickAction(R.string.process_text_action_reply, "Draft a brief reply to the following:\n\n"),
  )

class ProcessTextActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val (rawText, image, editable) = extractInput(intent)

    val modelRegistry = EntryPointAccessors.fromApplication(
      applicationContext,
      ModelRegistryEntryPoint::class.java,
    ).modelRegistry()

    setContent {
      GalleryTheme {
        ProcessTextDialog(
          rawText = rawText,
          image = image,
          editable = editable,
          modelRegistry = modelRegistry,
          onReplace = { result ->
            val resultIntent = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
          },
          onCopy = { text -> copyToClipboard(text) },
          onDismiss = {
            setResult(Activity.RESULT_CANCELED)
            finish()
          },
        )
      }
    }
  }

  private fun copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Box answer", text))
    Toast.makeText(this, getString(R.string.process_text_copied), Toast.LENGTH_SHORT).show()
  }

  /**
   * Reads whichever of PROCESS_TEXT / SEND(text) / SEND(image) triggered this Activity.
   * Returns (text-or-null, image-or-null, isEditableSelection).
   */
  private fun extractInput(intent: Intent?): Triple<String?, Bitmap?, Boolean> {
    if (intent == null) return Triple(null, null, false)
    return when (intent.action) {
      Intent.ACTION_PROCESS_TEXT -> {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        Triple(text, null, !readOnly)
      }
      Intent.ACTION_SEND -> {
        if (intent.type?.startsWith("image/") == true) {
          val uri: Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
              @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
          Triple(null, uri?.let { decodeSampledBitmap(it) }, false)
        } else {
          Triple(intent.getStringExtra(Intent.EXTRA_TEXT), null, false)
        }
      }
      else -> Triple(null, null, false)
    }
  }

  /** Decodes + downsamples a shared image so a large photo can't wedge a small on-device model. */
  private fun decodeSampledBitmap(uri: Uri): Bitmap? {
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
      }
      var sampleSize = 1
      while ((bounds.outWidth / sampleSize) > MAX_IMAGE_DIMENSION_PX ||
        (bounds.outHeight / sampleSize) > MAX_IMAGE_DIMENSION_PX) {
        sampleSize *= 2
      }
      contentResolver.openInputStream(uri)?.use { input ->
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeStream(input, null, opts)
      }
    } catch (e: Exception) {
      null
    }
  }
}

private enum class Phase {
  // Re-keyed off ModelRegistry.getAllModels().isNotEmpty() -- the same predicate
  // OpenAiServerService.onStartCommand uses to decide whether it can claim to be running --
  // rather than "has MainActivity run", which the process-scoped registry makes meaningless.
  NO_MODELS_REGISTERED, // ModelRegistry.getAllModels() is still empty AFTER this Activity itself
                        // attempted to populate it (loadModelAllowlist() + restoreImportedModels(),
                        // mirroring BootReceiver.kt) -- i.e. the population attempt itself came up
                        // empty (no network + no cached catalogue, and nothing imported), not
                        // merely "nothing downloaded yet" (that is NO_MODEL below).
  NO_MODEL, // The registry knows about models, but none of them is initialized/loaded, AND the
            // on-demand load this Activity attempted (see LOADING below) failed.
  LOADING, // The registry knows about models but none is initialized yet -- this Activity is
           // attempting the same on-demand load ChatHandler.kt (:102-108) does when
           // model.instance == null, via ModelRegistry.initializeModel(). Resolves to READY (or
           // IMAGE_UNSUPPORTED/EMPTY_INPUT) on success, or NO_MODEL on failure.
  IMAGE_UNSUPPORTED, // Shared an image, but the loaded model doesn't accept images.
  EMPTY_INPUT, // No usable text or image came in.
  READY,
}

@Composable
private fun ProcessTextDialog(
  rawText: String?,
  image: Bitmap?,
  editable: Boolean,
  modelRegistry: ModelRegistry,
  onReplace: (String) -> Unit,
  onCopy: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  // Held as Compose state (not a plain `val` re-read every composition) because this Activity no
  // longer just OBSERVES the registry -- when it finds the registry empty it POPULATES it itself
  // (see the LaunchedEffect below), and that population happens on a background coroutine, so the
  // UI needs an explicit write to `allModels` to pick up the result and recompose. If the registry
  // was already non-empty at cold-launch (e.g. this Activity races a just-launched MainActivity's
  // own loadModelAllowlist() call, or a previous invocation already loaded it in this process),
  // population is skipped entirely and this is just the initial read, same as before.
  var allModels by remember { mutableStateOf(modelRegistry.getAllModels()) }
  // True once the population attempt below (or the decision that none was needed) has run to
  // completion. Gates Phase.NO_MODELS_REGISTERED so it can only be reached AFTER an attempt was
  // made -- see the Phase enum doc above -- rather than merely reflecting a registry that hasn't
  // been asked to load yet.
  var registryPopulated by remember { mutableStateOf(allModels.isNotEmpty()) }

  // There is no "selected model" concept without ModelManagerViewModel/UI state (that was
  // Activity-side, per-session UI selection) -- picking any already-initialized model is the
  // closest equivalent of this Activity's original intent ("reuse the model the user already
  // loaded"), and is unaffected by which registry-readiness predicate gates NO_MODELS_REGISTERED
  // above.
  // Cold-loaded model, populated by the LaunchedEffect below only when no model was already
  // initialized. Kept separate from the initStatusFlow scan below (rather than relying on
  // recomposition to re-run that scan) so the fast "reuse an already-initialized model" path
  // above never depends on this on-demand load having run at all.
  var loadedModel by remember { mutableStateOf<Model?>(null) }
  // Set only if the on-demand load below actually ran and failed -- distinguishes "still
  // loading" (Phase.LOADING) from "gave up" (Phase.NO_MODEL) even though both are `!modelReady`.
  var loadFailed by remember { mutableStateOf(false) }

  val model: Model? =
    allModels.firstOrNull { it.initStatusFlow.value is Model.InitializationStatus.Initialized }
      ?: loadedModel
  val modelReady = model != null && model.name.isNotEmpty()

  val context = LocalContext.current

  // Cold-process registry population + on-demand load, in that order. Runs once per composition
  // of this one-shot dialog (LaunchedEffect(Unit)), entirely off the main thread, so it cannot
  // block the UI thread -- see the per-call dispatcher notes below.
  //
  // Step 1 -- populate: this Activity is a genuinely cold entry point (see the class-level doc
  // comment), so nothing upstream has necessarily ever called ModelRegistry.loadModelAllowlist()
  // or restoreImportedModels() in this process. Modeled directly on
  // notifications/BootReceiver.kt's boot-triggered server-start path (:87-96), which faces the
  // exact same "no Activity has run" problem for the API server: load the allowlist, await it,
  // then restore any locally-imported models, in that order (imported models attach to tasks
  // built from the allowlist, so allowlist-first order matters). Also matches BootReceiver's
  // callback style: onError only logs and still completes the deferred, because a partial/failed
  // allowlist load (e.g. offline with no cached copy) should not block restoreImportedModels()
  // from contributing whatever locally-imported models it can still find.
  //   loadModelAllowlist(onDone, onError) -- ModelRegistry.kt:420. Single-flight guarded
  //     (allowlistLoadLock / allowlistLoadDeferred, ModelRegistry.kt:174-178): if a concurrent
  //     MainActivity launch is already mid-load, this call becomes a joiner, not a second load --
  //     it awaits the SAME CompletableDeferred and gets the same onDone/onError outcome, so a
  //     race with MainActivity never double-fetches or double-mutates task.models. The call
  //     itself returns immediately (registryScope.launch fires the real work); the actual
  //     fetch/parse runs on Dispatchers.IO (ModelRegistry.kt:437), and onDone/onError are invoked
  //     on Dispatchers.Main (ModelRegistry.kt:454) -- consistent with this being driven from a
  //     Compose LaunchedEffect. Offline mode is respected on this path exactly as it is for every
  //     other loadModelAllowlist() caller: runLoadModelAllowlist() checks OfflineMode.isEnabled
  //     (ModelRegistry.kt:980) before taking the network leg, so this call cannot make a network
  //     fetch happen when the user has offline mode on.
  //   restoreImportedModels() -- ModelRegistry.kt:321. Plain synchronous call (not suspend), same
  //     as BootReceiver's usage; safe to call more than once per process (name-based
  //     addModelIfAbsent guard, ModelRegistry.kt:303).
  // `allModels` (Compose state) is written after both complete so the composable recomposes with
  // the populated registry -- `modelRegistry.getAllModels()` itself doesn't trigger recomposition,
  // only writing this state does. `registryPopulated` is then set unconditionally (attempted, not
  // "succeeded") so Phase.NO_MODELS_REGISTERED (below) can only be reached once this attempt has
  // actually run its course.
  LaunchedEffect(Unit) {
    var currentModels = allModels
    if (currentModels.isEmpty()) {
      val allowlistDone = CompletableDeferred<Unit>()
      modelRegistry.loadModelAllowlist(
        onDone = { allowlistDone.complete(Unit) },
        onError = { err ->
          Log.w(TAG, "loadModelAllowlist() failed (continuing, mirrors BootReceiver): $err")
          allowlistDone.complete(Unit)
        },
      )
      allowlistDone.await()
      modelRegistry.restoreImportedModels()
      currentModels = modelRegistry.getAllModels()
      allModels = currentModels
    }
    registryPopulated = true

    // Step 2 -- on-demand load: the fast path above (any already-initialized model) already
    // covers the warm case, so this only runs when nothing is ready yet. Mirrors ChatHandler.kt's
    // load-on-demand check --
    //   if (model.instance == null) {
    //     val result = loadModel(request.model, request.accelerator)
    //     ...
    //   }
    // -- and, since this Activity has no request.model to resolve and no OpenAiServer instance
    // (with its HTTP-concurrency busy-guards/NPU-pinning) to call loadModel() on, drives the same
    // underlying primitive OpenAiServer.loadModel() itself calls for its CPU-only-engine branch
    // (OpenAiServer.kt's initializeModel/CompletableDeferred pairing): ModelRegistry.initializeModel(),
    // awaited via a CompletableDeferred exactly like that branch does.
    //
    // Recomputed from `currentModels` (this coroutine's fresh, possibly just-populated view)
    // rather than the outer composable's `modelReady`/`allModels` vals -- those were captured at
    // LaunchedEffect-launch time and would still read the pre-population empty registry even
    // after `allModels` (state) was just reassigned above, since a suspend function's local
    // closures aren't recomposition-aware.
    val alreadyInitialized =
      currentModels.firstOrNull { it.initStatusFlow.value is Model.InitializationStatus.Initialized }
    if (alreadyInitialized != null || currentModels.isEmpty()) return@LaunchedEffect
    // Candidate set restricted to models this surface can actually use:
    //  - Model.isLlm (data/Model.kt:157) -- the discriminator ModelAllowlist.kt's toModel()
    //    (:224, from :100's isLlmModel check) sets true for chat/ask-image/ask-audio LLM
    //    allowlist entries and leaves at its `false` default for everything else. Confirmed by
    //    the two non-LLM task modules never passing it: ImageGenTaskModule.kt's sdModel() and
    //    WhisperTaskModule.kt's whisperModel() both build their Model() without an `isLlm`
    //    argument, so their Stable Diffusion / Whisper models stay isLlm == false. This Activity
    //    only ever calls collectInferenceText (an LLM-runtime call), so a non-LLM model is never
    //    a valid target regardless of which task it hangs off.
    //  - modelRegistry.isModelDownloaded(model) (ModelRegistry.kt:559, the public one-argument
    //    member -- not a new file-existence check) -- an allowlisted-but-undownloaded model must
    //    never be silently initialized (that would try to run inference against a file that
    //    isn't there, or worse, could kick off a multi-GB download the user never asked for from
    //    a text-selection action).
    // Deterministic pick when more than one candidate survives both filters: the smallest
    // downloaded candidate by sizeInBytes (ties broken by name), not task order or allowlist
    // order -- this is a cold-process load the user is actively waiting on, so the candidate
    // most likely to finish initializing quickly is preferred over an arbitrary "first" one.
    val target =
      currentModels
        .filter { it.isLlm && modelRegistry.isModelDownloaded(it) }
        .sortedWith(compareBy({ it.sizeInBytes }, { it.name }))
        .firstOrNull()
    if (target == null) {
      // No usable candidate -- do not spin on Phase.LOADING forever waiting for a load that was
      // never going to be attempted.
      loadFailed = true
      return@LaunchedEffect
    }
    val task = modelRegistry.tasks.find { t -> t.models.any { it.name == target.name } }
    if (task == null) {
      loadFailed = true
      return@LaunchedEffect
    }
    val initError = CompletableDeferred<String?>()
    modelRegistry.initializeModel(
      context = context,
      task = task,
      model = target,
      onDone = { initError.complete(null) },
      onError = { err -> initError.complete(err) },
    )
    val error = initError.await()
    if (error == null && target.instance != null) {
      loadedModel = target
    } else {
      loadFailed = true
    }
  }

  val trimmed = rawText?.trim().orEmpty()
  val truncated = trimmed.length > MAX_INPUT_CHARS
  val displayInput = if (truncated) trimmed.take(MAX_INPUT_CHARS) else trimmed

  val phase =
    when {
      // Population hasn't run its course yet (see the LaunchedEffect above) -- keep showing
      // LOADING rather than jumping to NO_MODELS_REGISTERED, which is now reserved for "the
      // population attempt itself came up empty."
      !registryPopulated -> Phase.LOADING
      allModels.isEmpty() -> Phase.NO_MODELS_REGISTERED
      !modelReady && loadFailed -> Phase.NO_MODEL
      !modelReady -> Phase.LOADING
      image != null && model?.llmSupportImage != true -> Phase.IMAGE_UNSUPPORTED
      image == null && displayInput.isBlank() -> Phase.EMPTY_INPUT
      else -> Phase.READY
    }

  var output by remember { mutableStateOf("") }
  var isGenerating by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  // The Activity itself is themed as an Android dialog (see AndroidManifest.xml) so this
  // Surface renders as a floating, wrap-content card over the dimmed caller app, rather than a
  // full-screen launch -- no nested Compose Dialog() window needed on top of that.
  Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
    Column(modifier = Modifier.padding(20.dp).heightIn(max = 520.dp)) {
        Text(text = stringResource(R.string.process_text_label), style = MaterialTheme.typography.titleMedium)

        when (phase) {
          Phase.LOADING -> {
            Column(modifier = Modifier.padding(top = 12.dp)) {
              Text(
                text = stringResource(R.string.process_text_generating),
                style = MaterialTheme.typography.bodyMedium,
              )
              CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
          }
          Phase.NO_MODELS_REGISTERED -> {
            // A load was attempted (registryPopulated is only set after it ran) and the registry
            // still came up empty -- distinct from Phase.NO_MODEL below (a specific model was
            // found and its on-demand init failed). Reuses the same title as NO_MODEL ("No model
            // loaded" is still accurate either way) but with its own message, per CHANGES 3.
            Column(modifier = Modifier.padding(top = 12.dp)) {
              Text(
                text = stringResource(R.string.process_text_no_model_title),
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = stringResource(R.string.process_text_no_models_registered_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
              )
              TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(android.R.string.ok))
              }
            }
          }
          Phase.NO_MODEL -> {
            Column(modifier = Modifier.padding(top = 12.dp)) {
              Text(
                text = stringResource(R.string.process_text_no_model_title),
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = stringResource(R.string.process_text_no_model_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
              )
              TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(android.R.string.ok))
              }
            }
          }
          Phase.IMAGE_UNSUPPORTED -> {
            MessageWithDismiss(
              message = stringResource(R.string.process_text_image_unsupported),
              onDismiss = onDismiss,
            )
          }
          Phase.EMPTY_INPUT -> {
            MessageWithDismiss(
              message = stringResource(R.string.process_text_empty_input),
              onDismiss = onDismiss,
            )
          }
          Phase.READY -> {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
              if (displayInput.isNotEmpty()) {
                Text(
                  text = displayInput,
                  style = MaterialTheme.typography.bodySmall,
                  maxLines = 6,
                  modifier = Modifier.padding(top = 8.dp),
                )
                if (truncated) {
                  Text(
                    text =
                      stringResource(R.string.process_text_truncated_notice, MAX_INPUT_CHARS.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

              if (!isGenerating && output.isEmpty()) {
                Column {
                  for (action in QUICK_ACTIONS) {
                    OutlinedButton(
                      onClick = {
                        isGenerating = true
                        errorText = null
                        runQuickInference(
                          scope = scope,
                          model = model!!,
                          prompt = action.promptPrefix + displayInput,
                          image = image,
                          onPartial = { output = it },
                          onDone = { isGenerating = false },
                          onError = {
                            errorText = it
                            isGenerating = false
                          },
                        )
                      },
                      modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                      Text(stringResource(action.labelResId))
                    }
                  }
                }
              }

              if (isGenerating || output.isNotEmpty()) {
                Text(
                  text = output.ifEmpty { stringResource(R.string.process_text_generating) },
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(top = 8.dp),
                )
                if (isGenerating) {
                  CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
              }

              errorText?.let {
                Text(
                  text = "${stringResource(R.string.process_text_error_prefix)} $it",
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.padding(top = 8.dp),
                )
              }

              if (!isGenerating && output.isNotEmpty()) {
                Row(modifier = Modifier.padding(top = 12.dp)) {
                  OutlinedButton(onClick = { onCopy(output) }) {
                    Text(stringResource(R.string.copy))
                  }
                  if (editable) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onReplace(output) }) {
                      Text(stringResource(R.string.process_text_replace_button))
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

@Composable
private fun MessageWithDismiss(message: String, onDismiss: () -> Unit) {
  Column(modifier = Modifier.padding(top = 12.dp)) {
    Text(text = message, style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) {
      Text(stringResource(android.R.string.ok))
    }
  }
}

private fun runQuickInference(
  scope: CoroutineScope,
  model: Model,
  prompt: String,
  image: Bitmap?,
  onPartial: (String) -> Unit,
  onDone: () -> Unit,
  onError: (String) -> Unit,
) {
  scope.launch {
    val fullPrompt = if (image != null) prompt.ifEmpty { "Describe this image." } else prompt
    try {
      collectInferenceText(
        model = model,
        prompt = fullPrompt,
        images = if (image != null) listOf(image) else emptyList(),
        coroutineScope = scope,
        onPartial = onPartial,
      )
      onDone()
    } catch (e: CancellationException) {
      // The original direct runInference call here had no try/catch at all, so a coroutine
      // cancellation (e.g. this dialog leaving composition mid-generation) simply propagated
      // and nothing further ran. Preserve that: don't route a routine cancellation through
      // onError as if it were a generation failure.
      throw e
    } catch (e: Exception) {
      onError(e.message.orEmpty())
    }
  }
}
