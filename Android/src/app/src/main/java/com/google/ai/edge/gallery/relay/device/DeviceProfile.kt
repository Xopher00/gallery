// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// No NPU/accelerator field: no unprivileged API can answer that; only attempting the load can.
@Singleton
class DeviceProfile
@Inject
constructor(@ApplicationContext private val context: Context) {

  // Constant for the process: computed once and cached.
  val socManufacturer: String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null

  val socModel: String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

  val supportedAbis: Array<String> = Build.SUPPORTED_ABIS

  val sdkInt: Int = Build.VERSION.SDK_INT

  // RAM/storage change constantly: sampled fresh on every call, never cached.
  fun totalRamBytes(): Long {
    val memoryInfo = readMemoryInfo()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (memoryInfo.advertisedMem != 0L) memoryInfo.advertisedMem else memoryInfo.totalMem
    } else {
      memoryInfo.totalMem
    }
  }

  fun availableRamBytes(): Long = readMemoryInfo().availMem

  fun freeStorageBytes(): Long = StatFs(context.filesDir.path).availableBytes

  private fun readMemoryInfo(): ActivityManager.MemoryInfo {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    return memoryInfo
  }
}

// Mirrors ModelRegistryEntryPoint (relay/modelmanager/ModelRegistry.kt) -- the precedent in this
// tree for reaching a @Singleton from composables/plain functions with no Hilt scaffolding.
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface DeviceProfileEntryPoint {
  fun deviceProfile(): DeviceProfile
}
