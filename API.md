# API Reference

LocalAI Runtime serves a local HTTP API with two surfaces:

- **OpenAI-compatible** routes under `/v1` — drop-in for OpenAI SDKs and existing tools.
- **Native** management routes under `/api` — model lifecycle, runtime info, and logs.

The server is Ktor (CIO engine) embedded in the app. The same JSON API is served over plain HTTP
and, when enabled, HTTPS.

| Item | Default | Notes |
|---|---|---|
| HTTP base URL | `http://127.0.0.1:8080` | Loopback only by default |
| HTTPS base URL | `https://<host>:8443` | Optional; self-signed or imported certificate |
| Bind address | `127.0.0.1` | `0.0.0.0` (LAN) is opt-in via Settings and comes with a warning |
| Content type | `application/json` | Streaming responses use `text/event-stream` |
| Web console | `http://127.0.0.1:8080/` | Chat / Models / API / Logs / Metrics tabs |

Throughout this document, replace `127.0.0.1:8080` with the endpoint shown on the app's Server
screen (loopback for on-device clients, the phone's LAN IP for other devices, `https://…:8443`
when TLS is enabled). The API Docs screen inside the app generates all examples dynamically from
the currently configured endpoint.

## Authentication

Configure the mode in Settings (or via pairing); it applies to both `/v1` and `/api` routes.

| Mode | Accepted credentials | Verification |
|---|---|---|
| `NONE` | none | Any request is accepted (fine for loopback-only use) |
| `API_KEY` | `Authorization: Bearer <token>` or `X-API-Key: <token>` | Constant-time hash comparison |
| `BEARER` | `Authorization: Bearer <token>` | Constant-time hash comparison |

Tokens look like `lai_<64 hex characters>` (32 bytes from `SecureRandom`), are shown once when
generated, and can be regenerated or revoked at any time. See [SECURITY.md](SECURITY.md) for
generation, storage, and pairing details.

Exemptions: `GET /api/health` and the web console root (`GET /`) never require authentication, so
health checks and first-contact browser access always work.

### Examples

No authentication (default, loopback):

```bash
curl http://127.0.0.1:8080/v1/models
```

With a token:

```bash
curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer lai_1f0e...c9b2"
# or, in API_KEY mode additionally:
curl http://127.0.0.1:8080/v1/models \
  -H "X-API-Key: lai_1f0e...c9b2"
```

Failure (any protected route, wrong or missing token):

```json
HTTP/1.1 401 Unauthorized
{
  "error": {
    "message": "Invalid or missing API token",
    "type": "authentication_error",
    "code": "invalid_api_key"
  }
}
```

## Rate limits

A token bucket per client IP protects the phone from overload:

| Route class | Limit |
|---|---|
| General endpoints | 60 requests/minute per IP |
| Generation endpoints (`/v1/chat/completions`, `/v1/completions`) | 10 requests/minute per IP |

Exceeding a limit returns `429` (see error table). Additionally, the engine caps concurrent
generations (`apiMaxConcurrent`, default 4) with a queue (default 16); when the queue is full the
server returns `503` with code `busy` rather than accumulating work.

## Error format

All errors use a single JSON shape:

```json
{
  "error": {
    "message": "Human-readable description",
    "type": "error_class",
    "code": "machine_code"
  }
}
```

| HTTP | `code` | `type` | Meaning |
|---|---|---|---|
| 400 | `invalid_request` | `invalid_request_error` | Malformed JSON, missing fields, unsupported parameters |
| 401 | `invalid_api_key` | `authentication_error` | Missing/wrong token while auth is enabled |
| 404 | `model_not_found` | `invalid_request_error` | Unknown model id (start it first) |
| 404 | `not_found` | `invalid_request_error` | Unknown route |
| 429 | `rate_limit_exceeded` | `rate_limit_error` | Token bucket exhausted for this client |
| 500 | `engine_error` | `server_error` | Generation or runtime failure |
| 501 | `not_supported` | `not_supported` | Capability not present in this build (e.g. embeddings) |
| 503 | `busy` | `server_error` | Concurrency limit and queue are full |
| 507 | `insufficient_memory` | `server_error` | Not enough RAM to load the model |

Request bodies are never logged. Only method, path, status, latency, client IP, model, and token
counts are recorded.

