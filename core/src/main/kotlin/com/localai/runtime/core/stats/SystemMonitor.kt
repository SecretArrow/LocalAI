package com.localai.runtime.core.stats

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import com.localai.runtime.core.model.SystemSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Samples CPU / RAM / storage / battery / network into [SystemSnapshot]s.
 *
 * [snapshots] is a SharedFlow (replay 1) produced by a sampling loop on the
 * injected [scope] that runs *only while there are subscribers* — the producer
 * job is (re)started from `onSubscription` and stops itself once
 * `subscriptionCount` drops to zero.
 *
 * GPU/NPU utilisation is not exposed by any public Android API, so those
 * fields stay null (honest reporting per spec §65).
 */
class SystemMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val _snapshots = MutableSharedFlow<SystemSnapshot>(replay = 1)

    /** Latest-value-cached stream sampled every [SAMPLE_INTERVAL_MS] while subscribed. */
    val snapshots: SharedFlow<SystemSnapshot> = _snapshots.onSubscription { ensureProducer() }

    @Volatile
    private var lastCpuSample: CpuSample? = null

    private val producerMutex = Mutex()
    private var producerJob: Job? = null

    /**
     * One-off snapshot.
     *
     * CPU usage is computed as the delta of /proc/stat jiffies since the
     * previous sample (producer tick or a prior [snapshotNow]); the very
     * first call therefore reports 0% CPU — acceptable and documented.
     * RAM/storage/battery/network are always current.
     */
    fun snapshotNow(): SystemSnapshot {
        val current = readCpuSample()
        val previous = lastCpuSample
        lastCpuSample = current
        return buildSnapshot(cpuPercentBetween(previous, current))
    }

    private suspend fun ensureProducer() {
        if (producerJob?.isActive == true) return
        producerMutex.withLock {
            if (producerJob?.isActive == true) return
            producerJob = scope.launch {
                while (_snapshots.subscriptionCount.value > 0) {
                    delay(SAMPLE_INTERVAL_MS)
                    val current = readCpuSample()
                    val previous = lastCpuSample
                    lastCpuSample = current
                    _snapshots.emit(buildSnapshot(cpuPercentBetween(previous, current)))
                }
            }
        }
    }

    // ------------------------------------------------------------------ //

    private fun buildSnapshot(cpuPercent: Float): SystemSnapshot {
        // RAM via ActivityManager
        var ramTotal = 0L
        var ramAvailable = 0L
        try {
            val activityManager = appContext.getSystemService(ActivityManager::class.java)
            if (activityManager != null) {
                val info = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(info)
                ramTotal = info.totalMem
                ramAvailable = info.availMem
            }
        } catch (_: Throwable) {
            // leave zeros
        }

        // Storage via StatFs on filesDir
        var storageTotal = 0L
        var storageUsed = 0L
        try {
            val stat = StatFs(appContext.filesDir.absolutePath)
            storageTotal = stat.totalBytes
            storageUsed = (stat.totalBytes - stat.availableBytes).coerceAtLeast(0L)
        } catch (_: Throwable) {
            // leave zeros
        }

        // Battery (percent + temperature) via the sticky ACTION_BATTERY_CHANGED intent
        var batteryPercent: Int? = null
        var temperatureC: Float? = null
        try {
            val sticky = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryPercent = (level * 100) / scale
                }
                val tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tenths != Int.MIN_VALUE && tenths in -300..1500) {
                    temperatureC = tenths / 10f
                }
            }
        } catch (_: Throwable) {
            // battery info unavailable
        }

        // Network
        val online = try {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            val active = connectivity?.activeNetwork
            active != null && connectivity
                ?.getNetworkCapabilities(active)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Throwable) {
            false
        }

        return SystemSnapshot(
            ts = System.currentTimeMillis(),
            cpuPercent = cpuPercent,
            ramUsedBytes = (ramTotal - ramAvailable).coerceAtLeast(0L),
            ramTotalBytes = ramTotal,
            gpuPercent = null,
            npuPercent = null,
            temperatureC = temperatureC,
            batteryPercent = batteryPercent,
            storageUsedBytes = storageUsed,
            storageTotalBytes = storageTotal,
            networkOnline = online,
        )
    }

    /** Reads the aggregate `cpu` line of /proc/stat. Returns null when unavailable. */
    private fun readCpuSample(): CpuSample? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            if (!line.startsWith("cpu")) return null
            // user nice system idle iowait irq softirq steal [guest guest_nice]
            val values = line.trim().split(Regex("\\s+")).drop(1)
                .mapNotNull { it.toLongOrNull() }
            if (values.size < 5) return null
            val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
            val total = values.take(8).sum()
            CpuSample(idle, total)
        } catch (_: Throwable) {
            null
        }
    }

    private fun cpuPercentBetween(previous: CpuSample?, current: CpuSample?): Float {
        if (previous == null || current == null) return 0f
        val deltaTotal = current.total - previous.total
        val deltaIdle = current.idle - previous.idle
        if (deltaTotal <= 0L) return 0f
        val busy = (deltaTotal - deltaIdle).coerceAtLeast(0L)
        return (busy * 100f) / deltaTotal
    }

    private data class CpuSample(val idle: Long, val total: Long)

    private companion object {
        const val SAMPLE_INTERVAL_MS = 2000L
    }
}
