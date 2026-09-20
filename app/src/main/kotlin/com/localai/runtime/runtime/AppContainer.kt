package com.localai.runtime.runtime

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.localai.runtime.core.download.DownloadManager
import com.localai.runtime.core.log.AppLogger
import com.localai.runtime.core.model.DownloadState
import com.localai.runtime.core.model.ModelState
import com.localai.runtime.core.net.NetworkMonitor
import com.localai.runtime.core.repo.CatalogRepository
import com.localai.runtime.core.repo.ModelRepository
import com.localai.runtime.core.runtime.BackendRegistry
import com.localai.runtime.core.runtime.DeviceProbe
import com.localai.runtime.core.runtime.StubBackend
import com.localai.runtime.core.runtime.cpu.CpuBackend
import com.localai.runtime.core.security.SecretStore
import com.localai.runtime.core.settings.SettingsRepository
import com.localai.runtime.core.stats.SystemMonitor
import com.localai.runtime.core.model.BackendType
import com.localai.runtime.export.ConfigPorter
import com.localai.runtime.mdns.LanAdvertiser
import com.localai.runtime.pairing.PairingManager
import com.localai.runtime.server.ApiServer
import com.localai.runtime.service.CatalogRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container. Created once in [com.localai.runtime.LocalAiApplication].
 * Everything is lazy so the app starts fast and optional subsystems only
 * initialise when first used.
 */
class AppContainer(val context: Context) {

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val downloadsDir: File = File(context.filesDir, "downloads").apply { mkdirs() }
    val logsDir: File = File(context.filesDir, "logs").apply { mkdirs() }
    val cacheDir: File = context.cacheDir

    private val appContext: Context = context.applicationContext

    @Volatile
    private var latestSettings: com.localai.runtime.core.model.AppSettings? = null

    init {
        applicationScope.launch {
            settingsRepository.flow.collect { latestSettings = it }
        }
    }

    val appLogger: AppLogger by lazy {
        AppLogger(
            scope = applicationScope,
            logDir = logsDir,
            verbose = { latestSettings?.verboseLogs ?: false },
        )
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val database by lazy {
        com.localai.runtime.core.db.LocalAiDatabase.build(appContext)
    }

    val modelRepository by lazy {
        ModelRepository(db = database)
    }

    val catalogRepository by lazy {
        CatalogRepository(
            client = okHttpClient,
            cacheFile = File(File(appContext.filesDir, "catalog").apply { mkdirs() }, "catalog-cache.json"),
            modelRepository = modelRepository,
        )
    }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            client = okHttpClient,
            store = database.downloadDao(),
            network = networkMonitor,
            hasher = com.localai.runtime.core.util.Hashing,
            scope = applicationScope,
            maxParallel = 2,
        )
    }

    val deviceProbe: com.localai.runtime.core.runtime.DeviceProbe by lazy {
        DeviceProbe(appContext)
    }

    val backendRegistry: BackendRegistry by lazy {
        BackendRegistry(
            probe = deviceProbe,
            backends = listOf(
                CpuBackend(),
                StubBackend(BackendType.GPU_VULKAN, "Vulkan compute integration is not bundled in this build; the CPU runtime is active."),
                StubBackend(BackendType.GPU_OPENCL, "OpenCL integration is not bundled in this build; the CPU runtime is active."),
                StubBackend(BackendType.NNAPI, "NNAPI delegate requires an ONNX/TFLite runtime, which is not bundled in this build."),
                StubBackend(BackendType.NPU, "This device exposes no public NPU API to this build."),
            ),
        )
    }

    val secretStore: SecretStore by lazy { SecretStore(appContext) }

    val pairingManager: PairingManager by lazy {
        PairingManager(
            db = database,
            secretStore = secretStore,
            scope = applicationScope,
        )
    }

    val runtimeCoordinator by lazy {
        com.localai.runtime.runtime.RuntimeCoordinator(
            context = appContext,
            registry = backendRegistry,
            modelRepository = modelRepository,
            settings = settingsRepository,
            logger = appLogger,
            scope = applicationScope,
        )
    }

    val authStore: RuntimeAuthStore by lazy {
        RuntimeAuthStore(
            settings = settingsRepository,
            secretStore = secretStore,
            pairingManager = pairingManager,
            scope = applicationScope,
        )
    }

    val systemMonitor: SystemMonitor by lazy {
        SystemMonitor(appContext, applicationScope)
    }

    val lanAdvertiser: LanAdvertiser by lazy { LanAdvertiser(appContext) }

    val configPorter: ConfigPorter by lazy {
        ConfigPorter(
            context = appContext,
            modelRepository = modelRepository,
            settingsRepository = settingsRepository,
        )
    }

    val apiServer: ApiServer by lazy {
        ApiServer(
            registry = RuntimeModelRegistry(modelRepository),
            engine = runtimeCoordinator,
            auth = authStore,
            env = RuntimeServerEnv(settingsRepository, applicationScope),
            logSink = RuntimeLogSink(database, appLogger, applicationScope),
        )
    }

    init {
        // Global download bookkeeping: mark models as their downloads reach
        // terminal states even when no screen is collecting the flow.
        applicationScope.launch {
            downloadManager.progress
                .distinctUntilChangedBy { list -> list.map { it.id to it.state } }
                .collect { list ->
                    list.forEach { p ->
                        val modelId = p.modelId ?: return@forEach
                        runCatching {
                            when (p.state) {
                                DownloadState.COMPLETED -> modelRepository.updateState(modelId, ModelState.INSTALLED)
                                DownloadState.FAILED -> modelRepository.updateState(modelId, ModelState.FAILED, p.error)
                                DownloadState.CANCELLED -> modelRepository.updateState(modelId, ModelState.NOT_INSTALLED)
                                else -> Unit
                            }
                        } // A deleted model row must never kill this collector.
                    }
                }
        }

        // One-shot resume pass for interrupted downloads (background-safe via WorkManager).
        applicationScope.launch {
            val settings = settingsRepository.flow.first()
            if (settings.autoResume) {
                try {
                    val request = androidx.work.OneTimeWorkRequestBuilder<com.localai.runtime.service.DownloadWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        .build()
                    WorkManager.getInstance(appContext).enqueueUniqueWork(
                        "download-resume",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        request,
                    )
                } catch (_: Throwable) {
                    // WorkManager unavailable (rare ROMs) — DownloadManager's own watchdog still resumes.
                }
            }
        }

        // Periodic catalog refresh + API log pruning (respects settings).
        applicationScope.launch {
            val settings = settingsRepository.flow.first()
            if (settings.catalogAutoRefresh) {
                scheduleCatalogRefresh(settings.catalogRefreshHours)
            }
            runCatching {
                database.apiLogDao().prune(System.currentTimeMillis() - PRUNE_AGE_MS)
            }
        }
    }

    private companion object {
        const val PRUNE_AGE_MS = 7L * 24 * 3600 * 1000
    }

    fun scheduleCatalogRefresh(hours: Int) {
        val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(
            hours.coerceAtLeast(1).toLong(), TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            "catalog-refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
