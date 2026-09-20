# Security

How LocalAI Runtime protects the model server, its credentials, and your data. The design goal:
safe defaults with convenience as an explicit, warned, opt-in.

## Threat model (summary)

| Threat | Primary mitigations |
|---|---|
| Random devices on the LAN using your inference server | Loopback-only bind by default; LAN is opt-in with warning; recommended auth requirement on LAN |
| Token theft from device storage/backups | Android Keystore AES-GCM encryption, noBackupFilesDir, backup rules exclude secrets |
| Credential leakage through QR codes/logs/exports | QR contains no token; request bodies never logged; exports exclude secrets by default |
| Brute-forcing the API | Per-IP rate limiting; constant-time token verification; 401 without detail |
| Malicious/corrupted model files | SHA-256 + size verification before activation; models are data, never executed code |
| Resource exhaustion / crashes | Request size limits, timeouts, concurrency caps + queue, memory pre-check, crash supervision |
| Traffic sniffing on the LAN | Optional HTTPS (self-signed or imported certificate) |
| Unwanted discovery exposure | mDNS advertising is optional and can be disabled |

Not in scope: a determined attacker with the unlocked phone or root — no Android app can defend
against that; this is the same posture as password managers and similar local-server apps.

## Network security

- **Default bind is `127.0.0.1`** (port 8080, TLS 8443 optional). Nothing on any network can reach
  the server until the user changes this.
- **LAN access is explicit opt-in** (Settings or the Server screen). Enabling it shows a prominent
  warning: *"LAN access exposes your AI server to other devices on the same network."* The Server
  screen repeats a warning whenever LAN is enabled without authentication.
- **WAN exposure is never enabled** by the app; it binds either loopback or `0.0.0.0` on the
  device's interfaces — forwarding to the internet is something only a user could do deliberately
  outside the app.
- **Cleartext policy.** The app's network security config permits cleartext so HTTP can serve LAN
  clients (many LAN tools cannot do ad-hoc TLS). Rationale: the server is local, HTTP-on-LAN is a
  user opt-in, and HTTPS (self-signed or imported) is available for users who want encryption.
  Outbound app traffic (catalog, downloads) is HTTPS.
- **mDNS** discovery (`_localai._tcp.` via NsdManager) advertises only the service name, type, and
  port — no tokens, no model data. It has its own setting (on by default) and can be disabled at
  any time; registration is best-effort and never interferes with server operation.
- **Auth recommendation on LAN.** The UI nudges (warning banner) whenever `0.0.0.0` binding is
  active with auth mode `NONE`; enabling LAN + no auth is possible but deliberate.

## Authentication

Three modes (Settings, Security section): **None**, **API Key**, **Bearer token**.

- **Token generation**: 32 bytes from `java.security.SecureRandom`, hex-encoded, prefixed
  `lai_` (format: `lai_` + 64 hex chars). Pairing codes are 6-digit SecureRandom values.
- **Storage**: tokens are stored as files in `noBackupFilesDir/secrets/`, each encrypted with
  AES/GCM using an Android Keystore key (alias `localai_master`), random IV prepended to the
  ciphertext. The Keystore key never leaves secure hardware where available.
- **Verification**: incoming tokens are compared against the stored hash (SHA-256) using a
  constant-time equality check — no early-exit string comparison.
- **Display**: a token is shown in full exactly once at generation/pairing time; afterwards only a
  prefix is displayed.
- **Lifecycle**: regenerate and revoke at any time (Settings for the API token, Devices screen for
  paired-device tokens). Revocation is immediate for future requests.
- **Enforcement**: `Authorization: Bearer <token>` (and `X-API-Key` in API_KEY mode) on all `/v1`
  and `/api` routes except `GET /api/health` and the web console root. Failures return a generic
  `401` without revealing which part was wrong.

## TLS

- **Modes**: HTTP only (default), HTTPS only, or HTTP + HTTPS simultaneously (HTTP stays on the
  main port; TLS runs on its own port, default 8443).
- **Self-signed generation**: on-device via BouncyCastle — RSA 2048, self-signed X500 certificate,
  3650-day validity, returned/stored as PEM. Generation happens locally; nothing is sent anywhere.
- **Import**: users can import an existing `certificate.pem` + `private-key.pem` pair (for example
  one signed by their own CA).
