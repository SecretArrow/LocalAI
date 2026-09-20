# LocalAI Runtime — Architecture Contracts

**READ THIS FILE COMPLETELY BEFORE WRITING ANY CODE.**
All public signatures below are FROZEN. Other agents implement against them. Do not rename, do not change parameter lists, do not move classes to other packages. If something is genuinely missing, implement it as a private helper in your own file.

## Project layout

```
:app           Android application (Compose Material 3 UI, services, coordinator)   package com.localai.runtime
:core          Android library (domain, Room DB, downloads, runtime, security)     package com.localai.runtime.core
:api-server    Pure Kotlin/JVM (Ktor server, OpenAI-compatible API)                package com.localai.runtime.server
```

- applicationId `com.localai.runtime`, minSdk 26, compileSdk 34, Kotlin 2.0.0, JDK 17.
- Manual DI: everything is wired in `com.localai.runtime.runtime.AppContainer` (written by the orchestrator).
- `:api-server` has NO Android dependencies. It defines the interfaces in `com.localai.runtime.server.api` (already written): `InferenceEngine`, `ModelRegistry`, `AuthStore`, `ServerEnv`, `ServerLogSink`, plus DTOs `ChatMessage`, `CompletionParams`, `ServerModel`, `EngineStats`, `BenchmarkResult`, `ServerLogEntry`, `ApiLimits`, `ServerEndpoint`.
- `:core` depends on `:api-server` (Android lib → JVM lib is allowed). `:app` depends on both.
- Domain models in `com.localai.runtime.core.model` are already written (Models.kt, AppSettings.kt, LocalAiException.kt). Use them everywhere; do not redefine.

## Hard conventions

1. Kotlin coroutines + Flow everywhere. Repositories expose `Flow` for lists; one-shot `suspend` for actions.
2. Errors: throw `LocalAiException` subtypes for user-facing failures (see model/LocalAiException.kt). Never show raw stack traces.
3. UI text: hardcoded English strings inside composables are ACCEPTED for v1 (no strings.xml except app_name/notifications). Keep text short and clear.
4. NO Hilt/Dagger/KAPT. NO `stringResource()` for screen text. NO TODO() placeholders — implement real behavior or surface "Not available" states honestly (spec forbids fake functionality).
5. ViewModels: plain `class XViewModel(...) : ViewModel()` constructor injection; screens create them via `viewModel(factory = ...)` with `viewModelFactory { initializer { ... } }` (androidx.lifecycle.viewmodel). Screens take `(container: AppContainer, navController: NavController)` or `(container: AppContainer, navController: NavController, modelId: String)` as params (plain functions, not wrapped in ViewModel).
6. Every ViewModel exposing state uses `StateFlow` created via `MutableStateFlow` + `asStateFlow()`. Side effects launched in `viewModelScope`.
7. Dispatchers: inject/derive from `Dispatchers.IO`/`Default`; never block the main thread.
8. Tests: pure JVM (JUnit4). NO Robolectric. Test only classes without Android framework dependencies.
9. Files use 4-space indent, UTF-8, final newline. Kotlin style: standard android-kotlin-style.
10. Do NOT run git, gradle, or any build command. You only WRITE files.

---

## :core contracts

### db package — `com.localai.runtime.core.db`

Room database `LocalAiDatabase` (version 1, `exportSchema = false`) with entities + DAOs:

