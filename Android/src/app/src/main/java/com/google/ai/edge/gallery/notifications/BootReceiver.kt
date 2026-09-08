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
package com.google.ai.edge.gallery.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepositoryEntryPoint
import com.google.ai.edge.gallery.relay.modelmanager.ModelRegistryEntryPoint
import com.google.ai.edge.gallery.openai.OpenAiServerService
import com.google.ai.edge.gallery.openai.ServerRuntime
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reschedules all notifications after the device boots up, and optionally starts
 * [OpenAiServerService] and preloads the last-pinned model, if the user has opted into "Start
 * server on boot" (default OFF; see [DataStoreRepositoryEntryPoint]).
 *
 * This receiver is triggered by the ACTION_BOOT_COMPLETED broadcast.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      Log.d(TAG, "Boot completed received, rescheduling notifications")
      try {
        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        entryPoint.notificationScheduleManager().rescheduleAllNotifications()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to reschedule notifications on boot", e)
      }

      // Boot auto-start of the API server -- opt-in, defaults to false. This whole branch is
      // wrapped so that any failure here (Hilt not ready, DataStore read failure, preload
      // throwing) is caught and logged, never left to propagate out of onReceive().
      try {
        val dataStoreRepository =
          EntryPointAccessors.fromApplication(
              context.applicationContext,
              DataStoreRepositoryEntryPoint::class.java,
            )
            .dataStoreRepository()

        if (!dataStoreRepository.readServerStartOnBoot()) {
          Log.d(TAG, "startServerOnBoot is off; not starting the API server")
        } else {
          Log.i(TAG, "startServerOnBoot is on; loading the model allowlist and starting the server")
          val modelRegistry =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ModelRegistryEntryPoint::class.java,
              )
              .modelRegistry()

          // goAsync() keeps this receiver's process alive briefly past onReceive() returning,
          // for the async allowlist load + service start + best-effort preload below.
          // pendingResult.finish() is called from every exit path (including the catch block)
          // so the system is never left waiting on it.
          val pendingResult = goAsync()
          val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
          bootScope.launch {
            try {
              val allowlistDone = CompletableDeferred<Unit>()
              modelRegistry.loadModelAllowlist(
                onDone = { allowlistDone.complete(Unit) },
                onError = { err ->
                  Log.w(TAG, "loadModelAllowlist() on boot reported an error (continuing): $err")
                  allowlistDone.complete(Unit)
                },
              )
              allowlistDone.await()

              modelRegistry.restoreImportedModels()

              OpenAiServerService.startService(context.applicationContext)

              // Await ServerRuntime reaching READY rather than racing the service's own startup.
              val (name, accelerator) = dataStoreRepository.readServerLastPinned()
              if (name != null) {
                val server = ServerRuntime.awaitReady(
                  timeoutMs = BOOT_PRELOAD_MAX_ATTEMPTS * BOOT_PRELOAD_POLL_INTERVAL_MS,
                )
                if (server == null) {
                  Log.w(
                    TAG,
                    "Boot preload: server never came up within the poll budget; skipping preload of '$name'",
                  )
                } else {
                  val result = server.loadModel(name, accelerator)
                  Log.i(TAG, "Boot preload of '$name' (accelerator=$accelerator) -> $result")
                }
              } else {
                Log.d(TAG, "Boot preload: no last-pinned model recorded; nothing to preload")
              }
            } catch (e: Exception) {
              Log.e(TAG, "Failed to start API server / preload model on boot", e)
            } finally {
              pendingResult.finish()
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to read startServerOnBoot preference on boot", e)
      }
    }
  }

  companion object {
    private const val TAG = "BootReceiver"
    private const val BOOT_PRELOAD_MAX_ATTEMPTS = 25
    private const val BOOT_PRELOAD_POLL_INTERVAL_MS = 200L
  }
}