## Native API (`/api`)

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/health` | No | Liveness probe |
| GET | `/api/status` | Yes | Server uptime, running models, request/token counters |
| GET | `/api/models` | Yes | All registered models with state |
| GET | `/api/models/{id}` | Yes | One model |
| POST | `/api/models/{id}/start` | Yes | Load and start a model |
| POST | `/api/models/{id}/stop` | Yes | Unload a running model |
| POST | `/api/models/{id}/restart` | Yes | Stop then start |
| GET | `/api/runtime` | Yes | Runtime/engine statistics for running models |
| GET | `/api/backends` | Yes | Backend availability on this device |
| GET | `/api/downloads` | Yes | Download list (placeholder — see note) |
| GET | `/api/devices` | Yes | Paired devices (placeholder — see note) |
| GET | `/api/logs?limit=100` | Yes | Recent request log entries |

Honest limitations (the server module has no visibility into app-internal subsystems, so these two
routes return stable placeholder shapes rather than fake data): `/api/downloads` always returns
`{"downloads": []}` — download state is visible in the app UI, not through the server; and
`/api/devices` returns `{"devices": []}` — paired devices are managed from the Devices screen.
Both are stable contracts that can be filled in later without breaking clients.

### `GET /api/health`

No authentication. Ideal for uptime probes and connectivity tests.

```bash
curl http://127.0.0.1:8080/api/health
```

```json
{
  "status": "ok",
  "version": 1
}
```

### `GET /api/status`

```json
{
  "uptimeMs": 184213,
  "running": ["qwen2.5-0.5b-instruct"],
  "requests": 124,
  "tokensGenerated": 45293,
  "server": {
    "http": "127.0.0.1:8080",
    "https": "127.0.0.1:8443",
    "authMode": "BEARER"
  }
}
```

### `GET /api/models`

```json
{
  "models": [
    {
      "id": "qwen2.5-0.5b-instruct",
      "name": "Qwen2.5 0.5B Instruct",
      "version": "1.0",
      "format": "GGUF",
      "quantization": "Q4_K_M",
      "sizeBytes": 491400032,
      "state": "RUNNING",
      "backend": "cpu",
      "contextLength": 4096,
      "minRamMb": 2048,
      "backends": ["cpu"]
    },
    {
      "id": "smollm2-135m-instruct",
      "name": "SmolLM2 135M Instruct",
      "version": "1.0",
      "format": "GGUF",
      "quantization": "Q8_0",
      "sizeBytes": 144811072,
      "state": "INSTALLED",
      "backend": null,
      "contextLength": 8192,
      "minRamMb": 1024,
      "backends": ["cpu"]
    }
  ]
}
```

### `GET /api/models/{id}`

Returns the same model object as above for a single id, or `404` with `model_not_found`.

### `POST /api/models/{id}/start` | `/stop` | `/restart`

No request body required. Start loads the model through the best available backend (CPU today)
after memory checks; stop unloads it; restart is stop + start. All three return the updated model
object.

```bash
curl -X POST http://127.0.0.1:8080/api/models/qwen2.5-0.5b-instruct/start \
  -H "Authorization: Bearer lai_1f0e...c9b2"
