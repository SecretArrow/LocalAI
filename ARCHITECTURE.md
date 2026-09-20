# Architecture

Module graph, package layout, data flows, storage schema, threading model, error hierarchy, and
extension points. Interface-level details (frozen signatures) live in
[docs/CONTRACTS.md](docs/CONTRACTS.md); this document explains the structure around them.

## Module graph

```text
+--------------------------------------------------------------+
| :app  (Android application, com.localai.runtime)             |
| Compose UI (14 screens), RuntimeCoordinator, RuntimeService,  |
| BootReceiver, Workers, Pairing, mDNS, QR, ConfigPorter        |
+-------------------------------+------------------------------+
                                | depends on
                                v
+--------------------------------------------------------------+
| :core  (Android library, com.localai.runtime.core)           |
| Domain models, Room DB, SettingsRepository (DataStore),       |
| Model/Catalog repositories, DownloadManager,                  |
| InferenceBackend abstraction + CpuBackend (JNI/llama.cpp),    |
| DeviceProbe, security (tokens/keystore), SystemMonitor,       |
| AppLogger, network monitor                                    |
+-------------------------------+------------------------------+
                                | depends on
                                v
+--------------------------------------------------------------+
| :api-server  (pure Kotlin/JVM, com.localai.runtime.server)   |
| Ktor CIO server, OpenAI-compatible routes, native routes,     |
| auth plugin, rate limiter, web console, self-signed TLS       |
+--------------------------------------------------------------+
```

Dependency rules:

- `:api-server` has **no Android dependencies** — the entire HTTP surface is JVM-unit-testable.
- `:core` depends on `:api-server` and implements its engine/registry/auth interfaces.
- `:app` depends on both and is the only module with UI, services, and manifest wiring.
- No module depends upward; `:api-server` knows nothing about Room, Android, or llama.cpp.
- Manual DI: everything is constructed in `AppContainer` (`com.localai.runtime.runtime`) — no
  Hilt, no KAPT, no service locator magic.

## Package map

| Package | Module | Responsibility |
|---|---|---|
| `com.localai.runtime` | :app | `MainActivity`, `LocalAiApplication`, navigation |
| `com.localai.runtime.runtime` | :app | `AppContainer` (DI), `RuntimeCoordinator` (implements `InferenceEngine`), `RuntimeAuthStore` |
| `com.localai.runtime.service` | :app | `RuntimeService` (specialUse foreground service), `BootReceiver`, `DownloadWorker`, `CatalogRefreshWorker` |
| `com.localai.runtime.pairing` | :app | `PairingManager` (6-digit codes, per-device tokens) |
| `com.localai.runtime.mdns` | :app | `LanAdvertiser` (NsdManager, `_localai._tcp.`) |
| `com.localai.runtime.qr` | :app | `QrCodes` (ZXing QR bitmaps, connect payload without token) |
| `com.localai.runtime.export` | :app | `ConfigPorter` (settings/model config import-export, no secrets) |
| `com.localai.runtime.ui.*` | :app | Theme, shared components, one package per screen (`home`, `models`, `modeldetail`, `downloads`, `chat`, `server`, `devices`, `monitoring`, `logs`, `storage`, `settings`, `benchmark`, `apidocs`, `about`) |
| `com.localai.runtime.core.model` | :core | Domain models: `ModelInfo`, `RuntimeConfig`, `CatalogEntry`, `AppSettings`, `LocalAiException`, enums |
| `com.localai.runtime.core.db` | :core | Room database, 5 entities, DAOs |
| `com.localai.runtime.core.settings` | :core | `SettingsRepository` (DataStore Preferences) |
| `com.localai.runtime.core.repo` | :core | `ModelRepository`, `CatalogRepository` (fetch/cache/diff) |
| `com.localai.runtime.core.download` | :core | `DownloadManager`, `DownloadStore`, `NetworkGate` |
| `com.localai.runtime.core.runtime` | :core | `InferenceBackend`, `LoadedModel`, `BackendRegistry`, `DeviceProbe`, `StubBackend` |
| `com.localai.runtime.core.runtime.cpu` | :core | `LlamaBridge` (JNI) + `LlamaCpuBackend` |
| `com.localai.runtime.core.security` | :core | `TokenGenerator`, `KeystoreCipher`, `SecretStore` |
| `com.localai.runtime.core.net` | :core | `NetworkMonitor`, `LanAddresses` |
| `com.localai.runtime.core.stats` | :core | `SystemMonitor` (CPU/RAM/storage/battery sampling) |
| `com.localai.runtime.core.log` | :core | `AppLogger` (ring buffer + rotated file) |
| `com.localai.runtime.core.util` | :core | `Hashing`, `Formats` |
| `com.localai.runtime.server` | :api-server | `ApiServer`, `AuthPlugin`, `RateLimiter`, `RoutesNative`, `RoutesOpenAi`, `WebConsole`, `SelfSignedCert` |
| `com.localai.runtime.server.api` | :api-server | Frozen interfaces + DTOs: `InferenceEngine`, `ModelRegistry`, `AuthStore`, `ServerEnv`, `ServerLogSink`, types |

