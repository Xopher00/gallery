// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.discovery

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.huggingface.modelName
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.HfSortOptionProto
import com.google.ai.edge.gallery.huggingface.heuristics.HfCapability
import com.google.ai.edge.gallery.huggingface.heuristics.HfModelFormat
import com.google.ai.edge.gallery.huggingface.heuristics.HfModelType
import com.google.ai.edge.gallery.huggingface.heuristics.isKnownPublisher
import com.google.ai.edge.gallery.huggingface.heuristics.matchesCapability
import com.google.ai.edge.gallery.huggingface.heuristics.primaryFormat
import com.google.ai.edge.gallery.huggingface.heuristics.resolveType
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.modelmanager.HfModelDetailsSheet
import com.google.ai.edge.gallery.ui.modelmanager.ModelStatsRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
  navigateUp: () -> Unit,
  onImportUrl: (encodedUrl: String, isImageGen: Boolean) -> Unit,
  modifier: Modifier = Modifier,
  taskId: String? = null,
  viewModel: DiscoveryViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  LaunchedEffect(taskId) { viewModel.setTaskScope(taskId) }

  var selectedModelForDetails by remember { mutableStateOf<HfModelItemProto?>(null) }
  var showModelDetailsSheet by remember { mutableStateOf(false) }
  var loadingDetailsForId by remember { mutableStateOf<String?>(null) }

  Scaffold(
    modifier = modifier,
    topBar = {
      GalleryTopAppBar(
        title = stringResource(R.string.discovery_title),
        leftAction = AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = navigateUp),
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      OutlinedTextField(
        value = uiState.query,
        onValueChange = viewModel::onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.discovery_search_placeholder)) },
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
        singleLine = true,
        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      )

      Row(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(stringResource(R.string.discovery_sort_label))
        FilterChip(
          selected = uiState.sort == HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS,
          onClick = { viewModel.onSortChange(HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS) },
          label = { Text(stringResource(R.string.discovery_sort_downloads)) },
        )
        FilterChip(
          selected = uiState.sort == HfSortOptionProto.HF_SORT_OPTION_LIKES,
          onClick = { viewModel.onSortChange(HfSortOptionProto.HF_SORT_OPTION_LIKES) },
          label = { Text(stringResource(R.string.discovery_sort_likes)) },
        )
        FilterChip(
          selected = uiState.sort == HfSortOptionProto.HF_SORT_OPTION_RECENT,
          onClick = { viewModel.onSortChange(HfSortOptionProto.HF_SORT_OPTION_RECENT) },
          label = { Text(stringResource(R.string.discovery_sort_recent)) },
        )
      }

      Row(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(stringResource(R.string.discovery_type_label))
        FilterChip(
          selected = uiState.typeFilter == null,
          onClick = { viewModel.onTypeFilterChange(null) },
          label = { Text(stringResource(R.string.discovery_type_all)) },
        )
        FilterChip(
          selected = uiState.typeFilter == HfModelType.TEXT,
          onClick = { viewModel.onTypeFilterChange(HfModelType.TEXT) },
          label = { Text(stringResource(R.string.discovery_type_text)) },
        )
        FilterChip(
          selected = uiState.typeFilter == HfModelType.VISION,
          onClick = { viewModel.onTypeFilterChange(HfModelType.VISION) },
          label = { Text(stringResource(R.string.discovery_type_vision)) },
        )
        FilterChip(
          selected = uiState.typeFilter == HfModelType.SPEECH,
          onClick = { viewModel.onTypeFilterChange(HfModelType.SPEECH) },
          label = { Text(stringResource(R.string.discovery_type_speech)) },
        )
        FilterChip(
          selected = uiState.typeFilter == HfModelType.IMAGE_GEN,
          onClick = { viewModel.onTypeFilterChange(HfModelType.IMAGE_GEN) },
          label = { Text(stringResource(R.string.discovery_type_image_gen)) },
        )
        FilterChip(
          selected = uiState.typeFilter == HfModelType.UNKNOWN,
          onClick = { viewModel.onTypeFilterChange(HfModelType.UNKNOWN) },
          label = { Text(stringResource(R.string.discovery_type_unknown)) },
        )
      }

      Box(modifier = Modifier.fillMaxSize()) {
        when {
          uiState.isLoading -> {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
          }
          uiState.error != null -> {
            Text(
              text = stringResource(R.string.discovery_error),
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
          }
          uiState.results.isEmpty() -> {
            Text(
              text = stringResource(R.string.discovery_empty_results),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
          }
          else -> {
            val capability = uiState.capability
            val (matched, rest) =
              if (capability == null) emptyList<HfModelItemProto>() to uiState.results
              else uiState.results.partition { it.matchesCapability(capability) }

            val resultRow: @Composable (HfModelItemProto) -> Unit = { model ->
              DiscoveryResultRow(
                model = model,
                isLoadingDetails = loadingDetailsForId == model.id,
                onClick = {
                  loadingDetailsForId = model.id
                  viewModel.loadDetails(model.id) { details ->
                    loadingDetailsForId = null
                    if (details != null) {
                      selectedModelForDetails = details
                      showModelDetailsSheet = true
                    }
                  }
                },
                onLoadCard = viewModel::loadModelCard,
              )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
              if (capability != null && matched.isNotEmpty()) {
                item { SectionHeader(text = capabilityHintText(capability)) }
                items(matched, key = { "match_${it.id}" }) { resultRow(it) }
                if (rest.isNotEmpty()) {
                  item { SectionHeader(text = stringResource(R.string.discovery_capability_other_header)) }
                }
              }
              items(rest, key = { "rest_${it.id}" }) { resultRow(it) }
              if (uiState.taskId != null) {
                item {
                  TextButton(
                    onClick = { viewModel.setTaskScope(null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                  ) {
                    Text(stringResource(R.string.discovery_show_all_models))
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  if (showModelDetailsSheet && selectedModelForDetails != null) {
    HfModelDetailsSheet(
      modelItem = selectedModelForDetails!!,
      onDismiss = {
        showModelDetailsSheet = false
        selectedModelForDetails = null
      },
      onImportModelFile = { modelId, fileName ->
        // Captured before nulling selectedModelForDetails below.
        val isImageGen = selectedModelForDetails?.resolveType() == HfModelType.IMAGE_GEN
        showModelDetailsSheet = false
        selectedModelForDetails = null
        val fileUrl = "https://huggingface.co/$modelId/resolve/main/$fileName?download=true"
        onImportUrl(Uri.encode(fileUrl), isImageGen)
      },
    )
  }
}

@Composable
private fun capabilityHintText(capability: HfCapability): String =
  when (capability) {
    HfCapability.TEXT -> stringResource(R.string.discovery_capability_hint_text)
    HfCapability.IMAGE -> stringResource(R.string.discovery_capability_hint_image)
    HfCapability.AUDIO -> stringResource(R.string.discovery_capability_hint_audio)
  }

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  )
}

@Composable
private fun DiscoveryResultRow(
  model: HfModelItemProto,
  isLoadingDetails: Boolean,
  onClick: () -> Unit,
  onLoadCard: (modelId: String, onResult: (String?) -> Unit) -> Unit,
  modifier: Modifier = Modifier,
) {
  var cardExpanded by remember(model.id) { mutableStateOf(false) }
  var isLoadingCard by remember(model.id) { mutableStateOf(false) }
  var cardText by remember(model.id) { mutableStateOf<String?>(null) }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier =
      modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable {
        if (!isLoadingDetails) onClick()
      },
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = model.modelName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (model.isKnownPublisher()) {
              Spacer(modifier = Modifier.padding(start = 6.dp))
              Icon(
                imageVector = Icons.Rounded.Verified,
                contentDescription = stringResource(R.string.discovery_known_publisher_badge),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 2.dp),
              )
            }
          }
          if (model.author.isNotEmpty()) {
            Text(
              text = model.author,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          model.primaryFormat()?.let { format -> FormatChip(format) }
          Spacer(modifier = Modifier.padding(top = 4.dp))
          ModelStatsRow(
            downloads = model.downloads,
            likes = model.likes,
            lastModified = model.lastModified,
          )
        }
        if (isLoadingDetails) {
          CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(20.dp))
        }
        IconButton(
          onClick = {
            cardExpanded = !cardExpanded
            if (cardExpanded && cardText == null && !isLoadingCard) {
              isLoadingCard = true
              onLoadCard(model.id) { result ->
                isLoadingCard = false
                cardText = result
              }
            }
          }
        ) {
          Icon(
            imageVector = if (cardExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription =
              stringResource(
                if (cardExpanded) R.string.discovery_model_card_collapse
                else R.string.discovery_model_card_expand
              ),
          )
        }
      }
      if (cardExpanded) {
        when {
          isLoadingCard -> {
            CircularProgressIndicator(
              modifier = Modifier.padding(top = 8.dp).size(20.dp)
            )
          }
          !cardText.isNullOrBlank() -> {
            MarkdownText(
              text = cardText!!,
              smallFontSize = true,
              modifier =
                Modifier.fillMaxWidth()
                  .heightIn(max = 200.dp)
                  .verticalScroll(rememberScrollState())
                  .padding(top = 8.dp),
            )
          }
          // Fetch failed or the repo has no README -- quiet, not an error dialog.
        }
      }
    }
  }
}

@Composable
private fun FormatChip(format: HfModelFormat, modifier: Modifier = Modifier) {
  val label =
    when (format) {
      HfModelFormat.LITERT -> stringResource(R.string.discovery_format_litert)
      HfModelFormat.GGUF -> stringResource(R.string.discovery_format_gguf)
    }
  Surface(
    shape = RoundedCornerShape(4.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = modifier.padding(top = 2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}