```kotlin
@Entity(tableName = "models") data class ModelEntity(
  @PrimaryKey val id: String, val name: String, val version: String, val format: String, val quantization: String,
  val sizeBytes: Long, val sha256: String?, val architecture: String?, val contextLength: Int, val parameterCount: Long,
  val vocabSize: Int, val license: String?, val sourceUrl: String?, val minRamMb: Int, val recommendedRamMb: Int,
  val backendsCsv: String, val state: String, val localPath: String?, val installedAt: Long, val updatedAt: Long,
  val favorite: Boolean, val collectionsCsv: String, val lastError: String?, val imported: Boolean)

@Entity(tableName = "runtime_configs") data class RuntimeConfigEntity(
  @PrimaryKey val modelId: String, val backendId: String?, val cpuThreads: Int, val gpuLayers: Int, val contextLength: Int,
  val batchSize: Int, val temperature: Float, val topP: Float, val topK: Int, val minP: Float, val repeatPenalty: Float,
  val seed: Long, val streaming: Boolean, val memoryMapping: Boolean, val flashAttention: Boolean, val parallel: Int, val profile: String)

@Entity(tableName = "downloads") data class DownloadEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0, val modelId: String?, val url: String, val fileName: String,
  val destPath: String, val downloadedBytes: Long, val totalBytes: Long, val etag: String?, val lastModified: String?,
  val sha256: String?, val expectedSize: Long, val state: String, val error: String?, val wifiOnly: Boolean,
  val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "api_logs") data class ApiLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0, val ts: Long, val method: String, val path: String, val status: Int,
  val latencyMs: Long, val model: String?, val client: String?, val error: String?, val tokensIn: Int, val tokensOut: Int)

@Entity(tableName = "paired_devices") data class PairedDeviceEntity(
  @PrimaryKey val id: String, val name: String, val tokenHash: String, val tokenPrefix: String,
  val createdAt: Long, val lastSeenAt: Long, val revoked: Boolean)

@Dao interface ModelDao { suspend fun upsert(m: ModelEntity); suspend fun get(id: String): ModelEntity?; fun observeAll(): Flow<List<ModelEntity>>; fun observe(id: String): Flow<ModelEntity?>; suspend fun delete(id: String); suspend fun all(): List<ModelEntity> }
@Dao interface RuntimeConfigDao { suspend fun upsert(c: RuntimeConfigEntity); fun observe(modelId: String): Flow<RuntimeConfigEntity?>; suspend fun get(modelId: String): RuntimeConfigEntity?; suspend fun delete(modelId: String) }
@Dao interface DownloadDao : DownloadStore  // implements the store interface below
@Dao interface ApiLogDao { suspend fun insert(e: ApiLogEntity): Long; fun observeRecent(limit: Int): Flow<List<ApiLogEntity>>; suspend fun recent(limit: Int): List<ApiLogEntity>; suspend fun clear(); suspend fun prune(keep: Long) }
@Dao interface PairedDeviceDao { suspend fun upsert(d: PairedDeviceEntity); fun observeAll(): Flow<List<PairedDeviceEntity>>; suspend fun byTokenHash(hash: String): PairedDeviceEntity?; suspend fun delete(id: String); suspend fun updateLastSeen(id: String, ts: Long) }
```

`LocalAiDatabase`: `@Database(entities = [...all five...], version = 1, exportSchema = false)`, abstract class with the five DAO getters, plus `companion object { fun build(context: Context): LocalAiDatabase = Room.databaseBuilder(...).fallbackToDestructiveMigration().build() }`.

### settings — `com.localai.runtime.core.settings`

```kotlin
class SettingsRepository(private val context: Context) {
    val flow: Flow<AppSettings>            // DataStore<Preferences> "localai_settings", map to AppSettings with defaults
    suspend fun update(transform: (AppSettings) -> AppSettings)
    // typed convenience suspend setters are allowed but keep update() as the core
}
```

### repo — `com.localai.runtime.core.repo`

```kotlin
class ModelRepository(private val db: LocalAiDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {
    val models: Flow<List<ModelInfo>>                      // Room observeAll mapped, sorted: running first, then name
    fun observe(id: String): Flow<ModelInfo?>
    suspend fun get(id: String): ModelInfo?
    suspend fun upsert(model: ModelInfo)                   // maps to entity (csv-join backends/collections)
    suspend fun updateState(id: String, state: ModelState, error: String? = null)
    suspend fun updateConfig(config: RuntimeConfig)
    fun observeConfig(id: String): Flow<RuntimeConfig?>
    suspend fun config(id: String): RuntimeConfig          // returns stored config or defaults built from model + AppSettings passed in configOrDefault(model, defaults)
    suspend fun configOrDefault(model: ModelInfo, defaults: AppSettings): RuntimeConfig
    suspend fun delete(id: String)
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun setCollections(id: String, collections: List<String>)
    suspend fun fromCatalog(entry: CatalogEntry): ModelInfo  // NOT_INSTALLED, no localPath
    suspend fun registerImported(name: String, path: String, sizeBytes: Long, sha256: String?, format: ModelFormat): ModelInfo
    suspend fun listAll(): List<ModelInfo>
}

class CatalogRepository(
    private val client: okhttp3.OkHttpClient,
    private val cacheFile: java.io.File,          // JSON cache of last successful manifest
    private val modelRepository: ModelRepository,
) {
    val entries: Flow<List<CatalogEntry>>         // from cache file, parsed at collect time; empty list when no cache
    suspend fun refresh(): Result<CatalogDiff>    // fetch manifest from URL (given), validate, write cache, diff vs installed
    suspend fun cachedVersion(): String?
    companion object { fun parse(json: String): CatalogManifest }  // kotlinx-serialization, lenient, ignoreUnknownKeys
}
```