## Key data flows

### Download pipeline

```text
UI / catalog entry / custom URL
  |
  v
DownloadManager.enqueue(url, dest, expected sha256+size, wifiOnly)     (Room: downloads row QUEUED)
  |
  v
Worker pool (maxParallel, default 2)
  |-- check NetworkGate (wifi-only)               --> PAUSED if violated
  |-- HEAD/GET with Range: bytes=<have>-            (If-Range: ETag | Last-Modified)
  |     |-- 206 -> append to <dest>.part, incremental SHA-256 (re-hash existing part on resume)
  |     |-- 200 -> server cannot resume: restart cleanly
  |-- progress Flow throttled to 500 ms snapshots (bytes, %, speed, ETA)
  |-- network error -> auto-retry backoff 2s/8s/32s (3 attempts), then FAILED (retryable)
  v
VERIFYING: final size + SHA-256 check
  |
  +-- match --> COMPLETED: register ModelInfo (INSTALLED), file usable
  +-- mismatch --> FAILED (ChecksumMismatch), model NOT activated
```

All state is persisted in the `downloads` table, so killing the app or rebooting resumes where it
left off (via `DownloadWorker` on restart or the Resume button).

### Generation request (SSE)

```text
Client  -->  POST /v1/chat/completions (stream: true)
  |
  v
Ktor: auth plugin -> rate limiter -> size/param validation
  |
  v
RuntimeCoordinator.generate(modelId, messages, params)
  |-- model not running? 404 model_not_found
  |-- acquire global semaphore (maxConcurrent) or join queue (overflow -> busy)
  |
  v
CpuBackend / LlamaBridge.nativeGenerate(...onToken callback)
  |-- callback -> Flow<String> chunk -> SSE "data: {...}" chunk
  |-- client disconnect -> callback returns false -> native generation stops
  v
final chunk finish_reason:"stop" -> "data: [DONE]"
  |
  v
ServerLogSink.log(method, path, status, latency, model, client, tokensIn/out)  (never bodies)
  -> in-memory ring (web console /api/logs) + Room api_logs
```

### Boot / foreground lifecycle

```text
BOOT_COMPLETED -> BootReceiver (if startOnBoot) -> startForegroundService(RuntimeService)
RuntimeService (specialUse FGS)
  |-- persistent notification: running models, server state, requests + tokens/sec (2s refresh)
  |-- actions: Open (MainActivity) / Stop (ACTION_STOP)
  |-- handles ACTION_START_MODEL / ACTION_STOP_MODEL
  |-- keeps: inference, API server, downloads
  `-- stops itself when no model is running AND the API server is stopped
