// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.imagegen

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun ImageGenScreen(
    modelManagerViewModel: ModelManagerViewModel,
    viewModel: ImageGenViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val initStatus by selectedModel.initStatusFlow.collectAsState()
    val modelReady = initStatus is Model.InitializationStatus.Initialized
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        viewModel.importSdModel(context, uri) { fileName, fileSize ->
            modelManagerViewModel.addImportedSdModel(fileName, fileSize)
        }
    }

    LaunchedEffect(uiState.imageSaved) {
        if (uiState.imageSaved) {
            snackbarHostState.showSnackbar("Saved to Pictures/LocalDiffusion")
            viewModel.clearSavedFlag()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isImporting) {
            ImportingDialog(progress = uiState.importProgress)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!modelReady) {
                    ModelNotReadyPlaceholder(initStatus)
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            filePicker.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Text("  Import GGUF model", fontWeight = FontWeight.SemiBold)
                    }
                    return@Column
                }

                // Generated image display
                if (uiState.generatedBitmap != null) {
                    GeneratedImageCard(bitmap = uiState.generatedBitmap!!)
                    OutlinedButton(
                        onClick = { viewModel.saveImage(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Text("  Save to Gallery", fontWeight = FontWeight.SemiBold)
                    }
                } else if (!uiState.isGenerating) {
                    EmptyImagePlaceholder()
                }

        // Progress bar during generation
        if (uiState.isGenerating) {
            GenerationProgressCard(
                step = uiState.progressStep,
                total = uiState.progressTotal,
            )
        }

        // Error message
        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Prompt input
        OutlinedTextField(
            value = uiState.prompt,
            onValueChange = viewModel::updatePrompt,
            label = { Text("Prompt") },
            placeholder = { Text("A photorealistic cat on a sofa...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            enabled = !uiState.isGenerating,
        )

        // Negative prompt
        OutlinedTextField(
            value = uiState.negativePrompt,
            onValueChange = viewModel::updateNegativePrompt,
            label = { Text("Negative prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
            enabled = !uiState.isGenerating,
        )

        // Size picker
        SizeSelector(
            selected = uiState.selectedSize,
            onSelect = viewModel::updateSize,
            enabled = !uiState.isGenerating,
        )

        // Steps slider
        SettingSlider(
            label = "Steps",
            value = uiState.steps.toFloat(),
            valueRange = 1f..50f,
            displayValue = uiState.steps.toString(),
            onValueChange = { viewModel.updateSteps(it.toInt()) },
            enabled = !uiState.isGenerating,
        )

        // CFG scale slider
        SettingSlider(
            label = "CFG Scale",
            value = uiState.cfgScale,
            valueRange = 1f..20f,
            displayValue = "%.1f".format(uiState.cfgScale),
            onValueChange = { viewModel.updateCfgScale(it) },
            enabled = !uiState.isGenerating,
        )

        // Generate button
                Button(
                    onClick = { viewModel.generateImage(selectedModel) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isGenerating && uiState.prompt.isNotBlank(),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Text("  Generate", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun GeneratedImageCard(bitmap: Bitmap) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Generated image",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EmptyImagePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Text(
                "Enter a prompt and tap Generate",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun GenerationProgressCard(step: Int, total: Int) {
    val fraction = if (total > 0) step.toFloat() / total else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            if (total > 0) "Step $step / $total" else "Starting...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(4.dp),
        )
    }
}

@Composable
private fun ModelNotReadyPlaceholder(status: Model.InitializationStatus) {
    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status is Model.InitializationStatus.Initializing) {
                CircularProgressIndicator()
                Text(
                    "Loading model...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Download a model to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(displayValue, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}

@Composable
private fun SizeSelector(
    selected: ImageSize,
    onSelect: (ImageSize) -> Unit,
    enabled: Boolean,
) {
    Column {
        Text("Size", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            IMAGE_SIZE_PRESETS.forEachIndexed { index, size ->
                SegmentedButton(
                    selected = size == selected,
                    onClick = { onSelect(size) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = IMAGE_SIZE_PRESETS.size),
                    enabled = enabled,
                    label = { Text(size.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun ImportingDialog(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(64.dp),
            )
            Text(
                "Importing model… ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