- **Key handling**: the private key is stored encrypted under the same Android Keystore master-key
  scheme as tokens — never in plain text, never in preferences.
- **Client note**: self-signed certificates trigger browser/client warnings by design; distribute
  trust deliberately (pin the CA, or accept the fingerprint once). This is inherent to local
  self-signed TLS and is disclosed in the UI.

## API protections

| Protection | Default | Notes |
|---|---|---|
| Rate limiting | 60 req/min general, 10 req/min generation, per client IP | Token bucket; `429` on excess |
| Max request size | 10 MB | Larger bodies rejected before parsing |
| Max prompt length | 200,000 characters | Guards tokenizer/memory abuse |
| Request timeout | 300 s | Long generations still complete; nothing hangs forever |
| Concurrency cap | 4 concurrent generations | Global semaphore in the coordinator |
| Queue size | 16 | Overflow returns `busy` (503), never unbounded growth |
| Auth exemption | `/api/health`, `/` only | Everything else enforces the configured mode |

The engine additionally refuses model starts when the memory pre-check fails (507), so a request
cannot OOM the device (see [RUNTIME.md](RUNTIME.md)).

## Data protection

- **Backups**: Android backup/data-extraction rules exclude secrets (`noBackupFilesDir`, token
  files) and model files — restoring on another device never carries credentials or multi-GB
  models silently.
- **Privacy mode logging** (on by default): request/response bodies are **never** logged. API logs
  contain method, path, status, latency, client IP, model, and approximate token counts only. The
  app log (ring buffer + rotated file, 2 MB cap) follows the same rule.
- **Exports**: settings/model-config exports never include tokens or keys unless the user
  explicitly asks for a secret-bearing export — which the standard export paths do not offer.
- **Telemetry**: none. No analytics, no crash reporting, no outbound requests other than catalog
  fetches, model downloads you start, and (in CI) GitHub API calls made by the workflows — never
  from the app.
- **Prompts/conversations**: processed in memory and handed to the local model; never uploaded.

## QR codes and pairing

- **QR connect payloads** contain host, port, TLS flag, and auth mode — **never the token**
  (`{"host": "192.168.1.20", "port": 8080, "tls": false, "auth": "BEARER"}`). Scanning gets a
  client connected to the endpoint; the credential is delivered separately.
- **Pairing flow**: the app shows a 6-digit code (SecureRandom) valid for **60 seconds**; the
  client presents the code (via `/api` pairing request or typed manually), the user approves on
  the phone, and a **per-device token** is generated and displayed once for transfer.
- **Per-device tokens**: each paired device has its own token stored only as a SHA-256 hash with a
  prefix for display; devices can be renamed and individually revoked from the Devices screen;
  last-seen timestamps are tracked.
- **Revocation** is immediate: the hash is removed and verification fails on the next request.

## Model file safety

- **Checksum verification**: every catalog download carries a SHA-256 that is verified
  (incrementally, across resumes) before the model can be activated; size is checked even when no
  checksum is known. See [MODEL_FORMATS.md](MODEL_FORMATS.md).
- **Never execute downloaded code**: model files are pure data. They are loaded exclusively
  through the llama.cpp runtime, which parses tensors — nothing from a model file is ever
  executed, `dlopen`'d, or interpreted as code. There is no plugin/scripting path for model
  content.
- **Path handling**: API routes address models by database id, never by filesystem path; there is
  no endpoint that writes to caller-chosen paths. Downloads write into the app's private storage
  via `.part` temp files that are renamed only after verification — no partial file is ever
  activated, and no archive extraction of model files exists in this build.

## Responsible disclosure

Found a security issue? Please report it responsibly:

1. Open a private security advisory on GitHub
   (`https://github.com/SecretArrow/LocalAI/security/advisories/new`), or contact the maintainers
   via the repository's contact channel.
2. Include reproduction steps, affected versions (see the About screen / `VERSION` file), and, if
   possible, a proof of concept.
3. Do not open public issues for exploitable vulnerabilities.

We aim to acknowledge reports within 72 hours and will credit reporters in release notes unless
anonymity is requested. Out of scope: attacks requiring physical access to an unlocked device,
and denial of service purely by exhausting the phone's CPU/RAM while the user deliberately runs
models locally.
