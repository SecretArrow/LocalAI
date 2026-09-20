# LocalAI Runtime

Turn an Android phone into a **self-contained local AI server**.

LocalAI Runtime is a production-grade Android application that downloads, imports and manages GGUF
language models, runs them entirely on-device via llama.cpp, and exposes them over an
OpenAI-compatible HTTP/HTTPS API. Other apps on the phone, PCs, and browsers on your LAN can use
the running model — no cloud, no account, no data leaving the device.

[![CI](https://github.com/SecretArrow/LocalAI/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/LocalAI/actions/workflows/ci.yml)
[![Release](https://github.com/SecretArrow/LocalAI/actions/workflows/release.yml/badge.svg)](https://github.com/SecretArrow/LocalAI/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-7F52FF)](https://kotlinlang.org)
[![Android API](https://img.shields.io/badge/API-26%2B-green)](https://developer.android.com/about/versions/oreo)

```text
                    ┌─────────────────────┐
                    │   Android Device    │
                    │                     │
                    │  LocalAI Runtime    │
                    │                     │
                    │  Model Manager      │
                    │  Runtime Engine     │
                    │  CPU/GPU/NPU        │
                    │  Download Manager   │
                    │  API Server         │
                    │  Background Service │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
              ▼                ▼                ▼
          Android App       PC/Laptop       Web Browser
              │                │                │
              └──────── HTTP/HTTPS API ────────┘
```

## Features

### Models and catalog

- Manage multiple models with states (Not installed, Downloading, Importing, Installed, Starting,
  Running, Stopping, Stopped, Failed) and per-model runtime configuration.
- Remote JSON model catalog with automatic/manual/background refresh, local caching, new/updated/
  removed detection, and device compatibility hints. The catalog is never hardcoded into the APK;
  the default source is [`catalog/catalog.json`](catalog/catalog.json) in this repository and can be
  changed in Settings.
- Import local `.gguf` files (plus `.onnx`, `.tflite`, `.safetensors` detection) via the Android
  Storage Access Framework, with SHA-256 hashing, metadata reading, and compatibility checks.
- Model detail pages, favorites, collections, search/filter/sort, per-model config export.

### Downloads

- Resumable HTTP/HTTPS downloads with `Range`/`If-Range` (ETag or Last-Modified), `.part` files,
  incremental SHA-256 streamed while downloading, and final size + checksum verification.
- Pause, resume, cancel, retry with exponential backoff (3 attempts: 2s/8s/32s), a parallel queue
  (default 2), Wi-Fi-only gating, speed/ETA display, and full persistence across app restarts.
- Custom model URLs (`Add Model URL`: HEAD probe for size and range support, then enqueue).

### Runtime

- Real on-device inference through llama.cpp (pinned release b4755) compiled for Android and loaded
  via JNI (`liblocalai_runtime.so`) — memory mapping, configurable threads and context, streaming
  token generation, and reproducible seeding.
- Memory pre-check before every load (estimated footprint vs. available RAM), supervised restarts
  after crashes (max 3 attempts with 2s/8s/32s backoff), per-model request queuing, and honest
  backend availability reporting (see table below).
- Built-in benchmark: prompt-processing and generation tokens/sec, peak memory, startup time.

### API server

- OpenAI-compatible endpoints: `GET /v1/models`, `POST /v1/chat/completions` (SSE streaming and
  non-streaming), `POST /v1/completions`, `POST /v1/embeddings` (returns 501 — not supported).
- Native management endpoints: `/api/health`, `/api/status`, `/api/models`, `/api/models/{id}` with
  `start|stop|restart`, `/api/runtime`, `/api/backends`, `/api/logs`, and more — see
  [API.md](API.md).
- Ktor CIO server on port 8080 (HTTP) with optional HTTPS on 8443 (self-signed certificate
  generated on-device via BouncyCastle, or imported PEM pair).
- Embedded web console at `http://127.0.0.1:8080/` with Chat / Models / API / Logs / Metrics tabs.

### Security

- Binds to `127.0.0.1` by default; LAN access (`0.0.0.0`) is strictly opt-in with a warning and an
  authentication recommendation.
- Authentication modes: None, API Key, Bearer token. Tokens are 32-byte `SecureRandom` values
  stored encrypted (Android Keystore AES-GCM), verified in constant time, shown once, revocable.
- Per-IP token-bucket rate limiting (60 req/min general, 10 req/min for generation), request size
  limits, timeouts, and connection caps.
- Optional device pairing with 6-digit codes (60 s validity) and per-device tokens; QR connect
  payloads never contain the token itself. mDNS discovery (`_localai._tcp.`) is optional.

### User interface

- Material 3 Jetpack Compose UI, light/dark/system themes with dynamic color, adaptive navigation
  (bottom bar on phones, rail on tablets).
- 14 screens: Home, Models, Model Detail, Downloads, Chat (streaming, stop, regenerate, markdown),
  Server, Devices, Monitoring, Logs, Storage, Settings, Benchmark, API Docs, About.
- Real-time monitoring (CPU, RAM, storage, battery, temperature, network), log viewer with level
  filter and export, storage manager with cleanup actions.

### Background operation

- Foreground service (`specialUse`) keeps inference, the API server, and downloads alive with a
  persistent status notification; optional start-on-boot and auto-start model (both opt-in).
- WorkManager workers for download resume and periodic catalog refresh.

## Current backend support

LocalAI Runtime reports backend availability honestly. Accelerators other than CPU are **detected
and displayed** but are **not integrated execution backends** in this build — the UI marks them as
unavailable and explains why. Nothing is faked.

| Backend | Execution | Status in this build |
|---|---|---|
| CPU (llama.cpp b4755, via JNI) | Yes | Fully supported — the real runtime |
| GPU (Vulkan) | No | Detected (device capability display only) |
| GPU (OpenCL) | No | Detected (device capability display only) |
| NNAPI | No | Detected (requires an ONNX/TFLite runtime, not bundled) |
| NPU | No | Reported as Unknown (no public Android API) |

`POST /v1/embeddings` returns `501` because the bundled CPU runtime does not expose embeddings.
Adding a backend is a documented extension path — see [RUNTIME.md](RUNTIME.md).

## Requirements

- Android 8.0 (API 26) or later, `arm64-v8a` device (or `x86_64` emulator).
- RAM guidance: 2 GB works for the smallest catalog models; 4 GB+ for 0.5B–1.5B Q4_K_M models;
  6 GB+ is comfortable for 1.5B models at longer contexts. Every catalog entry lists
  `min_ram_mb` / `recommended_ram_mb`.
- Free storage roughly 1.2x the model file size.
- Optional: a Wi-Fi LAN if you want other devices to reach the API.

## Installation

**From GitHub Releases (recommended).** Download the latest signed APK from
[Releases](https://github.com/SecretArrow/LocalAI/releases), open it on your device, and allow
installs from this source when prompted. Release artifacts are built by CI and published with
SHA-256 checksums.

**Build it yourself with GitHub Actions (no local toolchain needed).** Fork or clone this
repository, push to your fork, and the `ci` and `release` workflows do the rest — see
[BUILD.md](BUILD.md). You can also build locally with JDK 17 and Android SDK 34.

## Quickstart

1. **Install and open the app.** Grant the notification permission when asked — it is required
   for the foreground service notification on Android 13+.
2. **Get a model.** Open **Models**, browse the catalog section, and download
   *SmolLM2 135M Instruct* (about 145 MB) for a fast first-run test — or tap **Import** to pick an
   existing `.gguf` file from your device.
3. **Start it.** Tap **Run** on the model card and wait for the state to become *Running*.
4. **Chat on the phone.** Open **Chat**, pick the running model, and send a message; tokens stream
   as they are generated.
5. **Serve the API.** Open **Server** and tap **Start**. The server listens on
   `http://127.0.0.1:8080` by default (loopback only). From a shell on the same network after
   enabling LAN access in Settings, replace `127.0.0.1` with the phone's LAN address shown on the
   Server screen.

```bash
# List models
curl http://127.0.0.1:8080/v1/models

# Chat completion (non-streaming)
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "smollm2-135m-instruct",
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }'

# Streaming completion
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "smollm2-135m-instruct",
    "messages": [{"role": "user", "content": "Tell me a short story."}],
    "stream": true
  }'

# Health check (no authentication required)
curl http://127.0.0.1:8080/api/health
```

Point any OpenAI SDK client at the phone by setting the base URL, for example:

```python
from openai import OpenAI

client = OpenAI(base_url="http://192.168.1.20:8080/v1", api_key="lai_...")
```

To connect other devices: enable **LAN access** (Settings, or the Server screen), enable
**authentication** (strongly recommended), then scan the QR code on the Server screen or pair the
device with a 6-digit code. The QR payload contains host, port, TLS and auth mode — never the
token itself. A browser on any LAN machine can also open the web console at the same address.

## Architecture

Three Gradle modules with a strict dependency direction:

```text
:app  (Android application: Compose UI, services, coordinator, pairing, mDNS, QR, export)
  └── :core  (Android library: domain models, Room DB, downloads, runtime + CPU backend, security, stats, logs)
        └── :api-server  (pure Kotlin/JVM: Ktor server, OpenAI-compatible + native routes, web console)
```

`:api-server` has no Android dependencies, so the whole HTTP surface is unit-testable on the JVM.
Manual dependency injection wires everything in `AppContainer`; there is no Hilt/KAPT. Data lives
in Room (5 tables) and DataStore; inference flows through an `InferenceBackend` abstraction with a
real CPU implementation and honest stubs for the rest. Full details, data flows, and extension
points: [ARCHITECTURE.md](ARCHITECTURE.md).

## Documentation

| Document | Contents |
|---|---|
| [BUILD.md](BUILD.md) | Prerequisites, build commands, CI pipelines, release process, native build notes |
| [API.md](API.md) | Complete HTTP API reference with examples (curl, Python, JS, Android) |
| [RUNTIME.md](RUNTIME.md) | Runtime architecture, CPU backend, memory rules, benchmark methodology, backend extension guide |
| [MODEL_FORMATS.md](MODEL_FORMATS.md) | GGUF details, quantization guidance, detected-but-unsupported formats, import behavior |
| [SECURITY.md](SECURITY.md) | Threat model, network security, authentication, TLS, API protections, disclosure policy |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Symptom, cause, fix tables for common problems |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module graph, package map, data flows, schema, threading model, extension points |
| [docs/CONTRACTS.md](docs/CONTRACTS.md) | Frozen internal interface contracts used by the implementation |

## CI/CD

You do not need a local Android toolchain to ship a binary. Everything is automated with GitHub
Actions:

- **ci.yml** — every push: `assembleDebug` plus unit tests, with Gradle and ccache caching,
  parallel jobs, concurrency cancellation, Gradle wrapper validation, and a secret scan.
- **release.yml** — on a `v*` tag or manual dispatch (and automatically when the `VERSION` file
  is bumped): builds a signed release APK/AAB using repository secrets
  (`ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`; falls back to debug signing when absent) and publishes a GitHub Release
  with checksums.
- **auto-fix.yml** — when CI fails, a bot parses the logs, applies deterministic fixes (optionally
  LLM-assisted via the `OPENAI_API_KEY` / `ZAI_API_KEY` / `OPENROUTER_API_KEY` secrets) and pushes
  `fix(auto): attempt N` commits, up to 5 attempts per human commit.

Typical release flow: edit `VERSION` (for example `0.2.0`), commit, push — release.yml tags and
publishes. See [BUILD.md](BUILD.md) for details.

## Privacy

Everything runs locally by default. The app does not upload prompts, conversations, models, API
requests, or API keys anywhere. The only outbound traffic is model catalog fetches and model
downloads you explicitly start (plus optional GitHub checks), and catalog requests never contain
conversation data. Request bodies are never logged (privacy mode is on by default), secrets are
excluded from Android backups, and generation happens entirely on the CPU of your device. Details:
[SECURITY.md](SECURITY.md).

## License

Released under the [MIT License](LICENSE). Third-party components: llama.cpp (MIT), Ktor
(Apache-2.0), OkHttp (Apache-2.0), ZXing (Apache-2.0), BouncyCastle (MIT), and AndroidX/Kotlin
libraries under their respective Apache-2.0 licenses. Catalog models carry their own licenses
(listed per entry in the catalog and shown in the app).
