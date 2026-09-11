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

package com.google.ai.edge.gallery

import android.app.Application
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.notifications.NotificationScheduleManager
import com.google.ai.edge.gallery.relay.security.AppLockManager
import com.google.ai.edge.gallery.relay.security.OfflineMode
import com.google.ai.edge.gallery.relay.security.SecurityAuditLog
import com.google.ai.edge.gallery.relay.security.SignatureVerifier
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager

  override fun onCreate() {
    super.onCreate()
    // Initialize the notification schedule manager to load the scheduled notifications from the
    // disk.
    notificationScheduleManager.initialize()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    FirebaseApp.initializeApp(this)

    // Box: restore the saved offline-mode preference. Without this the toggle silently resets to
    // off on every process start. Box called it from its own GalleryApplication, which the merge
    // deleted along with its package.
    OfflineMode.init(this)
    // Box: load the persisted lock/screenshot prefs; without this they reset on every restart.
    AppLockManager.init(this)
    // Box: report-only repackaging/re-signing check. Debug/CI builds carry no expected
    // digest (see BuildConfig.TRUSTED_SIGNING_CERT_SHA256) and log "not configured", not
    // a mismatch. This never blocks startup or disables anything.
    SignatureVerifier(this).checkAndLog()
    SecurityAuditLog.log(this, "APPLICATION_CREATED")

    // Box: one-time cleanup after retiring the Room/SQLCipher chat store in favour of the proto
    // session store (D-n, 2026-09-06). Runs once, guarded by a flag in box_settings (kept -- it
    // also holds the app-lock/screenshot prefs). Never allowed to crash startup: proto already
    // holds every conversation, so a cleanup failure just leaves harmless leftover files.
    try {
      val settingsPrefs = getSharedPreferences("box_settings", MODE_PRIVATE)
      if (!settingsPrefs.getBoolean("room_store_cleanup_done", false)) {
        try {
          deleteDatabase("box_chat.db")
        } catch (e: Exception) {
          SecurityAuditLog.log(this, "ROOM_CLEANUP_DELETE_DB_FAILED: ${e.message}")
        }
        for (prefsFile in listOf("box_secure_prefs", "box_db_enc", "box_security")) {
          try {
            deleteSharedPreferences(prefsFile)
          } catch (e: Exception) {
            SecurityAuditLog.log(this, "ROOM_CLEANUP_DELETE_PREFS_FAILED: $prefsFile: ${e.message}")
          }
        }
        settingsPrefs.edit().putBoolean("room_store_cleanup_done", true).apply()
      }
    } catch (e: Exception) {
      SecurityAuditLog.log(this, "ROOM_CLEANUP_FAILED: ${e.message}")
    }
  }
}
