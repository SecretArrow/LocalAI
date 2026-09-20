package com.localai.runtime.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localai.runtime.LocalAiApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Periodic catalog refresh (scheduled by the app container when catalogAutoRefresh is on).
 * Logs the diff summary and always succeeds — refresh failures are transient and the next
 * period retries them.
 */
class CatalogRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? LocalAiApplication)?.container
            ?: return Result.failure()
        return try {
            val settings = container.settingsRepository.flow.first()
            val result = container.catalogRepository.refresh(settings.catalogUrl)
            val message = result.fold(
                onSuccess = { diff ->
                    "refresh: ok (new=${diff.newModels.size}, updated=${diff.updatedModels.size}, removed=${diff.removedIds.size})"
                },
                onFailure = { e ->
                    "refresh: failed — ${e.message ?: e.javaClass.simpleName}"
                },
            )
            container.appLogger.i(TAG, message)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            container.appLogger.e(TAG, "Catalog refresh worker failed", t)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "CatalogRefreshWorker"
    }
}