Diffing rule: entry is "new" when no installed ModelInfo with same id; "updated" when installed version differs from catalog version; "removed" when installed model with sourceUrl-based catalog id is missing from the manifest. Never auto-download or auto-replace (spec §5).

### download — `com.localai.runtime.core.download`

```kotlin
interface DownloadStore {   // implemented by DownloadDao
    suspend fun insert(e: DownloadEntity): Long
    suspend fun update(e: DownloadEntity)
    suspend fun get(id: Long): DownloadEntity?
    suspend fun all(): List<DownloadEntity>
    suspend fun active(): List<DownloadEntity>
    suspend fun delete(id: Long)
    fun observeAll(): Flow<List<DownloadEntity>>
}

interface NetworkGate { fun isOnline(): Boolean; fun isWifi(): Boolean; fun isMetered(): Boolean }

class DownloadManager(
    private val client: okhttp3.OkHttpClient,
    private val store: DownloadStore,
    private val network: NetworkGate,
    private val hasher: com.localai.runtime.core.util.Hashing,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val maxParallel: Int = 2,
) {
    val progress: Flow<List<DownloadProgress>>     // distinct snapshots of all non-terminal downloads (+ recently completed), updated at most every 500ms
    suspend fun enqueue(url: String, destFile: java.io.File, fileName: String, modelId: String?, expectedSha256: String?, expectedSize: Long, wifiOnly: Boolean): Long
    suspend fun pause(id: Long); suspend fun resume(id: Long); suspend fun cancel(id: Long); suspend fun retry(id: Long)
    suspend fun resumeAll(); suspend fun pauseAll(); suspend fun cancelAll()
    fun snapshot(): List<DownloadProgress>
}
```

