# Troubleshooting

Symptom → cause → fix tables for the most common problems. If your issue is about producing a
binary rather than running the app, see [BUILD.md](BUILD.md) first.

## Model will not start

| Symptom | Likely cause | Fix |
|---|---|---|
| Error "Not enough memory. Required X, Available Y" | RAM pre-check failed: `fileSize * 1.15 + contextLength * 2 KB` exceeds available RAM | Stop other running models (Home screen), reduce Context length in the model's runtime config, or download a smaller quantization (Q4_K_M → Q3_K_M, or a smaller model). Catalog entries list `min_ram_mb` |
| Model state `FAILED` after repeated restarts | Crash supervision exhausted its 3 attempts (backoff 2 s/8 s/32 s) | Open Model Detail and read the last error; typical fixes: shorter context, fewer threads, different quantization. Then Start again manually |
| "Native CPU runtime unavailable" | `liblocalai_runtime.so` could not load (see Native library load failure below) | Use an `arm64-v8a` or `x86_64` device; reinstall the APK matching your ABI |
| Start returns `404 model_not_found` via API | Model is not installed/registered | Download or import the model first, or check the id with `GET /v1/models` |
| Start returns `507` via API | Same memory pre-check, surfaced over HTTP | Same as the RAM row above |

## Downloads

| Symptom | Likely cause | Fix |
|---|---|---|
| Download stuck in `PAUSED` with no error | Wi-Fi-only gating paused it on mobile data | Connect to Wi-Fi, or disable Wi-Fi-only in Settings (Downloads section), then Resume |
| Download restarts from zero after resume | Server ignored the `Range` request or `ETag`/`Last-Modified` changed (file was replaced upstream) | Expected behavior — the app restarts only when the server forces it; otherwise resumes with `If-Range` |
| Download fails with network error, retries stop | 3 automatic retries exhausted (2 s/8 s/32 s backoff) | Check connectivity, then tap Retry. All partial progress in the `.part` file is kept |
| "Verification failed — checksum mismatch" | SHA-256 of the completed file differs from the catalog value (corrupted transfer or updated upstream file) | Delete and download again. If it persists, the catalog checksum is stale — refresh the catalog; the model is never activated on mismatch |
| "Insufficient storage" | Free space below ~1.2x file size | Clear cache/temp from the Storage screen or free device space, then retry |
| Custom URL download never starts | HEAD probe failed (server does not answer HEAD or blocks it) | Use a direct HTTPS URL that answers HEAD with `Content-Length`; range support is probed automatically |

## API server

| Symptom | Likely cause | Fix |
|---|---|---|
| "Port already in use" on start | Another process (or a second app instance) holds the port | Change the port in Settings (API section) and restart the server |
| `curl: (7) Failed to connect` from the phone shell | Server not running, or bound to a different interface | Start it on the Server screen; verify with `curl http://127.0.0.1:8080/api/health` |
| LAN device cannot connect | Bind address still `127.0.0.1` | Enable LAN access (bind `0.0.0.0`); confirm with the LAN URL shown on the Server screen |
| LAN device cannot connect, bind is `0.0.0.0` | Client on a different subnet/VLAN, AP isolation, or firewall | Same network required; disable AP/client isolation on the router; allow the port in any firewall between the devices |
| LAN requests return `401` | Authentication is enabled (recommended on LAN) | Send `Authorization: Bearer <token>` (or `X-API-Key`); pair the device or copy the token from Settings |
| Requests return `429` | Rate limit: 60 req/min general, 10 req/min generation per IP | Slow down or batch prompts; the limit protects the phone |
| HTTPS client shows certificate warnings | Self-signed certificate (by design) | Accept the fingerprint in the client, install your CA on the client, or import a CA-signed PEM pair into the app |
| `503 busy` responses | Concurrency cap (4) and queue (16) are full | Retry after in-flight generations finish, or raise limits in Settings (at your own risk) |

## Performance

| Symptom | Likely cause | Fix |
|---|---|---|
| Generation is slow | Threads, quantization, or context | Set CPU threads to Auto (min(cores, 8)); prefer Q4_K_M; keep context as short as needed — KV cache grows with context; avoid parallel requests on small models |
| Generation fast at first, then slows and stutters | Thermal throttling, or another model running | Stop the other model; let the device cool; reduce threads (less heat, often similar throughput) |
| First response after Start is slow | Prompt processing of a long system prompt + cold page cache | Normal for CPU inference; mmap makes subsequent loads faster. Shorten the system prompt |
| App feels sluggish while a model runs | Inference competes with the UI on the same cores | Reduce threads by 1–2 in the model's runtime config |

## Background operation

| Symptom | Likely cause | Fix |
|---|---|---|
| Runtime/service stops when screen turns off or app is backgrounded | OEM battery optimization killed the app | Disable battery optimization for LocalAI (Settings → Apps → Battery); on Xiaomi/Huawei/Samsung also enable "Unrestricted" background activity in the vendor's power manager |
| Model keeps running but downloads stall in background | Doze restricts network for background work | Keep the foreground notification active; WorkManager resumes downloads on the next window; check Downloads screen after |
| Notification "LocalAI" disappears after a while | The service stopped itself (no model running and server stopped) — or the OS killed it | Expected in the first case; in the second, apply the battery optimization fix above |
| Auto-start on boot does nothing | `startOnBoot` off, or OEM blocks boot receivers of non-started apps | Enable Start on boot in Settings (Background); on restrictive OEMs also allow auto-start in the vendor settings; Android may still defer it |

## Native library load failure

| Symptom | Likely cause | Fix |
|---|---|---|
| CPU backend reported unavailable; log shows `System.loadLibrary` failure | APK ABI does not match the device (for example 32-bit-only `armeabi-v7a` device, or a stripped APK) | Install the official APK from GitHub Releases (ships `arm64-v8a` + `x86_64`); on 32-bit-only devices there is no supported build |
| Works on the emulator, unavailable on device (or vice versa) | Emulator is `x86_64`, device is `arm64-v8a` — both ABIs are shipped, but side-loaded "lite" APKs sometimes strip one | Use the release APK unmodified |
| `UnsatisfiedLinkError` right after an app update | Stale native handle across process upgrade | Full stop and relaunch of the app; if it persists, reinstall the APK and re-import nothing — models are kept in app storage |

## CI build failure

| Symptom | Likely cause | Fix |
|---|---|---|
| ci.yml red on `main` | Compile/test failure | Open the run, download the log artifact (always uploaded). The auto-fix bot may already be working — watch for `fix(auto): attempt N` commits (max 5 per human commit) |
| Five `fix(auto)` commits and still red | Auto-fix exhausted its attempts | A human fix is required; the failure needs manual reading of the Gradle log |
| Release marked `debug-signed` | `ANDROID_KEYSTORE_*` secrets missing | Configure the four secrets, re-run release.yml |
| Wrapper validation failure | `gradle-wrapper.jar` tampered/missing | Restore the committed wrapper; never regenerate it locally |
| Native build step fails in CI only | Runner network hiccup fetching llama.cpp b4755, or ccache corruption | Re-run the job; ccache keys self-heal on the next clean build |
