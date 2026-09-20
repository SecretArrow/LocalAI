package com.localai.runtime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.localai.runtime.LocalAiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the runtime service after boot when the user enabled "Start on boot".
 * Uses goAsync so DataStore can be read off the main thread inside the receiver window.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val container = (app as? LocalAiApplication)?.container ?: return@launch
                val settings = container.settingsRepository.flow.first()
                if (!settings.startOnBoot) return@launch
                container.appLogger.i(TAG, "Boot completed: starting runtime service (startOnBoot enabled)")
                try {
                    val autoStartModel = settings.autoStartModelId
                    if (autoStartModel.isNullOrBlank()) {
                        RuntimeService.start(app)
                    } else {
                        // Also fire the configured model when "auto-start model" is set.
                        RuntimeService.start(app)
                        RuntimeService.startModel(app, autoStartModel)
                    }
                } catch (e: SecurityException) {
                    container.appLogger.w(TAG, "Not allowed to start the runtime service at boot: ${e.message}")
                } catch (e: IllegalStateException) {
                    // Android 12+ background foreground-service start restrictions.
                    container.appLogger.w(TAG, "Background start restriction prevented boot start: ${e.message}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Boot start failed", t)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