```

Failure examples: `507 insufficient_memory` when the RAM pre-check fails, `400`/`404` for unknown
or uninstalled models.

### `GET /api/runtime`

Per-model engine statistics (tokens/sec, memory, active/queued requests) plus totals.

```json
{
  "stats": [
    {
      "modelId": "qwen2.5-0.5b-instruct",
      "state": "RUNNING",
      "backend": "cpu",
      "tokensPerSecond": 9.8,
      "promptTokensPerSecond": 71.2,
      "memoryUsageBytes": 612368384,
      "activeRequests": 1,
      "queuedRequests": 0,
      "totalRequests": 12,
      "generatedTokens": 2048
    }
  ]
}
```

### `GET /api/backends`

Availability of every backend type on this device, with honest reasons.

```json
{
  "backends": [
    {
      "type": "CPU",
      "availability": "AVAILABLE",
      "reason": "",
      "details": "llama.cpp b4755, 8 cores"
    },
    {
      "type": "GPU_VULKAN",
      "availability": "UNAVAILABLE",
      "reason": "Vulkan compute integration not bundled in this build; CPU runtime is active",
      "details": "Device reports Vulkan 1.1.128"
    }
  ]
}
```

Availability values: `AVAILABLE` (real execution), `SUPPORTED` (device has it but not bundled),
`UNAVAILABLE`, `UNKNOWN`. See [RUNTIME.md](RUNTIME.md) for the detection methods.

### `GET /api/logs?limit=100`

Returns the most recent request log entries (server-side ring buffer, capped at 500 entries;
`limit` clamps to 100 by default). Shape matches `ServerLogEntry`: `ts`, `method`, `path`,
`status`, `latencyMs`, `model`, `client`, `error`, `tokensIn`, `tokensOut`.

```bash
curl "http://127.0.0.1:8080/api/logs?limit=100" -H "Authorization: Bearer lai_..."
```

```json
{
  "logs": [
    {
      "ts": 1758326400123,
      "method": "POST",
      "path": "/v1/chat/completions",
      "status": 200,
      "latencyMs": 3841,
      "model": "qwen2.5-0.5b-instruct",
      "client": "192.168.1.30",
      "error": null,
      "tokensIn": 34,
      "tokensOut": 48
    }
  ]
}
```

## OpenAI-compatible API (`/v1`)

Designed to work unmodified with OpenAI SDK clients: point them at the phone's base URL and use
any string as the API key when local auth is `NONE`.

| Method | Path | Description |
|---|---|---|
| GET | `/v1/models` | List registered models |
| POST | `/v1/chat/completions` | Chat completion, streaming and non-streaming |
| POST | `/v1/completions` | Legacy text completion (prompt field) |
| POST | `/v1/embeddings` | Not supported — always `501` |

### `GET /v1/models`

```json
{
  "object": "list",
  "data": [
    {
      "id": "qwen2.5-0.5b-instruct",
      "object": "model",
      "created": 1758326400,
      "owned_by": "local"
    },
    {
      "id": "smollm2-135m-instruct",
      "object": "model",
      "created": 1758326400,
      "owned_by": "local"
    }
  ]
}
```

### `POST /v1/chat/completions`

Request (all fields optional except `model` and `messages`):

```json
{
  "model": "qwen2.5-0.5b-instruct",
  "messages": [
    { "role": "system", "content": "You are a concise assistant." },
    { "role": "user", "content": "Say hello in one sentence." }
  ],
  "temperature": 0.7,
  "top_p": 0.9,
  "top_k": 40,
  "min_p": 0.05,
  "repeat_penalty": 1.1,
  "seed": -1,
  "max_tokens": 128,
  "stream": false,
  "stop": []
}
```

Supported parameters: `model`, `messages` (roles `system`/`user`/`assistant`), `temperature`,
`top_p`, `top_k`, `min_p`, `repeat_penalty`, `seed` (`-1` = random), `max_tokens`, `stream`,
`stop`. Unknown fields are ignored for forward compatibility.

Non-streaming response:

```json
{
  "id": "chatcmpl-local",
  "object": "chat.completion",
  "created": 1758326400,
  "model": "qwen2.5-0.5b-instruct",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you today?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 34,
    "completion_tokens": 12,
    "total_tokens": 46
  }
}
```

Streaming response (`"stream": true`) is Server-Sent Events with `Content-Type:
text/event-stream`. Each `data:` line carries one OpenAI-style chunk; the stream ends with
`data: [DONE]`:

```text
data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1758326400,"model":"qwen2.5-0.5b-instruct","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1758326400,"model":"qwen2.5-0.5b-instruct","choices":[{"index":0,"delta":{"content":"lo!"},"finish_reason":null}]}

