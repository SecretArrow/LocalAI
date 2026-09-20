# Model Formats

Which model file formats LocalAI Runtime can actually run, which it can merely detect, and how
imports work. The guiding rule: **run what is real, detect what is detectable, and never pretend
a format is executable when no runtime for it is bundled.**

## Supported now: GGUF

GGUF (GPT-Generated Unified Format) is the only format with a bundled runtime in this build — the
CPU backend (llama.cpp b4755) loads `.gguf` files directly, with mmap support and full sampler
control. Both file selection and the catalog are GGUF-first.

Detection is automatic from the file name extension (`.gguf`) and confirmed at load time by
llama.cpp itself — a corrupt or non-GGUF file fails with a clear `UnsupportedModel`/
`LoadFailed` error instead of undefined behavior.

### Quantization guidance

| Quantization | Approx. bits/weight | Quality | Guidance for phones |
|---|---|---|---|
| Q2_K | ~2.6 | Noticeably degraded | Only for squeezing very large models; expect quality loss |
| Q3_K_M | ~3.9 | Usable | Last resort on 3 GB-class devices |
| Q4_K_M | ~4.8 | Very good | **Recommended default** — best size/quality trade-off |
| Q5_K_M | ~5.7 | Near-lossless | Good when RAM allows |
| Q6_K | ~6.6 | Effectively lossless | Rarely worth the extra RAM on phones |
| Q8_0 | ~8.5 | Lossless for practical purposes | Great for tiny models (≤360M) where file is small anyway |
| F16 | 16 | Full precision | Not recommended on phones except for testing |

Practical advice: choose Q4_K_M for models from ~0.5B upward; choose Q8_0 for very small models
(the quality difference is free when the model is only ~150 MB). Avoid IQ/quants with exotic
kernels if you hit load errors on older llama.cpp builds.

### RAM rule of thumb

```text
estimatedBytes = fileSize * 1.15 + contextLength * 2 KB
```

The app applies exactly this formula before every model start (see [RUNTIME.md](RUNTIME.md)) and
refuses to load when available RAM is insufficient. Two levers when it fails: smaller
quantization (halves the file) or shorter context (linear KV-cache savings). Catalog entries
carry `min_ram_mb` and `recommended_ram_mb` computed with the same model.

### Recommended small models for phones

These are the entries shipped in the default catalog ([catalog/catalog.json](catalog/catalog.json));
all are Apache-2.0 and verified (exact size + SHA-256):

| Model | Quant | Size | Min RAM | Context | Notes |
|---|---|---|---|---|---|
| SmolLM2 135M Instruct | Q8_0 | 138 MB | 1 GB | 8192 | First-run smoke test; very fast on CPU |
| SmolLM2 360M Instruct | Q4_K_M | 258 MB | 1.5 GB | 8192 | Step up in quality, still tiny |
| Qwen2.5 0.5B Instruct | Q4_K_M | 469 MB | 2 GB | 32768 | Practical daily driver on 3 GB+ devices |
| Qwen2.5 1.5B Instruct | Q4_K_M | 1.04 GB | 3 GB | 32768 | Best quality in the set; 4 GB+ devices |
| TinyLlama 1.1B Chat v1.0 | Q4_K_M | 638 MB | 3 GB | 2048 | Solid chat quality for its size |

The catalog is remote JSON — the set above is a starting point, not a hardcoded list, and it can
be refreshed or replaced from Settings (see the schema in `CatalogEntry`,
[ARCHITECTURE.md](ARCHITECTURE.md)).

## Detected but not runnable

The format detector recognizes these extensions and shows them in the UI with their metadata, but
**no runtime adapter for them is bundled**, so they cannot be started. The app says so explicitly
rather than failing obscurely:

| Format | Detected via | Status | What is missing |
|---|---|---|---|
| ONNX (`.onnx`) | File extension + import dialog | Not runnable | ONNX Runtime mobile + `InferenceBackend` adapter |
| TFLite (`.tflite`) | File extension + import dialog | Not runnable | TFLite runtime + adapter (NNAPI acceleration would attach here) |
| Safetensors (`.safetensors`) | File extension + import dialog | Not runnable | Weight-only format; needs a loader + converter to a runtime format |

Importing one of these registers the file with correct metadata (size, SHA-256, format) and a
state that cannot transition to Running — the Model Detail page explains that the runtime adapter
is not bundled in this build. This mirrors spec §36: build the abstraction, document the
integration point, do not fake execution.

### Integration points (for future runtimes)

The seams already exist and are frozen in `docs/CONTRACTS.md`:

1. **`ModelFormatDetector`** — `ModelFormat.fromFileName()` already distinguishes
   GGUF/ONNX/TFLITE/SAFETENSORS.
2. **`ModelLoader` / runtime adapter** — implement `InferenceBackend` with
   `supportsModel(format)` returning true for the new format, plus a `LoadedModel`
   implementation. Register it in `BackendRegistry`.
3. **Native dependencies** — add the runtime library to `core/src/main/cpp/CMakeLists.txt` (or as
   an AAR dependency) and keep the ABI set `arm64-v8a`/`x86_64`.
4. **Honesty contract** — until the adapter is real, availability stays `UNAVAILABLE` with the
   reason shown to the user.

## Importing models

Import uses the Android Storage Access Framework — no broad storage permission is requested:

1. **Pick** — `ACTION_OPEN_DOCUMENT` with mime `*/*` from the Models screen; any file provider
   (Files, Downloads, Drive, another app) works.
2. **Register** — the file is registered via `ModelRepository.registerImported(...)`: scan,
   validate the format, compute SHA-256, read metadata, check compatibility, store in Room with a
   persisted `content://` URI. The file itself is **not copied** at import time — the app honors
   "do not copy huge files unnecessarily".
3. **Load** — when the model is started, `CpuBackend` opens the URI via
   `contentResolver.openFileDescriptor`. If the source is a `content://` URI, the app makes
   exactly **one copy** into its private models directory (needed for mmap and repeated loads)
   and loads from there; already-local files are used in place. This is the single pragmatic copy
   the design allows.

Imported models appear alongside downloaded ones with `imported = true`, their computed SHA-256,
size, detected format, and (for GGUF) the architecture/quantization reported by the runtime at
first load.

## Verification

Every download (catalog or custom URL) is verified before it can be activated:

| Check | When | On failure |
|---|---|---|
| Size (`Content-Length` / expected size) | At completion | `FAILED` with size mismatch error; `.part` retained for retry |
| SHA-256 (incrementally hashed while streaming, including across resumes) | At completion (state `VERIFYING`) | `FAILED` with `ChecksumMismatch`, expected vs. received digest shown; model is not activated |
| Metadata sanity (GGUF header parse) | At first load | Model state `FAILED` with a clear load error |

Catalog entries include `sha256` for every model, so catalog downloads always verify. Custom URLs
without a published checksum download with size verification only (you can supply a checksum when
adding the URL).
