package com.localai.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.localai.runtime.MainActivity
import com.localai.runtime.LocalAiApplication
import com.localai.runtime.R
import com.localai.runtime.runtime.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service (specialUse) that keeps the inference runtime and the API server
 * alive while anything is running. Shows a persistent low-importance notification with
 * running model names, API server state, request count and tokens/sec, plus Open and
 * Stop actions. Stops itself when no model is running and the API server is stopped.
 */
class RuntimeService : Service() {

    private var serviceScope: CoroutineScope? = null
    private var container: AppContainer? = null
    private var startedAt = 0L
    private val pendingActions = AtomicInteger(0)

    @Volatile
    private var stopRequested = false

    @Volatile
    private var lastNotifUpdate = 0L

    @Volatile
    private var lastSnapshot = Snapshot(runningNames = emptyList(), apiRunning = false, requests = 0L, tokensPerSecond = 0f)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startedAt = SystemClock.elapsedRealtime()
        createChannel()
        container = (application as? LocalAiApplication)?.container
        val c = container ?: return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope = scope
        scope.launch {
            combine(c.runtimeCoordinator.stats(), c.modelRepository.models) { stats, models -> stats to models }
                .collect { (stats, models) ->
                    val names = stats.map { s -> models.firstOrNull { it.id == s.modelId }?.name ?: s.modelId }.distinct()
                    val apiRunning = try {
                        c.apiServer.isRunning()
                    } catch (t: Throwable) {
                        false
                    }
                    val snapshot = Snapshot(
                        runningNames = names,
                        apiRunning = apiRunning,
                        requests = stats.fold(0L) { acc, s -> acc + s.totalRequests },
                        tokensPerSecond = stats.fold(0f) { acc, s -> acc + s.tokensPerSecond },
                    )
                    val namesChanged = snapshot.runningNames != lastSnapshot.runningNames
                    lastSnapshot = snapshot
                    val now = SystemClock.elapsedRealtime()
                    if (namesChanged || now - lastNotifUpdate >= NOTIFICATION_INTERVAL_MS) {
                        lastNotifUpdate = now
                        postNotification(snapshot)
                    }
                    maybeAutoStop(stats.isEmpty(), apiRunning)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // API 34 requirement: enter the foreground immediately, before any suspension point.
        startForegroundNow(buildNotification(lastSnapshot))
        val c = container
        if (c == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START_MODEL -> handleStartModel(c, intent.getStringExtra(EXTRA_MODEL_ID))
            ACTION_STOP_MODEL -> handleStopModel(c, intent.getStringExtra(EXTRA_MODEL_ID))
            ACTION_STOP_SERVICE -> handleStopService(c)
            else -> {
                // ACTION_START or a sticky restart: keep the notification fresh, nothing else to do.
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // The container owns the coordinator lifecycle; only stop our notification updates.
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- actions

    private fun handleStartModel(c: AppContainer, modelId: String?) {
        if (modelId.isNullOrBlank()) {
            c.appLogger.w(TAG, "START_MODEL action received without a model_id extra")
            return
        }
        pendingActions.incrementAndGet()
        serviceScope?.launch {
            try {
                val model = c.runtimeCoordinator.start(modelId)
                c.appLogger.i(TAG, "Started model ${model.name} (backend ${model.backend ?: "auto"})")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                c.appLogger.e(TAG, "Failed to start model $modelId", t)
            } finally {
                pendingActions.decrementAndGet()
                postNotification(lastSnapshot)
            }
        }
    }

    private fun handleStopModel(c: AppContainer, modelId: String?) {
        if (modelId.isNullOrBlank()) {
            c.appLogger.w(TAG, "STOP_MODEL action received without a model_id extra")
            return
        }
        pendingActions.incrementAndGet()
        serviceScope?.launch {
            try {
                val model = c.runtimeCoordinator.stop(modelId)
                c.appLogger.i(TAG, "Stopped model ${model.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                c.appLogger.e(TAG, "Failed to stop model $modelId", t)
            } finally {
                pendingActions.decrementAndGet()
                postNotification(lastSnapshot)
            }
        }
    }

    private fun handleStopService(c: AppContainer) {
        if (stopRequested) return
        stopRequested = true
        serviceScope?.launch {
            try {
                val ids = c.runtimeCoordinator.listRunning()
                for (id in ids) {
                    try {
                        c.runtimeCoordinator.stop(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        c.appLogger.e(TAG, "Failed to stop model $id", t)
                    }
                }
                try {
                    if (c.apiServer.isRunning()) c.apiServer.stop()
                } catch (t: Throwable) {
                    c.appLogger.e(TAG, "Failed to stop API server", t)
                }
                c.appLogger.i(TAG, "Runtime stopped by user (${ids.size} model(s) shut down)")
            } finally {
                stopForegroundAndSelf()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle helpers

    private fun maybeAutoStop(noRunningModels: Boolean, apiRunning: Boolean) {
        if (stopRequested) return
        if (!noRunningModels || apiRunning) return
        if (pendingActions.get() > 0) return
        if (SystemClock.elapsedRealtime() - startedAt < AUTO_STOP_GRACE_MS) return
        val c = container ?: return
        stopRequested = true
        c.appLogger.i(TAG, "Runtime idle: no models running and API server stopped — stopping service")
        stopForegroundAndSelf()
    }

    private fun startForegroundNow(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        ContextCompat.getMainExecutor(this).execute {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (t: Throwable) {
                // Service already stopped — nothing to do.
            }
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_RUNTIME,
            getString(R.string.notification_channel_runtime),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_runtime_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun postNotification(snapshot: Snapshot) {
        ContextCompat.getMainExecutor(this).execute {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(snapshot))
            } catch (t: Throwable) {
                // Notification updates are best-effort.
            }
        }
    }

    private fun buildNotification(s: Snapshot): Notification {
        val title = if (s.runningNames.isEmpty()) {
            "LocalAI Runtime — Idle"
        } else {
            "LocalAI Runtime — ${s.runningNames.joinToString(", ")}"
        }
        val summary = buildString {
            append(if (s.apiRunning) "API server: Running" else "API server: Off")
            if (s.requests > 0L) {
                append(" · ")
                append(s.requests)
                append(" requests")
            }
            if (s.tokensPerSecond > 0.05f) {
                append(" · ")
                append(String.format(Locale.US, "%.1f tok/s", s.tokensPerSecond))
            }
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RuntimeService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_RUNTIME)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(summary)
            .setSubText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private data class Snapshot(
        val runningNames: List<String>,
        val apiRunning: Boolean,
        val requests: Long,
        val tokensPerSecond: Float,
    )

    companion object {
        private const val TAG = "RuntimeService"
        private const val CHANNEL_RUNTIME = "runtime"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MS = 2_000L
        private const val AUTO_STOP_GRACE_MS = 10_000L

        const val ACTION_START = "com.localai.runtime.action.START"
        const val ACTION_STOP_SERVICE = "com.localai.runtime.action.STOP_SERVICE"
        const val ACTION_START_MODEL = "com.localai.runtime.action.START_MODEL"
        const val ACTION_STOP_MODEL = "com.localai.runtime.action.STOP_MODEL"
        const val EXTRA_MODEL_ID = "model_id"

        /** Starts (or ensures) the foreground runtime service. */
        fun start(context: Context) {
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Starts a specific model through the foreground runtime service. */
        fun startModel(context: Context, modelId: String) {
            val intent = Intent(context, RuntimeService::class.java)
                .setAction(ACTION_START_MODEL)
                .putExtra(EXTRA_MODEL_ID, modelId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the runtime (all models + API server) and the service itself. */
        fun stopService(context: Context) {
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_STOP_SERVICE)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                // App is in the background: fall back to a plain stop of the service.
                context.stopService(Intent(context, RuntimeService::class.java))
            }
        }
    }
}
