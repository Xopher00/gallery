// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * Makes the on-device model reachable from the rest of Android without opening the app first,
 * via ACTION_PROCESS_TEXT (selection menu "Ask Box") and ACTION_SEND (share sheet).
 *
 * Deliberately not a Hilt entry point: a fresh `by viewModels()` ModelManagerViewModel here
 * would start with an empty model list, not the one already loaded from MainActivity. Instead
 * this reaches the process-scoped ModelRegistry singleton directly via EntryPointAccessors.
 */
package com.google.ai.edge.gallery.intents

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
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.model.ModelRegistryEntryPoint
import com.google.ai.edge.gallery.relay.server.handlers.collectInferenceText
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
  NO_MODELS_REGISTERED, // Registry came up empty even after this Activity tried to populate it
                        // (allowlist + imported models) -- distinct from NO_MODEL below.
  NO_MODEL, // Models exist, but none is loaded and the on-demand load attempt failed.
  LOADING, // Models exist but none is initialized yet; an on-demand load is in flight.
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
  // State (not `val`) because population runs on a background coroutine below and needs an
  // explicit write to trigger recomposition; may already be non-empty if MainActivity beat us to it.
  var allModels by remember { mutableStateOf(modelRegistry.getAllModels()) }
  // Set once population has been attempted, gating Phase.NO_MODELS_REGISTERED.
  var registryPopulated by remember { mutableStateOf(allModels.isNotEmpty()) }

  // No per-session "selected model" without ModelManagerViewModel; reuse any already-initialized
  // model, falling back to one this Activity loads on demand via the LaunchedEffect below.
  var loadedModel by remember { mutableStateOf<Model?>(null) }
  // True only if the on-demand load actually ran and failed -- distinguishes LOADING from NO_MODEL.
  var loadFailed by remember { mutableStateOf(false) }

  val model: Model? =
    allModels.firstOrNull { it.initStatusFlow.value is Model.InitializationStatus.Initialized }
      ?: loadedModel
  val modelReady = model != null && model.name.isNotEmpty()

  val context = LocalContext.current

  // Populates the registry cold (this is a genuinely cold entry point -- see the class doc),
  // mirroring BootReceiver's boot-triggered path: load the allowlist, then restore imported
  // models, in that order (imports attach to allowlist-built tasks). Single-flight guarded, so a
  // concurrent MainActivity launch is joined rather than double-loaded. Runs off the main thread.
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

    // On-demand load, only if nothing is ready yet -- mirrors ChatHandler's load-on-demand check,
    // via the same underlying ModelRegistry.initializeModel(). Uses `currentModels` (this
    // coroutine's fresh view), not the outer composable's captured vals, which would still see
    // the pre-population empty registry.
    val alreadyInitialized =
      currentModels.firstOrNull { it.initStatusFlow.value is Model.InitializationStatus.Initialized }
    if (alreadyInitialized != null || currentModels.isEmpty()) return@LaunchedEffect
    // Only LLM models (isLlm) that are already downloaded are eligible -- initializing a
    // non-downloaded model here could silently kick off a multi-GB download from a text-selection
    // action. Ties broken by smallest sizeInBytes, then name, so a user waiting on a cold load
    // gets the fastest-initializing candidate rather than an arbitrary first match.
    val target =
      currentModels
        .filter { it.isLlm && modelRegistry.isModelDownloaded(it) }
        .sortedWith(compareBy({ it.downloadInfo.sizeInBytes }, { it.name }))
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
      // Show LOADING until population has run, rather than jumping to NO_MODELS_REGISTERED early.
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
            // Load was attempted and the registry still came up empty -- distinct from NO_MODEL
            // (a specific model's on-demand init failed).
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
      // Let cancellation (e.g. dialog leaving composition mid-generation) propagate normally --
      // don't route it through onError as if it were a generation failure.
      throw e
    } catch (e: Exception) {
      onError(e.message.orEmpty())
    }
  }
}
