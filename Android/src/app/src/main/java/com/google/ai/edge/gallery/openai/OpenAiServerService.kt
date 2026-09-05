/*
 * Ported from mobile-server (com.server.edge.gallery) into the Gallery API-server fork.
 */
package com.google.ai.edge.gallery.openai

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.modelmanager.ModelRegistryEntryPoint
import com.google.ai.edge.gallery.openai.OpenAiServerState.BindMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AGOpenAiServerService"
private const val CHANNEL_ID = "openai_server_channel"
private const val NOTIFICATION_ID = 1001
private const val ACTION_STOP_SERVER = "com.google.ai.edge.gallery.openai.STOP_SERVER"

/**
 * Foreground service hosting the local OpenAI-compatible API server.
 *
 * Starting the service (via [startService], `am startservice`, or `am start-foreground-service`
 * with no extras) always starts listening - there is no separate "arm" step, matching
 * mobile-server's own always-on-start behavior.
 */
class OpenAiServerService : Service() {

    private var server: OpenAiServer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        val isRunning: StateFlow<Boolean> = OpenAiServerState.isRunning
        val localUrl: StateFlow<String?> = OpenAiServerState.localUrl
        const val EXTRA_OPEN_SERVER_SCREEN = "open_server_screen"

        fun startService(context: Context) {
            val intent = Intent(context, OpenAiServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, OpenAiServerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse escapes dataSync's daily time cap; only declarable/usable API 34+.
            startForeground(
                NOTIFICATION_ID,
                createNotification("Starting server..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Starting server..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Starting server..."))
        }

        val bindMode = OpenAiServerState.loadBindMode(applicationContext)
        OpenAiServerState.loadSelectedInterfaceName(applicationContext)

        serviceScope.launch {
            // bindHost() reflects the persisted bind mode: 127.0.0.1 for LOOPBACK,
            // 0.0.0.0 for LAN. The reported local-url host stays 127.0.0.1 (the
            // loopback address always works for on-device clients); LAN mode's own reachable
            // address is a separate concern for the UI (ServerScreen), not this notification.
            val local = "http://127.0.0.1:${OpenAiServerState.DEFAULT_PORT}"

            try {
                // A mode/interface/API-key change while the server is already running lands
                // here as a re-entrant startService() call (ServerScreen's applyModeChange/
                // applyInterfaceChange/OpenAiServerState.regenerateApiKey). Detect that the live
                // server no longer matches the current prefs and rebind instead of hitting the
                // "already running; skipping restart" no-op below.
                //
                // checkConfig() tells apart a genuine config change (NeedsRebind, handled below)
                // from bindHost() throwing right now -- e.g. INTERFACE mode's selected network is
                // transiently down (BindUnavailable). A transient bind-check failure must not
                // stop an otherwise-healthy running server; it is only logged (bindError is
                // already recorded by bindHost() itself, inside checkConfig()).
                when (val check = server?.let { it.checkConfig(applicationContext) }) {
                    is OpenAiServer.ConfigCheck.NeedsRebind -> {
                        Log.i(TAG, "OpenAI API Server config changed while running; rebinding")
                        server?.stop()
                        server = null
                        OpenAiServerState.setRunning(false)
                        OpenAiServerState.setLiveBoundHost(null)
                    }
                    is OpenAiServer.ConfigCheck.BindUnavailable -> {
                        Log.w(TAG, "OpenAI API Server bind check failed (${check.message}); " +
                            "leaving the currently running server untouched")
                    }
                    OpenAiServer.ConfigCheck.Matches, null -> {
                        // Matches: nothing to do. null: server == null, handled below.
                    }
                }

                if (server == null) {
                    // ModelRegistry is a process-scoped @Singleton (modelmanager/ModelRegistry.kt),
                    // reachable with no Activity having ever run in this process -- mirrors
                    // BootReceiver's existing NotificationScheduleManagerEntryPoint pattern (see
                    // ModelRegistryEntryPoint).
                    val modelRegistry = EntryPointAccessors.fromApplication(
                        applicationContext,
                        ModelRegistryEntryPoint::class.java,
                    ).modelRegistry()

                    // The service must not claim to be running when it structurally cannot
                    // serve. getAllModels().isNotEmpty() is not that check: task.models holds
                    // every curated-catalogue entry regardless of whether its file was ever
                    // downloaded (loadModelAllowlist() adds catalogue models unconditionally, see
                    // modelmanager/ModelRegistry.kt), so a device that has only ever listed the
                    // catalogue -- never downloaded anything -- would still pass an emptiness
                    // check while having nothing it can actually serve. What the service needs is
                    // whether at least one model's file is present on disk right now, which is
                    // exactly what ModelRegistry.isModelDownloaded() (backed by
                    // checkIfModelDownloaded()'s on-disk file check) already answers. This is
                    // true once either loadModelAllowlist() has attached a catalogue model whose
                    // file was previously downloaded, or restoreImportedModels() has attached a
                    // user-imported model (its file exists by construction, since it was imported
                    // from an on-device file) -- both of which BootReceiver now runs before
                    // starting this service on boot, and both of which an Activity runs before a
                    // user ever opens the server screen.
                    if (modelRegistry.getAllModels().any { modelRegistry.isModelDownloaded(it) }) {
                        val newServer = OpenAiServer(applicationContext, modelRegistry)
                        newServer.start(OpenAiServerState.DEFAULT_PORT)
                        server = newServer
                        OpenAiServerState.setLiveBoundHost(newServer.boundHost)
                        Log.i(TAG, "OpenAI API Server started on port ${OpenAiServerState.DEFAULT_PORT} " +
                            "(bind mode: $bindMode)")
                        // setRunning(true) and the "running" notification only fire when a
                        // server is actually up -- see the else branch below for the no-models
                        // case, which previously fell through to this call and lied about being
                        // running.
                        OpenAiServerState.setRunning(true, local = local)
                        updateNotification(notificationText(local))
                    } else {
                        Log.w(TAG, "ModelRegistry has no downloaded/imported models yet; server cannot start")
                        // The service must not claim to be running (no setRunning(true), no
                        // "running" notification) when it structurally cannot serve -- every
                        // model currently attached to a task (if any) has no file on disk, so
                        // there is nothing this server could return for any request. Tell the
                        // user via notification instead of silently sitting there as a dead
                        // foreground service, and stop.
                        updateNotification(
                            "Server unavailable: no downloaded or imported model found. Open " +
                                "the app and download or import a model, then retry starting " +
                                "the server."
                        )
                        OpenAiServerState.setRunning(false)
                        OpenAiServerState.setLiveBoundHost(null)
                        stopSelf()
                    }
                } else {
                    Log.d(TAG, "OpenAI API Server already running; skipping restart")
                    OpenAiServerState.setRunning(true, local = local)
                    updateNotification(notificationText(local))
                }
            } catch (e: Exception) {
                // bindHost() (called inside server.start()) throws rather than widening the bind
                // scope when INTERFACE mode has no usable interface selected -- see
                // OpenAiServerState.bindHost(). That is correct and must not change. Left
                // uncaught, this coroutine has no CoroutineExceptionHandler, so the exception
                // would kill the process, and since the service is START_STICKY, Android would
                // relaunch it straight back into the same failure -- a crash loop. Surface the
                // reason via bindError (ServerScreen reads this) and the notification, then stop
                // cleanly instead of letting START_STICKY restart into the same crash.
                val message = e.message ?: "Server failed to start"
                Log.e(TAG, "OpenAI API Server failed to start (bind mode: $bindMode): $message", e)
                server?.stop()
                server = null
                OpenAiServerState.refreshBindError()
                OpenAiServerState.setRunning(false)
                OpenAiServerState.setLiveBoundHost(null)
                updateNotification("Server not started: $message")
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // stop() is suspend (it blocks up to 2s on IO -- see OpenAiServer.stop()), and
        // onDestroy() is a plain Service lifecycle callback with no suspend context of its own.
        // serviceScope is cancelled immediately below, so a stop launched inside it would never
        // run -- it is fired on a short-lived scope instead. Nothing after onDestroy() depends
        // on the stop having completed (the service is already tearing down), so this is
        // deliberately fire-and-forget rather than awaited.
        val stoppingServer = server
        server = null
        serviceScope.cancel()
        OpenAiServerState.setRunning(false)
        OpenAiServerState.setLiveBoundHost(null)
        Log.i(TAG, "OpenAI API Server Service stopped")
        if (stoppingServer != null) {
            CoroutineScope(Dispatchers.IO).launch { stoppingServer.stop() }
        }
    }

    private fun notificationText(local: String): String {
        val bindMode = OpenAiServerState.bindMode.value
        return "Server running ($bindMode) at $local"
    }

    /**
     * Called when the system's time limit for this foreground service type
     * (`dataSync`/`specialUse`) is reached (API 34+). We must stop promptly or the system
     * throws `ForegroundServiceDidNotStopInTimeException` and kills the app.
     *
     * Two overloads exist at compileSdk 37: the 1-arg version (added API 34,
     * used for dataSync/mediaProcessing) and the 2-arg version (added API 35,
     * generalized across FGS types). minSdk is 33, so both overrides
     * are @RequiresApi-annotated; the platform only ever invokes whichever one exists
     * at its own API level, and on API 33 neither is invoked (no time limit applies).
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "onTimeout(startId=$startId): foreground service time limit reached")
        handleTimeout()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout(startId=$startId, fgsType=$fgsType): foreground service time limit reached")
        handleTimeout()
    }

    private fun handleTimeout() {
        // Same fire-and-forget-on-IO reasoning as onDestroy() -- onTimeout() is a plain
        // callback (no suspend context) and serviceScope is cancelled below, so a stop queued
        // into it would never run.
        val stoppingServer = server
        server = null
        OpenAiServerState.setRunning(false)
        OpenAiServerState.setLiveBoundHost(null)

        // Detach the notification from the foreground state so it survives as
        // a normal, user-dismissible notification explaining what happened,
        // then tear the service down.
        stopForeground(STOP_FOREGROUND_DETACH)
        postTimeoutNotification()

        serviceScope.cancel()
        stopSelf()
        Log.w(TAG, "OpenAI API Server stopped due to foreground service time limit")
        if (stoppingServer != null) {
            CoroutineScope(Dispatchers.IO).launch { stoppingServer.stop() }
        }
    }

    private fun postTimeoutNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_OPEN_SERVER_SCREEN, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Edge Gallery API Server")
            .setContentText("Server stopped: the system's time limit for background servers was reached. Reopen the app to restart it.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "OpenAI API Server Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_OPEN_SERVER_SCREEN, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, OpenAiServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Edge Gallery API Server")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
