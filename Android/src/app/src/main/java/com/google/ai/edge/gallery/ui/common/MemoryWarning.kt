/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.common

import android.content.Context
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.capability.DeviceProfileEntryPoint
import dagger.hilt.android.EntryPointAccessors

private const val TAG = "AGMemoryWarning"
private const val BYTES_IN_GB = 1024f * 1024 * 1024

/** Composable function to display a memory warning alert dialog. */
@Composable
fun MemoryWarningAlert(onProceeded: () -> Unit, onDismissed: () -> Unit) {
  AlertDialog(
    title = { Text(stringResource(R.string.memory_warning_title)) },
    text = { Text(stringResource(R.string.memory_warning_content)) },
    onDismissRequest = onDismissed,
    confirmButton = {
      TextButton(onClick = onProceeded) {
        Text(stringResource(R.string.memory_warning_proceed_anyway))
      }
    },
    dismissButton = { TextButton(onClick = onDismissed) { Text(stringResource(R.string.cancel)) } },
  )
}

/** Checks if the device's memory is lower than the required minimum for the given model. */
fun isMemoryLow(context: Context, model: Model): Boolean {
  val minDeviceMemoryInGb = model.minDeviceMemoryInGb ?: return false
  val deviceProfile =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        DeviceProfileEntryPoint::class.java,
      )
      .deviceProfile()
  val deviceMemInGb = deviceProfile.totalRamBytes() / BYTES_IN_GB
  Log.d(
    TAG,
    "Device memory (GB): $deviceMemInGb. " +
      "Model's required min device memory (GB): $minDeviceMemoryInGb.",
  )
  return deviceMemInGb < minDeviceMemoryInGb
}
