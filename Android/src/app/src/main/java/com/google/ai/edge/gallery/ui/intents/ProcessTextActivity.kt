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
package com.google.ai.edge.gallery.ui.intents

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.modelmanager.ModelRegistry
import com.google.ai.edge.gallery.modelmanager.ModelRegistryEntryPoint
import com.google.ai.edge.gallery.openai.handlers.collectInferenceText
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
  NO_MODELS_REGISTERED, // ModelRegistry.getAllModels() is empty: no Activity has loaded the
                        // model allowlist into the registry yet in this process.
  NO_MODEL, // The registry knows about models, but none of them is initialized/loaded.
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
  // Re-evaluated fresh on every composition of this one-shot dialog (no caching/latching
  // anywhere), so a registry that is momentarily empty right after a cold process start -- e.g.
  // this Activity races a just-launched MainActivity's loadModelAllowlist() call -- shows
  // NO_MODELS_REGISTERED for this invocation only, never a permanent state: the next time the
  // user invokes this surface (after the allowlist has loaded, or a model has been initialized),
  // the same read observes the updated registry and resolves to NO_MODEL or READY as
  // appropriate. This mirrors the same accepted false-negative OpenAiServerService.onStartCommand
  // documents for its identical check.
  val allModels = modelRegistry.getAllModels()

  // There is no "selected model" concept without ModelManagerViewModel/UI state (that was
  // Activity-side, per-session UI selection) -- picking any already-initialized model is the
  // closest equivalent of this Activity's original intent ("reuse the model the user already
  // loaded"), and is unaffected by which registry-readiness predicate gates NO_MODELS_REGISTERED
  // above.
  val model: Model? =
    allModels.firstOrNull { it.initStatusFlow.value is Model.InitializationStatus.Initialized }
  val modelReady = model != null && model.name.isNotEmpty()

  val trimmed = rawText?.trim().orEmpty()
  val truncated = trimmed.length > MAX_INPUT_CHARS
  val displayInput = if (truncated) trimmed.take(MAX_INPUT_CHARS) else trimmed

  val phase =
    when {
      allModels.isEmpty() -> Phase.NO_MODELS_REGISTERED
      !modelReady -> Phase.NO_MODEL
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
          Phase.NO_MODELS_REGISTERED,
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
