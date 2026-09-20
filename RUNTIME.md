# Runtime Architecture

How LocalAI Runtime executes models on Android: the backend abstraction, the real CPU backend,
memory protection, crash supervision, benchmarking, and the extension path for new backends.

## Backend abstraction

All inference goes through two small interfaces in `:core` (`com.localai.runtime.core.runtime`):

```text
InferenceBackend                     LoadedModel
├── CpuBackend        (real)          ├── modelId / backend / contextLength
├── StubBackend(VULKAN)               ├── generateStream(messages, params): Flow<String>
├── StubBackend(OPENCL)               ├── benchmark(): BenchmarkResult
├── StubBackend(NNAPI)                └── unload()
├── StubBackend(NPU)
```

```kotlin
interface InferenceBackend {
    val type: BackendType
    suspend fun detect(capabilities: DeviceCapabilities): BackendInfo
    fun supportsModel(model: ModelInfo): Boolean
    suspend fun load(model: ModelInfo, config: RuntimeConfig): LoadedModel?
}
```

`BackendRegistry` owns the list of backends:

- `detectAll()` probes the device once and returns availability for every backend type.
- `available(model)` filters backends that are both operational and able to run the model.
- `auto(model)` picks the first available backend — today that is always CPU.

A `DeviceProbe` produces `DeviceCapabilities`; `StubBackend` exists precisely so that detected
accelerators can be reported honestly (`UNAVAILABLE` + reason) without pretending to run anything.

### Availability states

| State | Meaning |
|---|---|
| `AVAILABLE` | The backend is bundled and can execute models right now |
| `SUPPORTED` | The device has the capability, but this build does not bundle the runtime |
| `UNAVAILABLE` | Not usable on this device/build, with a human-readable reason |
| `UNKNOWN` | No reliable way to probe (e.g. NPU) — never guessed |

## CPU backend

The only execution backend in this build wraps llama.cpp, pinned to tag **b4755** (stable release,
fetched via CMake `FetchContent` at build time; see [BUILD.md](BUILD.md)).

### Native bridge

`core/src/main/cpp/llama_bridge.cpp` compiles into `liblocalai_runtime.so` and exposes a minimal
JNI surface through `LlamaBridge` (Kotlin):

| JNI function | Purpose |
|---|---|
| `nativeInit()` | Initialize the llama.cpp global backend |
| `nativeLoadModel(path, nCtx, nThreads, useMmap)` | Load a GGUF file; returns a handle or 0 |
| `nativeFreeModel(ptr)` | Release a handle |
| `nativeContextLength(ptr)` | Actual context of the loaded model |
| `nativeTokenizeCount(ptr, text)` | Token accounting for requests |
| `nativeGenerate(ptr, roles, contents, temperature, topP, topK, minP, repeatPenalty, seed, maxTokens, onToken)` | Streaming chat generation with a cancellation callback |
| `nativeBenchmark(ptr, nPromptTokens, nGenTokens)` | Timed prompt-processing and generation pass |

Design points:

- **Graceful degradation.** If `System.loadLibrary("localai_runtime")` fails (for example an
  unsupported ABI), `LlamaBridge.available` is `false` and the CPU backend reports `UNAVAILABLE`
  with the load error. The app never crashes on a missing native library.
- **Memory mapping.** GGUF weights are mmap'd by default (`memoryMapping` in the runtime config),
  which lets the kernel page model weights and reduces cold-start time and peak anonymous memory.
- **Threads.** `cpuThreads <= 0` means Auto: `min(cpuCores, 8)`. More threads help prompt
  processing but beyond physical cores mostly waste battery.
- **Cancellation.** `nativeGenerate` invokes a Kotlin callback per token; returning `false` stops
  generation immediately. The backend wraps this in a coroutine `Flow`, so cancelling the collector
  (Stop button, client disconnect) stops native work.
- **KV cache reuse.** The bridge clears the KV cache between unrelated requests and keeps the
  chat template application inside native code for speed.

### Sampling chain

Generation parameters map onto the llama.cpp sampler chain in this order:

```text
repeat penalty  →  top_k  →  top_p  →  min_p  →  temperature  →  distribution
```

Defaults (from `RuntimeConfig`): `temperature 0.7`, `top_p 0.9`, `top_k 40`, `min_p 0.05`,
`repeat_penalty 1.1`, `seed -1` (random). Profiles (Fast, Balanced, Quality, Coding, Low Memory)
are presets over the same knobs.

## RuntimeCoordinator

`RuntimeCoordinator` (`:app`) implements the `InferenceEngine` interface consumed by the API server
and the Chat UI. On top of raw backend calls it adds the operational guarantees:

- **Memory pre-check before every load** (see formula below) — fails fast with a readable message
  instead of an OOM kill.
- **Crash supervision** — if a model process/handle dies, restart with backoff 2 s, 8 s, 32 s; at
  most 3 attempts, then the model state becomes `FAILED` with the last error surfaced in the UI.
  No infinite restart loops.
- **Concurrency control** — a per-model mutex serializes loads/unloads; a global semaphore
  (default 4) caps concurrent generations; overflow beyond the queue (default 16) returns a `busy`
  error instead of piling up work.
- **Stats** — tokens/sec (rolling), prompt tokens/sec, native heap usage, request counters,
  combined into `EngineStats` for `/api/runtime`, Monitoring, and the notification.

