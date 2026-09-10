// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.discovery

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.huggingface.heuristics.HfCapability
import com.google.ai.edge.gallery.huggingface.heuristics.HfModelType
import com.google.ai.edge.gallery.huggingface.heuristics.capabilityForTaskId
import com.google.ai.edge.gallery.huggingface.heuristics.resolveType
import com.google.ai.edge.gallery.huggingface.sortModels
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.HfSortOptionProto
import com.google.ai.edge.gallery.relay.model.HfCardDescriptions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGDiscoveryVM"

data class DiscoveryUiState(
  val query: String = "",
  val sort: HfSortOptionProto = HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS,
  val typeFilter: HfModelType? = null,
  val results: List<HfModelItemProto> = emptyList(),
  val isLoading: Boolean = false,
  val error: String? = null,
  val taskId: String? = null,
  val capability: HfCapability? = null,
)

/** Owns Discovery's search/sort state; [DiscoveryScreen] only renders it. */
@HiltViewModel
class DiscoveryViewModel
@Inject
constructor(
  private val huggingFaceApiClient: HuggingFaceApiClient,
  private val dataStoreRepository: DataStoreRepository,
  private val hfCardDescriptions: HfCardDescriptions,
) : ViewModel() {

  private val _uiState = MutableStateFlow(DiscoveryUiState())
  val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()


  // Last search()'s results, pre-sort/filter. Re-sliced by applySortAndFilter() on every sort or
  // type-filter change without hitting the network.
  private var fittingResults: List<HfModelItemProto> = emptyList()

  init {
    search()
  }

  fun onQueryChange(query: String) {
    _uiState.update { it.copy(query = query) }
  }

  /** Scopes results to [taskId]'s capability, best-effort; null unscopes (the escape hatch). */
  fun setTaskScope(taskId: String?) {
    if (taskId == _uiState.value.taskId) return
    _uiState.update { it.copy(taskId = taskId, capability = taskId?.let(::capabilityForTaskId)) }
  }

  fun onSortChange(sort: HfSortOptionProto) {
    if (sort == _uiState.value.sort) return
    _uiState.update { it.copy(sort = sort) }
    applySortAndFilter()
  }

  fun onTypeFilterChange(type: HfModelType?) {
    if (type == _uiState.value.typeFilter) return
    _uiState.update { it.copy(typeFilter = type) }
    applySortAndFilter()
  }

  fun search() {
    val query = _uiState.value.query
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, error = null) }
      try {
        val accessToken = dataStoreRepository.readAccessTokenData()?.accessToken
        val fetched = huggingFaceApiClient.fetchModels(query = query, accessToken = accessToken)
        // Size is not filtered here: the half-RAM heuristic hides models that run fine (GGUF is
        // mmapped), and per-model detail fetches to get sizes made every search slow.
        fittingResults = fetched
        Log.i(TAG, "Discovery query='$query' results=${fittingResults.size}")
        applySortAndFilter()
      } catch (e: Exception) {
        Log.e(TAG, "Discovery search failed for query '$query'", e)
        _uiState.update { it.copy(isLoading = false, error = e.message) }
      }
    }
  }

  private fun applySortAndFilter() {
    val state = _uiState.value
    val typeFiltered =
      state.typeFilter?.let { type -> fittingResults.filter { it.resolveType() == type } }
        ?: fittingResults
    val sorted = sortModels(typeFiltered, state.sort)
    _uiState.update { it.copy(results = sorted, isLoading = false) }
  }

  fun loadDetails(modelId: String, onResult: (HfModelItemProto?) -> Unit) {
    viewModelScope.launch {
      try {
        val accessToken = dataStoreRepository.readAccessTokenData()?.accessToken
        val details = huggingFaceApiClient.getModelDetails(modelId, accessToken = accessToken)
        onResult(details)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load model details for $modelId", e)
        onResult(null)
      }
    }
  }

  /** Fetches [modelId]'s description; null on any failure -- a missing description is quiet. */
  fun loadDescription(modelId: String, onResult: (String?) -> Unit) {
    viewModelScope.launch {
      try {
        val description = hfCardDescriptions.ensureDescription(modelId)
        onResult(description)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load description for $modelId", e)
        onResult(null)
      }
    }
  }
}