Behaviour (spec §6, §23, §56): HTTP/HTTPS, follow redirects, `Range: bytes=<downloaded>-` resume with `If-Range` (ETag preferred, else Last-Modified; neither → restart only when server responds 200 to a range request), verify `Accept-Ranges`, stream to `dest.part` file, incremental SHA-256 via DigestInputStream while downloading (resume: re-hash existing part first), verify final size and sha256 (state VERIFYING then COMPLETED / FAILED ChecksumMismatch), pause/cancel via cooperative flags checked between chunks, automatic retry with exponential backoff (3 attempts, 2s/8s/32s) when auto-retry enabled and error is network-related, queue with maxParallel workers, progress with speed + ETA (rolling window), Wi-Fi only gate (pause → PAUSED when network doesn't satisfy constraint). Never restart a completed partial file unless ETag/Last-Modified changed. All state persisted in DownloadStore so restart resumes.

### runtime — `com.localai.runtime.core.runtime`

```kotlin
interface InferenceBackend {
    val type: BackendType
    suspend fun detect(capabilities: DeviceCapabilities): BackendInfo
    fun supportsModel(model: ModelInfo): Boolean
    suspend fun load(model: ModelInfo, config: RuntimeConfig): LoadedModel?   // null when backend cannot load it
}

interface LoadedModel {
    val modelId: String
    val backend: BackendType
    val contextLength: Int
    fun generateStream(messages: List<com.localai.runtime.server.api.ChatMessage>, params: com.localai.runtime.server.api.CompletionParams): kotlinx.coroutines.flow.Flow<String>
    suspend fun benchmark(): com.localai.runtime.server.api.BenchmarkResult
    suspend fun unload()
}

class BackendRegistry(private val probe: DeviceProbe, private val backends: List<InferenceBackend>) {
    suspend fun detectAll(): List<BackendInfo>
    fun available(model: ModelInfo): List<InferenceBackend>   // backends whose detect() == AVAILABLE and supportsModel
    fun auto(model: ModelInfo): InferenceBackend?              // first available (CPU is the only real one today)
}

class DeviceProbe(private val context: android.content.Context) {
    fun probe(): DeviceCapabilities   // ABI/cores/RAM via ActivityManager+Runtime, GPU via EGL14 query on a worker thread, Vulkan via PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, OpenCL via presence of /system/lib64/libOpenCL.so or /system/lib/libOpenCL.so, NNAPI via hasSystemFeature("android.hardware.neuralnetworks") (report UNKNOWN when uncertain), NPU = UNKNOWN (no public API), cpuBackendAvailable = LlamaBridge.available
}

class CpuBackend : InferenceBackend   // in core/runtime/cpu/LlamaCpuBackend.kt
class StubBackend(private val type: BackendType, private val reason: String) : InferenceBackend  // detect() returns UNAVAILABLE with reason; load() returns null
```

`CpuBackend` uses `LlamaBridge` (already written): GGUF only (`supportsModel` false otherwise), `load()` → `LlamaBridge.available` guard, `nativeLoadModel(path, ctx, threads, mmap)`; handle 0 → throw/return null with reason from `LlamaBridge.loadError`. `generateStream` wraps `nativeGenerate` in a flow: run on `Dispatchers.IO`, bridge tokens via `callbackFlow`-style channel, honour cancellation by returning false from the token lambda, finalise with model state updates done by the coordinator (not the backend). Threads default `min(cores, 8)` when config.cpuThreads <= 0. Benchmark via `nativeBenchmark(ptr, 512, 256)`; peak memory via `Debug.getNativeHeapAllocatedSize()` captured before/after (android.os.Debug is fine in :core).

`StubBackend` reasons (be honest, spec §2/§36): Vulkan → "Vulkan compute integration not bundled in this build; CPU runtime is active"; OpenCL → same style; NNAPI → "NNAPI delegate requires an ONNX/TFLite runtime, not bundled in this build"; NPU → "No public NPU API exposed by this device".

### security — `com.localai.runtime.core.security`

```kotlin
object TokenGenerator {
    fun randomToken(prefix: String = "lai_"): String    // 32 bytes SecureRandom, hex, prefix + 64 chars
    fun sixDigitCode(): String                          // "839221" style, SecureRandom
}

class KeystoreCipher(private val keyAlias: String = "localai_master") {
    fun encrypt(plain: String): ByteArray               // AES/GCM AndroidKeyStore, random IV prepended
    fun decrypt(blob: ByteArray): String
    fun deleteKey()                                     // best-effort
}

class SecretStore(private val context: android.content.Context, private val cipher: KeystoreCipher = KeystoreCipher()) {
    fun saveToken(name: String, token: String)          // encrypted file in noBackupFilesDir/secrets/
    fun readToken(name: String): String?
    fun clearToken(name: String)
    fun tokenSha256(name: String): String?              // lowercase hex sha256 of stored token
    companion object { fun sha256(value: String): String }
}
```

### net — `com.localai.runtime.core.net`

```kotlin
class NetworkMonitor(private val context: android.content.Context) : NetworkGate {
    val online: Flow<Boolean>                            // callbackFlow on ConnectivityManager.NetworkCallback
    override fun isOnline(): Boolean; override fun isWifi(): Boolean; override fun isMetered(): Boolean
}
object LanAddresses {
    fun ipv4Addresses(context: android.content.Context): List<String>   // WiFi IPv4s via WifiManager/Enumeration of NetworkInterfaces (non-loopback site-local)
}
```

### stats — `com.localai.runtime.core.stats`

```kotlin
class SystemMonitor(private val context: android.content.Context, private val scope: kotlinx.coroutines.CoroutineScope) {
    val snapshots: Flow<SystemSnapshot>   // SharedFlow sampled every 2000ms while subscribed: CPU% from /proc/stat delta, RAM via ActivityManager.MemoryInfo, storage via StatFs on filesDir, battery+temp via ACTION_BATTERY_CHANGED sticky intent, network via ConnectivityManager
    fun snapshotNow(): SystemSnapshot
}
```

### log — `com.localai.runtime.core.log`

```kotlin
class AppLogger(private val scope: kotlinx.coroutines.CoroutineScope, private val logDir: java.io.File, private val verbose: () -> Boolean = { false }) {
    val lines: Flow<List<LogLine>>     // ring buffer of last 2000 lines
    fun d(tag: String, msg: String); fun i(tag: String, msg: String); fun w(tag: String, msg: String); fun e(tag: String, msg: String, t: Throwable? = null)
    fun clear()
    fun exportTo(file: java.io.File)   // plain text, newest last
    private rotation: single file log.txt, rotate at 2 MB → log.1.txt (keep one previous)
}
```

### util — `com.localai.runtime.core.util`

```kotlin
object Hashing {
    suspend fun sha256(file: java.io.File, onProgress: (Long) -> Unit = {}): String
    fun sha256(bytes: ByteArray): String
    fun constantTimeEquals(a: String, b: String): Boolean
}
object Formats {
    fun bytes(v: Long): String          // "4.8 GB", binary units
    fun speed(bytesPerSec: Long): String// "12.4 MB/s"
    fun eta(seconds: Long): String      // "2m 31s"
    fun percent(done: Long, total: Long): Int
    fun time(ts: Long): String          // HH:mm:ss
}
```

---

## :api-server contracts — `com.localai.runtime.server`

```kotlin
class ApiServer(
    private val registry: ModelRegistry,
    private val engine: InferenceEngine,
    private val auth: AuthStore,
    private val env: ServerEnv,
    private val logSink: ServerLogSink,
) {
    fun start(): Boolean                 // CIO engine on env.endpoint(); false when already running or bind fails
    fun stop()
    fun isRunning(): Boolean
    fun endpoint(): ServerEndpoint
    suspend fun startWithTls(certPem: String, keyPem: String): Boolean   // HTTP stays on main port; TLS via second connector on tlsPort (self-signed or imported)
    suspend fun generateSelfSignedCert(): Pair<String, String>            // returns PEM pair (uses BouncyCastle)
}
```

Implement in files: `ApiServer.kt` (engine lifecycle, plugin wiring: ContentNegotiation kotlinx-json, auth interceptor, rate limiting, request size limit, logging hook, error handling → JSON `{"error": {"message", "type", "code"}}`), `AuthPlugin.kt` (NONE/API_KEY/BEARER; Bearer token parsed from `Authorization: Bearer x`; API_KEY mode accepts `Authorization: Bearer x` or `X-API-Key: x`; 401 JSON on failure; skipped for GET /api/health and the web console root), `RateLimiter.kt` (token bucket per client IP: 60 req/min general, 10 req/min for generate endpoints; 429 JSON), `RoutesNative.kt`, `RoutesOpenAi.kt`, `WebConsole.kt` (serves `env.webConsoleHtml()` at `/`), `SelfSignedCert.kt` (BouncyCastle X500 self-signed RSA 2048, 3650 days, returns PEM cert + PKCS8 key strings; on Android registers provider via `Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1)` guarded in a helper called from app — keep the registration inside this class's companion `ensureProvider()`).

Native routes (spec §16): GET `/api/health` (200 `{"status":"ok","version":1}` — no auth), GET `/api/status` (uptime, running models, server info), GET `/api/models`, GET `/api/models/{id}`, POST `/api/models/{id}/start|stop|restart`, GET `/api/runtime` (backend availability summary from engine stats), GET `/api/devices` (paired devices → not exposed here; return `{"devices":[]}` + document), GET `/api/backends`, GET `/api/downloads` (empty list placeholder object `{"downloads":[]}` — server has no download visibility; document honestly in API.md).

OpenAI routes (spec §15): GET `/v1/models` (`{"object":"list","data":[{id, object:"model", created, owned_by:"local"}...]}`), POST `/v1/chat/completions` (stream=true → SSE `data: {json chunks}` with OpenAI chunk schema + `data: [DONE]`; non-stream → full response with usage; 404 `model_not_found` when engine can't find/start the model), POST `/v1/completions` (legacy prompt field; delegate to same engine), POST `/v1/embeddings` → 501 `{"error":{"message":"Embeddings require a runtime with embedding support; not available in this build","type":"not_supported"}}` (honest per spec §59).

OpenAI chunk schema (streaming): `{"id":"chatcmpl-local","object":"chat.completion.chunk","created":<epoch>,"model":<id>,"choices":[{"index":0,"delta":{"content":"..."},"finish_reason":null}]}` final chunk `finish_reason:"stop"` then `data: [DONE]`.

Request logging: method, path, status, latency, client IP, model when present, tokensIn (approx via message length/4), tokensOut (emitted pieces count) → `logSink.log(ServerLogEntry(...))`. Privacy: never log bodies.

Web console (`resources/web/index.html`, single file, dark theme, no external assets): tabs Chat / Models / API / Logs / Metrics; Chat tab: model dropdown (GET /v1/models), message list, input, streaming via fetch + SSE parse; Models tab: table with state + Start/Stop buttons (POST /api/models/{id}/start|stop); API tab: endpoint list + curl examples generated from `location.host`; Logs tab: polls GET /api/logs?limit=100 (ADD this simple route returning last 100 entries as `{"logs":[...]}` from an in-memory ring inside ApiServer fed by logSink — keep a `ConcurrentLinkedDeque` capped at 500 inside ApiServer); Metrics tab: polls GET /api/status and shows requests/tokens/uptime. Handle connection errors with a visible "Server unreachable" banner. Vanilla JS + CSS only, ~compact.

Tests (`api-server/src/test/kotlin/com/localai/runtime/server/`): `AuthPluginTest`, `OpenAiRoutesTest`, `RateLimiterTest` — use `ktor-server-test-host` with fake registry/engine (simple stub classes), assert 200/401/429/404/501 paths and SSE first-chunk shape.

---

## :app contracts — `com.localai.runtime`

### runtime — `com.localai.runtime.runtime` (written by app-infra agent)

```kotlin
class RuntimeCoordinator(
    private val context: android.content.Context,
    private val registry: BackendRegistry,
    private val modelRepository: ModelRepository,
    private val settings: SettingsRepository,
    private val logger: AppLogger,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : com.localai.runtime.server.api.InferenceEngine {
    // Implements start/stop/restart/listRunning/generate/benchmark/stats per the interface.
    // Memory pre-check before load (spec §10/§35): estimate = sizeBytes*1.15 + contextLength*2KB;
    // compare vs ActivityManager.MemoryInfo.availMem; throw EngineException(INSUFFICIENT_MEMORY) with clear message.
    // Crash supervision (spec §34): max 3 restarts with 2s/8s/32s backoff per model, then FAILED state.
    // Concurrency (spec §20): per-model Mutex; global semaphore of apiMaxConcurrent for generate calls; queue overflow → EngineException(BUSY).
    // Stats: combine LoadedModel token timings (tokens/sec rolling), native heap usage, request counters.
}

class AppContainer(private val context: android.content.Context)  // written by ORCHESTRATOR — you may reference it but not write it
```

**AppContainer public surface (FROZEN — reference these exact properties):**

```kotlin
class AppContainer(val context: Context) {
    val applicationScope: kotlinx.coroutines.CoroutineScope   // SupervisorJob + Dispatchers.Default
    val appLogger: com.localai.runtime.core.log.AppLogger
    val settingsRepository: com.localai.runtime.core.settings.SettingsRepository
    val database: com.localai.runtime.core.db.LocalAiDatabase
    val modelRepository: com.localai.runtime.core.repo.ModelRepository
    val catalogRepository: com.localai.runtime.core.repo.CatalogRepository
    val okHttpClient: okhttp3.OkHttpClient
    val networkMonitor: com.localai.runtime.core.net.NetworkMonitor
    val downloadManager: com.localai.runtime.core.download.DownloadManager
    val deviceProbe: com.localai.runtime.core.runtime.DeviceProbe
    val backendRegistry: com.localai.runtime.core.runtime.BackendRegistry
    val runtimeCoordinator: com.localai.runtime.runtime.RuntimeCoordinator
    val systemMonitor: com.localai.runtime.core.stats.SystemMonitor
    val secretStore: com.localai.runtime.core.security.SecretStore
    val pairingManager: com.localai.runtime.pairing.PairingManager
    val lanAdvertiser: com.localai.runtime.mdns.LanAdvertiser
    val configPorter: com.localai.runtime.export.ConfigPorter
    val apiServer: com.localai.runtime.server.ApiServer
    val authStore: com.localai.runtime.runtime.RuntimeAuthStore   // implements com.localai.runtime.server.api.AuthStore
    val modelsDir: java.io.File      // context.filesDir/models
    val downloadsDir: java.io.File   // context.filesDir/downloads
    val cacheDir: java.io.File       // context.cacheDir
}
```

### services — `com.localai.runtime.service`

```kotlin
class RuntimeService : android.app.Service      // specialUse FGS. Started via startForegroundService from UI or BootReceiver.
// Notification channel "runtime" (string res notification_channel_runtime).
// Persistent notification: "LocalAI Runtime — <model names or Idle>", API server state, requests + tokens/sec when available,
// actions: Open (MainActivity) + Stop (ACTION_STOP handled in onStartCommand). onStartCommand also handles ACTION_START_MODEL(modelId) / ACTION_STOP_MODEL(modelId).
// Binds the coordinator from (application as LocalAiApplication).container; observes stats to update notification at most every 2s.
// Stops itself (stopForeground+stopSelf) when no model running AND api server stopped.

class BootReceiver : android.content.BroadcastReceiver   // BOOT_COMPLETED → if settings.startOnBoot, startForegroundService(RuntimeService) (guard try-catch for background restrictions)
```

### workers — `com.localai.runtime.service`

```kotlin
class DownloadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker
// Periodic/one-shot resume worker: container.downloadManager.resumeAll(); setForeground with "downloads" channel notification when API>=26; Result.success always (download manager handles its own errors)

class CatalogRefreshWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker
// container.catalogRepository.refresh(); log result via AppLogger; Result.success
// AppContainer schedules it (PeriodicWorkRequest of catalogRefreshHours when catalogAutoRefresh) — orchestrator wires scheduling in AppContainer
```

### pairing — `com.localai.runtime.pairing`

```kotlin
class PairingManager(
    private val db: LocalAiDatabase,
    private val secretStore: SecretStore,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    val devices: Flow<List<PairedDevice>>
    suspend fun beginPairing(): PairingSession      // code valid 60s
    suspend fun approve(session: PairingSession, deviceName: String): String   // returns generated device token (shown/scanned once)
    suspend fun revoke(id: String)
    suspend fun rename(id: String, name: String)
    suspend fun touchLastSeen(tokenHash: String)
    suspend fun verifyToken(token: String): Boolean // sha256 match against non-revoked paired devices
}
data class PairingSession(val code: String, val expiresAtMs: Long) { fun isExpired(now: Long = System.currentTimeMillis()): Boolean }
```

### mdns — `com.localai.runtime.mdns`

```kotlin
class LanAdvertiser(private val context: android.content.Context) {
    fun start(port: Int, name: String = "LocalAI Runtime")   // NsdManager registerService "_localai._tcp.", try-catch no-op on failure
    fun stop()
    val isRunning: Boolean
}
```

### qr — `com/z/localai fix → com.localai.runtime.qr`

```kotlin
object QrCodes {
    fun generate(content: String, size: Int = 512): android.graphics.Bitmap?   // ZXing QRCodeWriter, null on failure
    fun apiConnectPayload(host: String, port: Int, tls: Boolean): String       // json {"host","port","tls","auth"} — NO token inside (spec §25)
}
```

### export — `com.localai.runtime.export`

```kotlin
class ConfigPorter(private val context: android.content.Context, private val modelRepository: ModelRepository, private val settingsRepository: SettingsRepository) {
    suspend fun exportSettingsJson(): String          // {"version":1,"runtime":{...},"api":{...},"downloads":{...},"models":[{id,name,backend,threads,context_length,temperature,top_p}]} — NEVER secrets/tokens (spec §8/§40)
    suspend fun exportModelConfig(modelId: String): String
    suspend fun importSettingsJson(json: String): Result<Int>  // apply non-secret settings; returns count applied
    suspend fun importModelConfig(modelId: String, json: String): Result<Unit>
}
```

### Screens & ViewModels (app-ui agents)

Every screen lives in `com.localai.runtime.ui.screens.<name>` as TWO files: `<Name>Screen.kt` (composables; entry function `<Name>Screen(container: AppContainer, navController: NavController[, modelId: String])`) and `<Name>ViewModel.kt`. ViewModels in `com.localai.runtime.ui.screens.<name>` too (same package, keeps imports simple).

Screens to implement (spec references):
- **home**: dashboard (models installed/running counts, API status, CPU/RAM/GPU tiles via SystemMonitor, quick actions: Run Model / Import Model / Download Model / Open Chat / API Server) + "More" links to Monitoring, Logs, Storage, Devices, Downloads, Benchmark, About, API docs.
- **models**: search + filter chips (All/Installed/Running/Compatible/Format/Quant) + sort menu + list of model cards (name, version, format, quant, size, RAM req, backend, state) + actions Download/Run/Stop/Pause/Resume/Delete/Details/Export config + catalog section listing CatalogRepository entries with Download action + "Add custom URL" dialog (spec §55: HEAD → size → enqueue).
- **modeldetail**: full metadata table + state + Start/Stop/Restart/Configure (runtime config editor with Auto modes)/Export config/Delete + last error display.
- **downloads**: active downloads with ProgressRow + pause/resume/cancel + queue + pause all/resume all/cancel all (spec §57).
- **chat**: conversation list drawer or simple single-conversation v1 with New Chat, model selector (running models), system prompt field, message list with streaming bubbles, stop generation, regenerate, copy, share, simple markdown (bold/italic/code fence) rendering via AnnotatedString, temperature + max tokens quick settings (spec §28).
- **server**: API dashboard — endpoint(s) with copy buttons, LAN URL list, requests/tokens stats, Start/Stop/Restart server, QR code (QrCodes.apiConnectPayload), link to API docs screen, HTTPS section (self-signed generate, import PEM), security warning when LAN+no-auth (spec §17/§13/§24).
- **devices**: pairing — start pairing (code + countdown), connected devices list with revoke/rename, mDNS toggle (spec §26).
- **monitoring**: SystemMonitor snapshots → Canvas line charts for CPU/RAM + runtime stats (tokens/sec, requests) + storage/battery/temp tiles (spec §19).
- **logs**: AppLogger lines + API request logs (ApiLogDao observeRecent(200)), level filter, clear, export/share (spec §18).
- **storage**: StorageUsage breakdown (models dir walk, downloads, cache, logs, temp), free space, clear cache, delete temp, export/import settings (spec §22/§40).
- **settings**: sections Runtime / API / Downloads / Background / Security / Appearance / Developer (verbose logs toggle, diagnostics info) mirroring AppSettings 1:1 (spec §32), uses SettingsRepository.update.
- **benchmark**: pick running/installed model → run benchmark via coordinator → results table (pp t/s, tg t/s, peak RAM, startup) + export result as text (spec §46).
- **apidocs**: built-in documentation — endpoint reference + dynamically generated curl/Python/JS examples using current endpoint from settings (spec §47).
- **about**: app version, licenses (llama.cpp MIT, ZXing Apache-2.0, OkHttp Apache-2.0, Ktor Apache-2.0, BouncyCastle MIT), privacy statement (spec §51), links.

ChatViewModel: holds messages list (data class UiMessage(role, content, streaming: Boolean)), collects `coordinator.generate(...)` into the last assistant message; stop via Job.cancel(); regenerate drops last assistant message and re-runs.

Import model flow (models screen): ACTION_OPEN_DOCUMENT with mime `*/*` (SAF), copy NOT done — models register via `ModelRepository.registerImported(name, path, size, sha256, format)` where path is a persisted URI string; the actual load uses `contentResolver.openFileDescriptor` → in CpuBackend, when localPath starts with "content://", copy once to private models dir then load the file (spec §7 "do not copy huge files unnecessarily" — one copy into the app's model dir is the pragmatic approach; document it).

---

## Honest availability rules (spec §65)

- Never claim GPU/NPU execution. Vulkan/OpenCL/NNAPI/NPU show as Unavailable with the stub reasons above.
- /v1/embeddings → 501 not supported.
- Web console Logs/Metrics tabs read from the server's own state — no fake data.
- When no model is running, chat screen shows "Start a model first" empty state.
- Download of a catalog entry always verifies checksum when sha256 provided.

## CI/CD notes (for awareness — workflows are written by orchestrator)

- GitHub Actions runs `./gradlew :app:assembleDebug test` on every push (with Gradle + ccache caching).
- Release: tag `v*` or manual dispatch; signs via `ANDROID_KEYSTORE_*` env vars (falls back to debug signing when absent).
- Auto-fix: on CI failure, `.github/workflows/auto-fix.yml` + `scripts/autofix.py` parse logs, apply deterministic fixes, push `fix(auto): attempt N` commits to main (max 5 per human commit), optionally LLM-assisted when `OPENAI_API_KEY`/`ZAI_API_KEY`/`OPENROUTER_API_KEY` secrets exist.