### Memory estimation and OOM protection

Before starting a model, the coordinator estimates the footprint and compares it with
`ActivityManager.MemoryInfo.availMem`:

```text
estimatedBytes = modelSizeBytes * 1.15 + contextLength * 2 KB
```

- `* 1.15` covers runtime overhead (KV cache quantization slack, compute buffers, vocab).
- `contextLength * 2 KB` approximates the KV cache for typical 4-bit/8-bit caches.
- If `estimatedBytes > availMem`, the start fails immediately with
  `INSUFFICIENT_MEMORY` (API: `507`), a clear "Required X / Available Y" message, and suggestions
  (stop another model, reduce context, use a smaller quantization).

Worked example — Qwen2.5 0.5B Q4_K_M (491,400,032 bytes) at 4096 context:

```text
491,400,032 * 1.15  = 565,110,037
4,096 * 2 KB         =   8,388,608
estimated            = 573,498,645  ≈ 547 MiB
```

Rule of thumb: to hold a model comfortably, the device should have at least the model file size
plus ~15% plus context overhead, in line with the catalog's `min_ram_mb` / `recommended_ram_mb`.

## Benchmark methodology

The Benchmark screen (and `engine.benchmark(modelId)`) runs `nativeBenchmark(ptr, 512, 256)`:

| Metric | Definition |
|---|---|
| Prompt processing (pp) | Tokens/sec while ingesting a 512-token prompt |
| Generation (tg) | Tokens/sec over 256 generated tokens |
| Peak memory | Delta of `Debug.getNativeHeapAllocatedSize()` captured before/after |
| Startup time | Time from load request to ready handle |

Results are comparable across devices for the same model + quantization + thread count; they are
shown in the Benchmark screen and exportable as text. Keep the device unplugged/cool and the same
thread count if you want reproducible numbers.

## Device capability detection

| Capability | How it is detected | Reported as |
|---|---|---|
| CPU architecture / ABI | `Build.SUPPORTED_ABIS` | `arm64`, ABI list |
| CPU cores | `Runtime.getRuntime().availableProcessors()` | core count (threads default = min(cores, 8)) |
| Total/available RAM | `ActivityManager.MemoryInfo` (`totalMem`, `availMem`) | bytes |
| GPU vendor / model | `EGL14` query on a worker thread | strings when available |
| Vulkan | `PackageManager.hasSystemFeature(FEATURE_VULKAN_HARDWARE_VERSION)` | availability + version string |
| OpenCL | Presence of `/system/lib64/libOpenCL.so` or `/system/lib/libOpenCL.so` | availability |
| NNAPI | `hasSystemFeature("android.hardware.neuralnetworks")` | availability |
| NPU | No public Android API exists | `UNKNOWN` (never guessed) |
| CPU runtime | `LlamaBridge.available` (native library loads) | the real execution backend |

The probe result drives `/api/backends`, the Models screen compatibility hints, and the About
diagnostics view.

## Why other backends are unavailable (honestly)

| Backend | Reason it is not an execution backend in this build |
|---|---|
| Vulkan | llama.cpp Vulkan compute is not bundled in this build; integrating it requires shipping and tuning GGML Vulkan shaders per driver. The device's Vulkan support is still detected and displayed. |
| OpenCL | Same situation as Vulkan, plus highly vendor-specific driver quality on Android; no reliable cross-device behavior. |
| NNAPI | NNAPI executes ONNX/TFLite graphs, not GGUF. An NNAPI backend requires bundling an ONNX Runtime or TFLite runtime and a conversion path — not included in this build. |
| NPU | Android exposes no public, portable NPU API; vendor SDKs are device-specific. Reported as Unknown. |

None of these are fake: the UI shows them as unavailable with exactly these reasons, and no model
is ever claimed to run on hardware it does not run on.

## Adding a backend (extension guide)

The abstraction exists so backends can be added without touching the API server or UI.

1. **Implement `InferenceBackend`** in `:core` (for example
   `core/runtime/vulkan/VulkanBackend.kt`):
   - `detect()` must return `AVAILABLE` **only** when the backend can genuinely execute a model
     (library loads, drivers OK). Otherwise return `UNAVAILABLE` with a truthful reason — this is
     the contract that keeps the app honest.
   - `supportsModel()` filters by format/architecture the backend can run.
   - `load()` returns a `LoadedModel` whose `generateStream()` is a cancellable coroutine flow of
     token strings, or `null` with a reason when the model cannot be loaded.
2. **Register it** in `BackendRegistry` (the list is constructed in `AppContainer`). Order
   matters for `auto()`: put preferred backends first; CPU remains the fallback.
3. **Native code** (if the backend is C/C++): add the sources to
   `core/src/main/cpp/CMakeLists.txt`, keep ABIs `arm64-v8a`/`x86_64`, and mind APK size —
   consider delivering heavy runtimes as on-demand dynamic features.
4. **Respect the operational rules**: memory pre-check before load, cooperative cancellation,
   unload frees everything, benchmark support, and stats reporting through the coordinator.
5. **Document availability honestly** in `/api/backends` and the UI; never advertise a backend
   that fails at runtime (spec §65 forbids fake acceleration).

The same pattern extends to non-GGUF runtimes (ONNX, TFLite) — see
[MODEL_FORMATS.md](MODEL_FORMATS.md) for the format side of that work.
