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
import com.google.ai.edge.gallery.security.AppLockManager
import com.google.ai.edge.gallery.security.BiometricEncryptionManager
import com.google.ai.edge.gallery.security.OfflineMode
import com.google.ai.edge.gallery.security.SecurityAuditLog
import com.google.ai.edge.gallery.security.SecurityUtils
import com.google.ai.edge.gallery.security.SignatureVerifier
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    firebaseAnalytics?.setAnalyticsCollectionEnabled(dataStoreRepository.readFirebaseAnalytics())

    // Box: restore the saved offline-mode preference. Without this the toggle silently resets to
    // off on every process start. Box called it from its own GalleryApplication, which the merge
    // deleted along with its package.
    OfflineMode.init(this)
    // Box: load the persisted lock/screenshot prefs; without this they reset on every restart.
    AppLockManager.init(this)
    // Box: load the persisted DB-encryption-enabled flag into isEnabledFlow. Without this,
    // isEnabledFlow starts false on every process start regardless of the SharedPrefs value
    // (it is otherwise only ever flipped by storeEncryptedPassphrase()/disable(), both called
    // from the Settings screen), so the startup decrypt gate below (MainActivity) would think
    // encryption is off and let the UI straight through to a database that getDatabasePassphrase()
    // will still refuse to open.
    BiometricEncryptionManager.init(this)
    // Box: warm the non-biometric database-passphrase cache off the main thread so the first
    // chat-history open after a cold start doesn't pay for a synchronous StrongBox unwrap on
    // the UI thread (measured ~1s / 162 skipped frames at 120Hz). Skipped, silently, when
    // biometric DB encryption is on -- there the plain passphrase legitimately doesn't exist
    // until the user authenticates, and this must never trigger a prompt or log an error.
    CoroutineScope(Dispatchers.IO).launch {
      SecurityUtils.warmPassphraseCache(this@GalleryApplication)
    }
    // Box: report-only repackaging/re-signing check. Debug/CI builds carry no expected
    // digest (see BuildConfig.TRUSTED_SIGNING_CERT_SHA256) and log "not configured", not
    // a mismatch. This never blocks startup or disables anything.
    SignatureVerifier(this).checkAndLog()
    SecurityAuditLog.log(this, "APPLICATION_CREATED")
  }
}
