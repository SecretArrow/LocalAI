#!/usr/bin/env python3
"""
LocalAI — CI auto-fixer.

Parses CI failure diagnostics, applies deterministic fixes for known failure
classes, optionally applies LLM-assisted fixes (OpenAI-compatible API when a
key secret is configured), and commits the result so the workflow can push it
back to main. CI then re-runs automatically.

Loop safety: counts consecutive `fix(auto):` commits after the last human
commit and gives up after MAX_ATTEMPTS.

Exit codes: 0 = done (fix committed or nothing to do), 1 = fatal error.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

MAX_ATTEMPTS = 5
AUTO_PREFIX = "fix(auto):"
MAX_LLM_FILES = 8
MAX_LLM_FILE_BYTES = 60_000

KNOWN_IMPORTS = {
    "viewModelFactory": "androidx.lifecycle.viewmodel.viewModelFactory",
    "initializer": "androidx.lifecycle.viewmodel.initializer",
    "collectAsStateWithLifecycle": "androidx.lifecycle.compose.collectAsStateWithLifecycle",
    "collectAsState": "androidx.compose.runtime.collectAsState",
    "getValue": "androidx.compose.runtime.getValue",
    "setValue": "androidx.compose.runtime.setValue",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "rememberCoroutineScope": "androidx.compose.runtime.rememberCoroutineScope",
    "remember": "androidx.compose.runtime.remember",
    "rememberSaveable": "androidx.compose.runtime.saveable.rememberSaveable",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "Autofill": "androidx.compose.ui.autofill.Autofill",
    "CpuBackend": "com.localai.runtime.core.runtime.cpu.CpuBackend",
    "StubBackend": "com.localai.runtime.core.runtime.StubBackend",
    "BackendRegistry": "com.localai.runtime.core.runtime.BackendRegistry",
    "DeviceProbe": "com.localai.runtime.core.runtime.DeviceProbe",
    "InferenceBackend": "com.localai.runtime.core.runtime.InferenceBackend",
    "LoadedModel": "com.localai.runtime.core.runtime.LoadedModel",
    "LlamaBridge": "com.localai.runtime.core.runtime.cpu.LlamaBridge",
    "AppContainer": "com.localai.runtime.runtime.AppContainer",
    "RuntimeService": "com.localai.runtime.service.RuntimeService",
    "Formats": "com.localai.runtime.core.util.Formats",
    "Hashing": "com.localai.runtime.core.util.Hashing",
    "TokenGenerator": "com.localai.runtime.core.security.TokenGenerator",
    "SecretStore": "com.localai.runtime.core.security.SecretStore",
    "KeystoreCipher": "com.localai.runtime.core.security.KeystoreCipher",
    "QrCodes": "com.localai.runtime.qr.QrCodes",
    "LanAdvertiser": "com.localai.runtime.mdns.LanAdvertiser",
    "NetworkMonitor": "com.localai.runtime.core.net.NetworkMonitor",
    "LanAddresses": "com.localai.runtime.core.net.LanAddresses",
    "SystemMonitor": "com.localai.runtime.core.stats.SystemMonitor",
    "AppLogger": "com.localai.runtime.core.log.AppLogger",
    "ModelRepository": "com.localai.runtime.core.repo.ModelRepository",
    "CatalogRepository": "com.localai.runtime.core.repo.CatalogRepository",
    "SettingsRepository": "com.localai.runtime.core.settings.SettingsRepository",
    "LocalAiDatabase": "com.localai.runtime.core.db.LocalAiDatabase",
    "ModelInfo": "com.localai.runtime.core.model.ModelInfo",
    "ModelState": "com.localai.runtime.core.model.ModelState",
    "ModelFormat": "com.localai.runtime.core.model.ModelFormat",
    "BackendType": "com.localai.runtime.core.model.BackendType",
    "Availability": "com.localai.runtime.core.model.Availability",
    "BackendInfo": "com.localai.runtime.core.model.BackendInfo",
    "DeviceCapabilities": "com.localai.runtime.core.model.DeviceCapabilities",
    "RuntimeConfig": "com.localai.runtime.core.model.RuntimeConfig",
    "AppSettings": "com.localai.runtime.core.model.AppSettings",
    "CatalogEntry": "com.localai.runtime.core.model.CatalogEntry",
    "CatalogDiff": "com.localai.runtime.core.model.CatalogDiff",
    "DownloadState": "com.localai.runtime.core.model.DownloadState",
    "DownloadProgress": "com.localai.runtime.core.model.DownloadProgress",
    "SystemSnapshot": "com.localai.runtime.core.model.SystemSnapshot",
    "PairedDevice": "com.localai.runtime.core.model.PairedDevice",
    "StorageUsage": "com.localai.runtime.core.model.StorageUsage",
    "ApiLogRecord": "com.localai.runtime.core.model.ApiLogRecord",
    "LogLine": "com.localai.runtime.core.model.LogLine",
    "LocalAiException": "com.localai.runtime.core.model.LocalAiException",
    "ChatMessage": "com.localai.runtime.server.api.ChatMessage",
    "CompletionParams": "com.localai.runtime.server.api.CompletionParams",
    "InferenceEngine": "com.localai.runtime.server.api.InferenceEngine",
    "EngineException": "com.localai.runtime.server.api.EngineException",
    "ServerModel": "com.localai.runtime.server.api.ServerModel",
    "ApiServer": "com.localai.runtime.server.ApiServer",
    "DownloadManager": "com.localai.runtime.core.download.DownloadManager",
}


def sh(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=True, check=check)


def log(msg: str) -> None:
    print(f"[autofix] {msg}", flush=True)


# ---------------------------------------------------------------------------
# Diagnostics parsing
# ---------------------------------------------------------------------------

KOTLIN_ERR = re.compile(r"^e: file://(/[^:]+):(\d+)(?::\d+)?\s+(.+)$", re.MULTILINE)
TASK_FAIL = re.compile(r"Execution failed for task '([^']+)'")
WENT_WRONG = re.compile(r"What went wrong:\s*\n((?:.*\n){1,8})", re.MULTILINE)

# R8 release builds: "Missing class java.lang.management.ManagementFactory"
R8_MISSING_CLASS = re.compile(r"Missing class ([\w.$]+)")

# detekt findings, two shapes:
#   txt report:    "EmptyCatchBlock - [broken] at /abs/F.kt:6:58 - Signature=..."
#                 "LongMethod - 283/120 - [openAiRoutes] at /abs/F.kt:144:26 - ..."
#   console line:  "/abs/F.kt:6:58: Empty catch block detected. ... [EmptyCatchBlock]"
DETEKT_TXT = re.compile(
    r"^([A-Za-z]\w*) - (?:\d+/\d+ - )?\[[^\]]*\] at (/[^:]+\.kt):(\d+):(\d+) - Signature=",
    re.MULTILINE,
)
DETEKT_CONSOLE = re.compile(r"^(/[^:\n]+\.kt):(\d+):(\d+): (.+) \[([A-Za-z]\w*)\]$", re.MULTILINE)


class Diag:
    def __init__(self, text: str) -> None:
        self.text = text
        self.kotlin_errors = [
            (m.group(1), int(m.group(2)), m.group(3).strip())
            for m in KOTLIN_ERR.finditer(text)
        ]
        self.failed_tasks = sorted(set(TASK_FAIL.findall(text)))
        self.went_wrong = [m.group(1).strip() for m in WENT_WRONG.finditer(text)]
        self.missing_classes = sorted(set(R8_MISSING_CLASS.findall(text)))
        detekt: dict[tuple[str, int, str], str] = {}
        for rule, path, line, _col in DETEKT_TXT.findall(text):
            detekt.setdefault((path, int(line), rule), f"detekt {rule}")
        for path, line, _col, msg, rule in DETEKT_CONSOLE.findall(text):
            detekt.setdefault((path, int(line), rule), f"detekt {rule}: {msg}")
        self.detekt_violations = sorted(detekt.items())

    @property
    def has_compile_errors(self) -> bool:
        return bool(self.kotlin_errors)

    @property
    def has_oom(self) -> bool:
        return "OutOfMemoryError" in self.text or "GC overhead limit exceeded" in self.text

    @property
    def has_ndk_issue(self) -> bool:
        return bool(
            re.search(r"(No version of NDK|NDK not configured|requires NDK|Compatible side by side NDK)", self.text)
        )

    @property
    def has_native_fetch_issue(self) -> bool:
        return bool(
            re.search(
                r"(Could NOT resolve|could not read Username|Failed to download|"
                r"error: download failed|Connection timed out|Could not resolve host)",
                self.text,
            )
            and "llama" in self.text.lower()
        )

    @property
    def has_lock_issue(self) -> bool:
        return "Timeout waiting to lock" in self.text

    @property
    def has_test_failures(self) -> bool:
        return bool(re.search(r"(\d+ tests? completed, \d+ failed|> Task .*?test.*?FAILED)", self.text))


def load_diags(logs_dir: Path) -> Diag:
    chunks = []
    for p in sorted(logs_dir.rglob("*")):
        if p.is_file() and p.stat().st_size < 20_000_000:
            try:
                chunks.append(f"===== {p.name} =====\n" + p.read_text(errors="replace"))
            except OSError:
                pass
    return Diag("\n".join(chunks))


# ---------------------------------------------------------------------------
# Attempt counting (loop safety)
# ---------------------------------------------------------------------------

def current_attempt() -> int:
    subjects = sh("git", "log", "--format=%s", check=False).stdout.splitlines()
    n = 0
    for s in subjects:
        if s.startswith(AUTO_PREFIX):
            n += 1
        else:
            break
    return n + 1


def failed_commit_is_ancestor() -> bool:
    sha = os.environ.get("FAILED_SHA", "").strip()
    if not sha:
        return True
    res = sh("git", "merge-base", "--is-ancestor", sha, "HEAD", check=False)
    return res.returncode == 0


# ---------------------------------------------------------------------------
# Deterministic fixes
# ---------------------------------------------------------------------------

def fix_gradle_heap() -> str | None:
    props = Path("gradle.properties")
    if not props.exists():
        return None
    text = props.read_text()
    if "6g" in text:
        return None
    new = re.sub(r"-Xmx\d+g", "-Xmx6g", text)
    if new != text:
        props.write_text(new)
        return "raised Gradle daemon heap to -Xmx6g"
    return None


def fix_ndk_version() -> str | None:
    ndk_home = os.environ.get("ANDROID_HOME", "/usr/local/lib/android/sdk")
    installed = sorted(
        (p.name for p in Path(ndk_home, "ndk").glob("*") if p.is_dir()),
        reverse=True,
    )
    build_file = Path("core/build.gradle.kts")
    if not build_file.exists():
        return None
    text = build_file.read_text()
    if installed:
        new_version = installed[0]
        new = re.sub(r'ndkVersion = "[^"]+"', f'ndkVersion = "{new_version}"', text)
        if new != text:
            build_file.write_text(new)
            return f"pinned ndkVersion to installed {new_version}"
        return None
    # No NDK available at all: drop the pin so AGP picks/downloads its default.
    new = re.sub(r'\s*ndkVersion = "[^"]+"', "", text)
    if new != text:
        build_file.write_text(new)
        return "removed explicit ndkVersion (let AGP resolve the default NDK)"
    return None


def fix_unresolved_imports(diag: Diag) -> list[str]:
    notes: list[str] = []
    by_file: dict[Path, set[str]] = {}
    for path, _, msg in diag.kotlin_errors:
        m = re.match(r"Unresolved reference: (\w+)$", msg)
        if not m:
            continue
        symbol = m.group(1)
        imp = KNOWN_IMPORTS.get(symbol)
        if imp is None:
            continue
        p = Path(path)
        # CI checkouts live under /home/runner/work/... — map repo-relative.
        if not p.exists():
            p = Path.cwd() / path.lstrip("/")
        if not p.exists():
            # Try to find by suffix (module path may differ).
            rel = path.split("/LocalAI/")[-1] if "/LocalAI/" in path else path.lstrip("/")
            p = Path.cwd() / rel
        if not p.exists():
            continue
        by_file.setdefault(p, set()).add(imp)

    for p, imports in by_file.items():
        try:
            text = p.read_text()
        except OSError:
            continue
        missing = [i for i in imports if f"import {i}" not in text]
        if not missing:
            continue
        lines = text.split("\n")
        last_import = max((i for i, l in enumerate(lines) if l.startswith("import ")), default=None)
        if last_import is None:
            # Insert after package declaration.
            pkg = next((i for i, l in enumerate(lines) if l.startswith("package ")), None)
            insert_at = (pkg + 2) if pkg is not None else 0
            new_lines = lines[:insert_at] + [f"import {i}" for i in sorted(missing)] + [""] + lines[insert_at:]
        else:
            new_lines = lines[: last_import + 1] + [f"import {i}" for i in sorted(missing)] + lines[last_import + 1 :]
        p.write_text("\n".join(new_lines))
        notes.append(f"added missing import(s) to {p}: {', '.join(sorted(missing))}")
    return notes


def fix_missing_r8_classes(diag: Diag) -> list[str]:
    """Silence R8 'Missing class' errors from JVM-only libraries.

    Release builds fail when R8 encounters classes referenced by dependencies
    (Ktor, Coroutines debug hooks, ...) that do not exist on Android. The
    established fix — already proven on this repo for JMX classes — is to add
    -dontwarn lines to the app's keep-rules file.
    """
    rules = Path("app/proguard-rules.pro")
    if not rules.exists() or not diag.missing_classes:
        return []
    text = rules.read_text()
    add = [c for c in diag.missing_classes if f"-dontwarn {c}" not in text]
    if not add:
        return []
    block = (
        "\n# auto-fix: R8 missing classes (referenced by JVM-only libraries)\n"
        + "\n".join(f"-dontwarn {c}" for c in add)
        + "\n"
    )
    rules.write_text(text.rstrip("\n") + "\n" + block)
    shown = ", ".join(add[:5]) + (" ..." if len(add) > 5 else "")
    return [f"added -dontwarn for {len(add)} R8 missing class(es): {shown}"]


def rerun_failed_workflow() -> bool:
    """Re-run the failed CI job (used for transient network/lock failures)."""
    run_id = os.environ.get("FAILED_RUN_ID", "").strip()
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not (run_id and repo and token):
        return False
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/rerun",
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status in (200, 201)
    except Exception as exc:  # noqa: BLE001
        log(f"rerun request failed: {exc}")
        return False


# ---------------------------------------------------------------------------
# LLM-assisted fixes (optional)
# ---------------------------------------------------------------------------

def llm_config() -> tuple[str, str, str] | None:
    model = os.environ.get("LLM_MODEL", "").strip()
    if os.environ.get("ZAI_API_KEY", "").strip():
        return (
            "https://api.z.ai/api/paas/v4/chat/completions",
            os.environ["ZAI_API_KEY"].strip(),
            model or "glm-4.6",
        )
    if os.environ.get("OPENAI_API_KEY", "").strip():
        return (
            "https://api.openai.com/v1/chat/completions",
            os.environ["OPENAI_API_KEY"].strip(),
            model or "gpt-4o",
        )
    if os.environ.get("OPENROUTER_API_KEY", "").strip():
        return (
            "https://openrouter.ai/api/v1/chat/completions",
            os.environ["OPENROUTER_API_KEY"].strip(),
            model or "anthropic/claude-sonnet-4",
        )
    return None


def llm_chat(url: str, key: str, model: str, system: str, user: str) -> str | None:
    payload = json.dumps(
        {
            "model": model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
    ).encode()
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode())
            return data["choices"][0]["message"]["content"]
    except Exception as exc:  # noqa: BLE001
        log(f"LLM call failed: {exc}")
        return None


def repo_relative(path: str) -> Path | None:
    p = Path(path)
    if p.exists():
        return p
    rel = path.split("/LocalAI/")[-1] if "/LocalAI/" in path else path.lstrip("/")
    p = Path.cwd() / rel
    return p if p.exists() else None


def llm_fixes(diag: Diag) -> list[str]:
    cfg = llm_config()
    if cfg is None:
        log("no LLM key configured — deterministic rules only")
        return []
    url, key, model = cfg
    notes: list[str] = []

    # Group errors per file.
    per_file: dict[str, list[str]] = {}
    for path, line, msg in diag.kotlin_errors:
        per_file.setdefault(path, []).append(f"line {line}: {msg}")
    # detekt violations join the same per-file LLM repair path.
    for (path, line, rule), desc in diag.detekt_violations:
        per_file.setdefault(path, []).append(f"line {line}: {desc}")

    for i, (path, errs) in enumerate(per_file.items()):
        if i >= MAX_LLM_FILES:
            notes.append(f"LLM fixes capped at {MAX_LLM_FILES} files — remaining need manual attention")
            break
        p = repo_relative(path)
        if p is None or p.stat().st_size > MAX_LLM_FILE_BYTES:
            continue
        source = p.read_text(errors="replace")
        system = (
            "You are a build-fixing agent for a Kotlin/Android project. "
            "You receive compilation errors and/or static-analysis violations "
            "for ONE file plus its full source. "
            "Reply with a SINGLE unified diff (git format) that fixes the errors. "
            "Output ONLY the diff, no prose, no markdown fences. "
            "Keep changes minimal and correct. For detekt style rules prefer the "
            "smallest conforming change over restructuring."
        )
        user = (
            f"File: {p}\n\nCompilation errors:\n" + "\n".join(errs[:30]) + "\n\n"
            f"Current source:\n```kotlin\n{source}\n```\n"
            "Produce the unified diff now."
        )
        log(f"asking {model} to fix {p} ({len(errs)} errors)")
        diff = llm_chat(url, key, model, system, user)
        if not diff:
            continue
        diff = diff.strip()
        if diff.startswith("```"):
            diff = re.sub(r"^```[a-z]*\n|\n```$", "", diff)
        if not diff.startswith("---"):
            continue
        proc = subprocess.run(
            ["git", "apply", "--recount", "--whitespace=nowarn", "-"],
            input=diff,
            capture_output=True,
            text=True,
        )
        if proc.returncode == 0:
            notes.append(f"LLM patch applied to {p}")
        else:
            log(f"LLM patch for {p} did not apply: {proc.stderr.strip()[:200]}")
    return notes


def llm_generic_fix(diag: Diag) -> list[str]:
    """LLM fallback for non-compile failures (R8, lintVital, manifest...).

    Used when the failure has a Gradle "What went wrong" section but no
    Kotlin compile errors — typical for release builds. The model receives
    the failure text plus the repository file list and returns one diff.
    """
    cfg = llm_config()
    if cfg is None:
        return []
    url, key, model = cfg
    failure = "\n".join(diag.went_wrong[:6]) or "(no detail)"
    tasks = ", ".join(diag.failed_tasks) or "(unknown)"
    listing = sh("git", "ls-files", check=False).stdout.strip()
    system = (
        "You are a build-fixing agent for a Kotlin/Android project. "
        "You receive a Gradle build failure summary and the list of repository "
        "files. Diagnose the root cause, pick the minimal set of files to "
        "change, and reply with a SINGLE unified diff (git format). "
        "Output ONLY the diff, no prose, no markdown fences. "
        "If the failure is caused by missing secrets or environment inputs, "
        "reply with exactly NOFIX."
    )
    user = (
        f"Failed tasks: {tasks}\n\nFailure detail:\n{failure}\n\n"
        f"Repository files:\n{listing}\n\nProduce the unified diff now."
    )
    log(f"asking {model} for a generic fix (tasks={tasks})")
    diff = llm_chat(url, key, model, system, user)
    if not diff:
        return []
    diff = diff.strip()
    if diff.startswith("```"):
        diff = re.sub(r"^```[a-z]*\n|\n```$", "", diff)
    if not diff.startswith("---") or diff == "NOFIX":
        return []
    proc = subprocess.run(
        ["git", "apply", "--recount", "--whitespace=nowarn", "-"],
        input=diff,
        capture_output=True,
        text=True,
    )
    if proc.returncode == 0:
        return ["LLM generic patch applied"]
    log(f"generic LLM patch did not apply: {proc.stderr.strip()[:200]}")
    return []


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    logs_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "ci-logs")
    if not logs_dir.exists():
        log(f"no logs directory at {logs_dir} — nothing to do")
        return 0

    attempt = current_attempt()
    log(f"auto-fix attempt #{attempt} (max {MAX_ATTEMPTS})")
    if attempt > MAX_ATTEMPTS:
        log("attempt cap reached — giving up. A human should look at the CI logs.")
        return 0

    if not failed_commit_is_ancestor():
        log("the failed commit is no longer on main — skipping stale fix")
        return 0

    diag = load_diags(logs_dir)
    log(
        "diagnostics: "
        f"{len(diag.kotlin_errors)} kotlin errors, "
        f"failed tasks={diag.failed_tasks}, "
        f"oom={diag.has_oom}, ndk={diag.has_ndk_issue}, "
        f"native_fetch={diag.has_native_fetch_issue}, "
        f"tests={diag.has_test_failures}, "
        f"r8_missing={len(diag.missing_classes)}, "
        f"detekt={len(diag.detekt_violations)}"
    )

    if not any(
        [
            diag.kotlin_errors,
            diag.has_oom,
            diag.has_ndk_issue,
            diag.has_native_fetch_issue,
            diag.has_test_failures,
            diag.went_wrong,
            diag.missing_classes,
            diag.detekt_violations,
        ]
    ):
        log("no recognised failure signatures — nothing to fix")
        return 0

    applied: list[str] = []

    if diag.has_oom:
        note = fix_gradle_heap()
        if note:
            applied.append(note)

    if diag.has_ndk_issue:
        note = fix_ndk_version()
        if note:
            applied.append(note)

    if diag.kotlin_errors:
        applied.extend(fix_unresolved_imports(diag))

    if diag.missing_classes:
        applied.extend(fix_missing_r8_classes(diag))

    applied.extend(llm_fixes(diag))

    if not applied and (diag.went_wrong or diag.missing_classes):
        applied.extend(llm_generic_fix(diag))

    if diag.has_native_fetch_issue or diag.has_lock_issue:
        if rerun_failed_workflow():
            log("transient failure detected — requested CI re-run instead of a commit")
            return 0

    if diag.has_test_failures and not applied:
        log("test failures need review — deterministic rules produced no change")

    if not applied:
        log("no fix could be produced automatically")
        return 0

    sh("git", "config", "user.name", "github-actions[bot]")
    sh("git", "config", "user.email", "github-actions[bot]@users.noreply.github.com")
    sh("git", "add", "-A")
    staged = sh("git", "diff", "--cached", "--quiet", check=False)
    if staged.returncode == 0:
        log("no staged changes after fixes")
        return 0

    summary = "; ".join(applied)[:150]
    message = f"{AUTO_PREFIX} attempt {attempt} — {summary}"
    sh("git", "commit", "-m", message)

    commit_sha = sh("git", "rev-parse", "HEAD").stdout.strip()
    log(f"committed: {message}")
    log(f"sha: {commit_sha}")
    for note in applied:
        log(f"  - {note}")

    # Export for the workflow's push step.
    gh_env = os.environ.get("GITHUB_ENV")
    if gh_env:
        with open(gh_env, "a") as fh:
            fh.write("AUTOFIX_COMMITTED=1\n")
            fh.write(f"AUTOFIX_COMMIT_SHA={commit_sha}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
