package com.localai.runtime.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.localai.runtime.LocalAiApplication
import com.localai.runtime.R
import kotlinx.coroutines.CancellationException

/**
 * One-shot/periodic worker that resumes pending downloads after process restarts or
 * connectivity changes. The download manager owns its own error handling, so this worker
 * always reports success (spec §23/§56).
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? LocalAiApplication)?.container
            ?: return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (t: Throwable) {
            // Foreground promotion can be rejected by background FGS restrictions; resume anyway.
            container.appLogger.w(TAG, "Could not show the download worker notification: ${t.message}")
        }
        return try {
            container.downloadManager.resumeAll()
            container.appLogger.i(TAG, "Download resume pass completed")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.appLogger.e(TAG, "Download resume pass failed", t)
            Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Resuming downloads…")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                applicationContext.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.notification_channel_downloads_desc)
                setShowBadge(false)
            }
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
        private const val CHANNEL_DOWNLOADS = "downloads"
        private const val NOTIFICATION_ID = 1002
    }
}
