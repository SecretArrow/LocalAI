# Build Guide

How to produce LocalAI binaries. The short version: **you do not need a local Android
toolchain** — GitHub Actions builds, tests, signs, and releases everything. Local builds are
optional and described second.

## Prerequisites (local builds only)

| Requirement | Version | Notes |
|---|---|---|
| JDK | 17 | Toolchain level for all modules; a newer JDK running Gradle also works with `org.gradle.java.installations.paths` but 17 is the supported target |
| Android SDK | API 34 (compileSdk/targetSdk) | `platforms;android-34`, `build-tools` matching AGP |
| Android NDK | 27.0.12077973 | Pinned in `core/build.gradle.kts` |
| CMake | 3.22.1 | Bundled SDK CMake is fine |
| Gradle | 8.9 | Use the checked-in wrapper `./gradlew` — do not use a system Gradle |

Accept SDK licenses once:

```bash
yes | sdkmanager --licenses
```

Toolchain summary: AGP 8.5.2, Kotlin 2.0.0, KSP 2.0.0-1.0.21, Compose BOM 2024.09.03, Room 2.6.1,
Ktor 2.3.12, OkHttp 4.12.0, Gradle 8.9. All versions come from `gradle/libs.versions.toml`.

## Build commands

```bash
# Debug APK (outputs to app/build/outputs/apk/debug/)
./gradlew :app:assembleDebug

# Run all unit tests (JVM only — no emulator required)
./gradlew test

# Release APK/AAB (unsigned unless signing env vars are set; see below)
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

Install a debug APK on a connected device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses application id suffix `.debug` so it can coexist with a release install.
Release builds are minified and resource-shrunk with ProGuard rules from `app/proguard-rules.pro`.

## Native build details

The CPU inference backend is a thin JNI library, `liblocalai_runtime.so`, built by CMake from
`core/src/main/cpp/`:

- **llama.cpp is fetched at configure time** via CMake `FetchContent` from the pinned tag
  `b4755` (`https://github.com/ggml-org/llama.cpp/archive/refs/tags/b4755.tar.gz`). The tag is
  passed as `-DLLAMA_TAG=b4755` from Gradle and is deliberately pinned for JNI API compatibility.
- **ABIs**: `arm64-v8a` (real devices) and `x86_64` (emulator). `armeabi-v7a` is intentionally not
  shipped — llama.cpp performance on 32-bit ARM is poor and most devices in scope are 64-bit.
- **Flags**: `-O3 -fexceptions -frtti -std=c++17`, `ANDROID_STL=c++_shared`, and llama.cpp options
  trimmed for size and reproducibility (`GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`,
  `GGML_NATIVE=OFF`, `LLAMA_CURL=OFF`, no examples/tests/server).
- **First build cost**: fetching and compiling llama.cpp plus the bridge takes roughly 5–15 minutes
  per ABI on a modern CI runner (faster with ccache). Subsequent builds are incremental.

The native library is optional at runtime: if `System.loadLibrary("localai_runtime")` fails (for
example on an unsupported ABI), the app stays usable and reports the CPU backend as unavailable
with the load error — it never crashes.

## CI pipelines

All workflows live in `.github/workflows/`. None of them require secrets to run a normal build.

### ci.yml — continuous integration

- **Triggers**: every push to `main`, every pull request.
- **Jobs**: `assembleDebug` for the app module plus `test` for the JVM test suites of all modules.
- **Caching**: Gradle caches (wrapper + dependencies + configuration cache where applicable) and
  **ccache** for the native toolchain, keyed on the `LLAMA_TAG`/source hash so a change to the
  pinned llama.cpp version or bridge sources rebuilds native code but unrelated changes reuse it.
- **Concurrency**: in-progress runs for the same branch/ref are cancelled when a new push arrives.
- **Hygiene**: the Gradle wrapper JAR is validated against the checksum in
  `gradle/wrapper/gradle-wrapper.properties`, and a secret scan blocks merges that would commit
  tokens or keystores.

### release.yml — signed releases

- **Triggers**: pushing a tag matching `v*`, manual workflow dispatch, and automatically when a
  commit bumps the `VERSION` file (the workflow creates the tag itself).
- **What it does**: builds release APK and AAB, signs them, and publishes a GitHub Release with
  SHA-256 checksums of every artifact.
- **Signing inputs** (repository secrets / environment variables):

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE_B64` | Base64-encoded PKCS#12/JKS keystore, decoded by the workflow |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | Password for that key |

  When any of these are absent the workflow falls back to **debug signing** and labels the release
  accordingly, so the pipeline never hard-fails for lack of secrets. Do not distribute
  debug-signed builds to other people.

### auto-fix.yml — self-healing CI

- **Trigger**: a failing `ci` run on `main`.
- **Behavior**: checks out the commit, parses the Gradle log (the raw log is always uploaded as an
  artifact by ci.yml), applies a set of deterministic fixes for known error patterns (missing
  import, signature mismatch, resource typo, and similar), optionally consults an LLM when one of
  `OPENAI_API_KEY`, `ZAI_API_KEY`, or `OPENROUTER_API_KEY` is configured, and pushes a commit
  `fix(auto): attempt N` to `main`.
- **Limits**: at most **5 auto-fix attempts per human commit**; after that the workflow stops and
  leaves the failure for a person. The GitHub token is used only for the push — it is never
  committed into the repository.

## Release process

Day-to-day releases are a two-line change:

1. Edit the `VERSION` file at the repository root (single line, semantic version, currently
   `0.1.0`).
2. Commit and push to `main`.

release.yml detects the bump, tags `v<version>`, builds, signs, and publishes the GitHub Release
with checksums. `versionCode` is derived from the semantic version plus a CI build number.

Alternative: tag manually.

```bash
git tag v0.2.0
git push origin v0.2.0
```

Or run the release workflow from the Actions tab with a manual dispatch.

To enable real signing, generate a keystore once and add the four secrets above:

```bash
keytool -genkeypair -v -keystore localai-release.jks -alias localai \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 localai-release.jks   # paste as ANDROID_KEYSTORE_B64
```

## Build troubleshooting quick list

| Symptom | Cause | Fix |
|---|---|---|
| `Failed to find CMake` / NDK errors locally | NDK 27.0.12077973 or CMake 3.22.1 not installed | `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"` |
| `You have not accepted the license agreements` | SDK licenses pending | `yes \| sdkmanager --licenses` |
| Gradle wrapper validation fails in CI | `gradle-wrapper.jar` modified or not committed | Restore from the repository; never replace it with a locally generated wrapper |
| Native build cannot fetch llama.cpp | Network egress blocked from the runner | Re-run; the tarball URL is `https://github.com/ggml-org/llama.cpp/archive/refs/tags/b4755.tar.gz` |
| Build works in CI but not locally (or vice versa) | Different JDK/SDK/NDK versions | Match the versions in the table above; CI logs print their toolchain versions |
| Release artifacts marked `debug-signed` | Signing secrets not configured | Add the four `ANDROID_KEYSTORE_*` secrets, then re-run release.yml |
| CI keeps failing after 5 `fix(auto)` commits | Auto-fix exhausted its attempts | Read the uploaded Gradle log artifact and fix by hand — see TROUBLESHOOTING.md |

For runtime problems (model will not start, LAN unreachable, and so on) see
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).