data: {"id":"chatcmpl-local","object":"chat.completion.chunk","created":1758326400,"model":"qwen2.5-0.5b-instruct","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

Concatenating the `delta.content` values reproduces the full message. Errors during a stream are
emitted as a final JSON error chunk before the stream closes.

```bash
curl -N http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5-0.5b-instruct",
    "messages": [{"role": "user", "content": "Tell me a short story."}],
    "stream": true
  }'
```

(`-N` disables curl buffering so chunks appear as they are generated.)

If the requested model is not running, the server attempts to surface a clear `404
model_not_found` error — start the model first from the app or via `POST /api/models/{id}/start`.

### `POST /v1/completions`

Legacy completion endpoint. Accepts a `prompt` (string) plus the same generation parameters;
delegates to the same engine.

```bash
curl http://127.0.0.1:8080/v1/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "smollm2-135m-instruct",
    "prompt": "The capital of France is",
    "max_tokens": 16,
    "stream": false
  }'
```

```json
{
  "id": "cmpl-local",
  "object": "text_completion",
  "created": 1758326400,
  "model": "smollm2-135m-instruct",
  "choices": [
    {
      "index": 0,
      "text": " Paris, a city known for",
      "finish_reason": "length"
    }
  ],
  "usage": {
    "prompt_tokens": 6,
    "completion_tokens": 16,
    "total_tokens": 22
  }
}
```

### `POST /v1/embeddings`

Always returns `501` — the bundled CPU runtime does not expose embedding generation, and this
build reports that honestly instead of returning fabricated vectors.

```json
HTTP/1.1 501 Not Implemented
{
  "error": {
    "message": "Embeddings require a runtime with embedding support; not available in this build",
    "type": "not_supported",
    "code": "not_supported"
  }
}
```

## Web console

Open `http://127.0.0.1:8080/` (or the LAN URL) in any browser on a machine that can reach the
phone. The console is a single self-contained page served by the app (no external assets) with
five tabs:

| Tab | Function |
|---|---|
| Chat | Model dropdown (`/v1/models`), streaming conversation via fetch + SSE parsing |
| Models | Table of models with state and Start/Stop buttons (`/api/models`) |
| API | Endpoint reference with curl examples generated from `location.host` |
| Logs | Live request log (polls `/api/logs?limit=100`) |
| Metrics | Requests, tokens, uptime (polls `/api/status`) |

Connection problems show a visible "Server unreachable" banner; the console renders no fake data.

## Client examples

The examples below use `BASE` as a placeholder. On the phone itself, `BASE=http://127.0.0.1:8080`.
From another device, use the LAN address from the Server screen, for example
`http://192.168.1.20:8080` or `https://192.168.1.20:8443`. Build the base URL dynamically from
settings/pairing data rather than hardcoding it.

### curl

```bash
BASE=http://192.168.1.20:8080
TOKEN=lai_1f0e...c9b2   # omit when auth is NONE

curl $BASE/v1/models -H "Authorization: Bearer $TOKEN"

curl $BASE/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5-0.5b-instruct","messages":[{"role":"user","content":"Hello"}]}'
```

### Python (OpenAI SDK)

Any OpenAI SDK works by changing the base URL. Use any non-empty string as the API key when local
auth is `NONE`:

```python
from openai import OpenAI

client = OpenAI(base_url="http://192.168.1.20:8080/v1", api_key="lai_1f0e...c9b2")

response = client.chat.completions.create(
    model="qwen2.5-0.5b-instruct",
    messages=[{"role": "user", "content": "Say hello in one sentence."}],
)
print(response.choices[0].message.content)

# Streaming
stream = client.chat.completions.create(
    model="qwen2.5-0.5b-instruct",
    messages=[{"role": "user", "content": "Tell me a short story."}],
    stream=True,
)
for chunk in stream:
    if chunk.choices[0].delta.content:
        print(chunk.choices[0].delta.content, end="", flush=True)
```

### JavaScript (browser / Node 18+)

```javascript
const BASE = "http://192.168.1.20:8080";
const headers = {
  "Content-Type": "application/json",
  "Authorization": "Bearer lai_1f0e...c9b2", // omit when auth is NONE
};

// Non-streaming
const res = await fetch(`${BASE}/v1/chat/completions`, {
  method: "POST",
  headers,
  body: JSON.stringify({
    model: "qwen2.5-0.5b-instruct",
    messages: [{ role: "user", content: "Say hello in one sentence." }],
  }),
});
const json = await res.json();
console.log(json.choices[0].message.content);

// Streaming (SSE over fetch)
const streamRes = await fetch(`${BASE}/v1/chat/completions`, {
  method: "POST",
  headers,
  body: JSON.stringify({
    model: "qwen2.5-0.5b-instruct",
    messages: [{ role: "user", content: "Tell me a short story." }],
    stream: true,
  }),
});
const reader = streamRes.body.getReader();
const decoder = new TextDecoder();
let buffer = "";
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });
  const lines = buffer.split("\n");
  buffer = lines.pop();
  for (const line of lines) {
    if (!line.startsWith("data: ")) continue;
    const payload = line.slice(6);
    if (payload === "[DONE]") break;
    const chunk = JSON.parse(payload);
    process.stdout.write(chunk.choices[0].delta.content ?? "");
  }
}
```

### Android (OkHttp)

```kotlin
val base = "http://192.168.1.20:8080"   // from pairing payload / user input
val client = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.MINUTES)   // long generations need a generous timeout
    .build()

val body = """
    {
      "model": "qwen2.5-0.5b-instruct",
      "messages": [{"role": "user", "content": "Say hello in one sentence."}]
    }
""".trim()

val request = Request.Builder()
    .url("$base/v1/chat/completions")
    .header("Authorization", "Bearer $token")   // omit when auth is NONE
    .post(body.toRequestBody("application/json".toMediaType()))
    .build()

client.newCall(request).execute().use { response ->
    println(response.body!!.string())
}
```

Note for Android clients: plain-HTTP LAN URLs require the **client app** to allow cleartext
traffic for that host (its own `network_security_config.xml`); LocalAI Runtime's server config
does not change your client app's policy. Browsers, curl, and desktop tools are unaffected.

## SSE client notes

- Responses use `Content-Type: text/event-stream`; each event is a `data: <json>` line, and the
  stream terminates with the literal line `data: [DONE]`.
- Disable client-side buffering (`curl -N`, `X-Accel-Buffering: no` proxies, flush on read).
- Keep read timeouts generous — tokens arrive steadily but a long generation can outlast short
  default timeouts (the server-side request timeout defaults to 300 s).
- Client disconnects cancel generation server-side; there is no orphaned background work.
- A JSON error chunk may precede `[DONE]` when generation fails mid-stream; handle both shapes.