UI actions (Run/Start server) -> startForegroundService -> same service
```

## Room schema (version 1, 5 tables)

| Table | Purpose | Key columns |
|---|---|---|
| `models` | Registered models (downloaded, imported, catalog-known) | id, name, version, format, quantization, sizeBytes, sha256, architecture, contextLength, parameterCount, license, sourceUrl, min/recommendedRamMb, backendsCsv, state, localPath, favorite, collectionsCsv, lastError, imported |
| `runtime_configs` | Per-model runtime configuration | modelId (PK), backendId, cpuThreads, gpuLayers, contextLength, batchSize, temperature, topP, topK, minP, repeatPenalty, seed, streaming, memoryMapping, flashAttention, parallel, profile |
| `downloads` | Resumable download state | auto id, modelId, url, fileName, destPath, downloadedBytes, totalBytes, etag, lastModified, sha256, expectedSize, state, error, wifiOnly |
| `api_logs` | Request log (UI Logs screen) | auto id, ts, method, path, status, latencyMs, model, client, error, tokensIn, tokensOut |
| `paired_devices` | Paired LAN clients | id, name, tokenHash, tokenPrefix, createdAt, lastSeenAt, revoked |

`fallbackToDestructiveMigration` is intentional for the 0.x series (local cache data only;
models live as files and are re-scanned). Settings live in DataStore, not Room.

## Settings overview (DataStore keys)

Grouped as in the Settings screen (`AppSettings`):

| Group | Keys |
|---|---|
| Runtime | `defaultBackendId` (null = Auto), `defaultThreads` (-1 = Auto), `defaultContext` (4096), `defaultGpuLayers`, `defaultModelId` |
| API | `apiEnabled`, `apiHost` (127.0.0.1), `apiPort` (8080), `apiTlsEnabled`, `apiTlsPort` (8443), `apiAuthMode` (NONE), `apiMaxConcurrent` (4), `apiQueueSize` (16), `apiTimeoutSeconds` (300), `apiLanAccess` (false) |
| Discovery | `mdnsEnabled` (true) |
| Downloads | `wifiOnly` (true), `maxParallelDownloads` (2), `autoRetry` (true), `autoResume` (true) |
| Background | `keepRuntimeAlive` (true), `startOnBoot` (false), `autoStartModelId` (null) |
| Catalog | `catalogUrl` (default: this repository's `catalog/catalog.json`), `catalogAutoRefresh` (true), `catalogRefreshHours` (24) |
| Appearance | `darkMode` (SYSTEM), `dynamicColor` (true) |
| Developer/privacy | `developerMode`, `verboseLogs`, `privacyMode` (true) |

## Threading model

| Scope | Where | Used for |
|---|---|---|
| `applicationScope` (SupervisorJob + Dispatchers.Default) | AppContainer | Long-lived app-wide work: downloads, logger, monitor, pairing |
| `viewModelScope` (Main) | Each ViewModel | State exposure; heavy calls hop to IO/Default |
| `Dispatchers.IO` | CpuBackend generation, hashing, DB, downloads | Blocking/native work |
| `Dispatchers.Main` | Compose UI only | Never blocked |
| Ktor CIO event loop | API server | Non-blocking request handling |

Rules enforced by the contracts: no blocking calls on Main; generation flows are cancellable
cooperative flows; `Flow` for lists, `suspend` for actions; progress flows throttled (500 ms
downloads, 2 s monitor sampling, 2 s notification refresh).

## Error hierarchy

User-facing failures throw `LocalAiException` subtypes (never raw stack traces):

```text
LocalAiException
├── ModelIncompatible        ├── DownloadInterrupted
├── BackendUnavailable       ├── ChecksumMismatch (expected, actual)
├── InsufficientRam (requiredBytes, availableBytes)
├── InsufficientStorage (requiredBytes, availableBytes)
├── PortInUse (port)         ├── CertificateInvalid
├── AuthenticationFailed     ├── RuntimeCrashed
├── UnsupportedModel         ├── NotFound
├── Storage                  ├── Network
└── Cancelled
```

The API server maps engine failures to `EngineException(reason)` → HTTP codes (`MODEL_NOT_FOUND`
→ 404, `INSUFFICIENT_MEMORY` → 507, `BUSY` → 503, and so on) with the JSON error envelope defined
in [API.md](API.md). Developer mode may attach `detail` to any error for diagnostics.

## Extension points

| Extension | How |
|---|---|
| New inference backend (Vulkan, OpenCL, NNAPI, NPU, or a new runtime) | Implement `InferenceBackend` + `LoadedModel` in `:core`, register in `BackendRegistry` (AppContainer). Honest availability is a contract — see [RUNTIME.md](RUNTIME.md) |
| New model format (ONNX, TFLite) | Format detection already exists; add a backend that `supportsModel` that format — integration points listed in [MODEL_FORMATS.md](MODEL_FORMATS.md) |
| Catalog sources | The catalog is a remote JSON manifest (`CatalogEntry` schema); point `catalogUrl` at any compatible feed, or serve your own — the parser is lenient and versioned |
| Web console | Single self-contained HTML from `:api-server` resources (`WebConsole.kt` serves `env.webConsoleHtml()`); extend tabs there without touching Android code |
| API surface | Routes live in `RoutesNative.kt` / `RoutesOpenAi.kt` (pure JVM); add routes + tests without device instrumentation |
| Background jobs | WorkManager workers in `com.localai.runtime.service` scheduled from AppContainer |

## Testing strategy

- Pure JVM unit tests everywhere `:api-server` and non-Android `:core` classes live (Ktor
  test-host with fake registry/engine for auth, rate limiting, routes, and SSE shape; MockWebServer
  for download resume/checksum logic).
- No Robolectric; no Android-instrumented tests in CI — device-level behaviors (foreground
  service, boot receiver) are covered by manual acceptance against the spec's checklist.
- CI runs `assembleDebug` + `test` on every push; failures feed the auto-fix loop (see
  [BUILD.md](BUILD.md)).
