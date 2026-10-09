# Windows restore and A/B integration checkpoint

## Verified recovery

Workspace: `D:\CodexProjects\EasySaveVideo\esv-source-12f97d43-20261009`.
Snapshot label: `12f97d43c11fa19ea7dd08ccc0805a3030c71346`.
Both fragments matched the supplied sizes and SHA-256 values. Combined archive:
26522932 bytes, SHA-256 `163f77e4c435b90b8a6fdc1dd8217b45b8a03e9e8c4c8224ae2272c1793b3481`.
Archive validation rejected links, special files, unsafe paths, duplicate/case-colliding paths before extraction.
Actual counts matched: 7397 entries, 6498 regular files, 151 executable modes.
No existing project or uncommitted file was overwritten. New local snapshot import commit
`4036e12d614436f907814f8702eee95773eac011` is **not recovered upstream Git history**.
The original verified archive remains available separately.
Original executable modes are also recorded in the new Git baseline; Windows import initially
lost these Unix mode bits, so their restoration is metadata preservation, not a functional edit.

The independent prototype baseline `d48b59b8245f412fa7e9dc22ece2235f6bcb2e02`
was checked clean. Its generic registry/shared-ledger/consent/fence/draining principles
were adapted into Discovery's existing synchronous operation/cancellation model;
the separate standalone suspend prototype was not mistaken for application integration.
Its 100 labelled fixtures and 80 mutation variants remain unchanged and independently versioned.

## Code integrated and scope

Discovery now defines `VerificationEngine`, extensible string identities, capabilities,
request/result identity, conservative evidence/verdict, shared remaining request/body budget,
monotonic deadline, cancellation, network constraints and scheduling policy. No A/B engine enum.
The scheduler keeps one active call, rejects identity/evidence corruption, stops on denial/429,
and prohibits fallback or new work until the prior engine's physical drain is proven complete.
No engine exception or unknown byte consumption can reset the budget and start another engine.
The byte ledger is a response-body budget, **not measured total network traffic**.

A's existing client is adapted and the application composition root uses the scheduler.
Shared request ID reaches the strict wire protocol; generation/source/navigation fences remain.
The existing transport exposes its physical admission state, rather than treating a returned
future as proof that cleanup finished. Default session-only consent and startup independence remain.
Total production validation ceiling remains 3000 ms with the existing 1000 ms operation reserve;
A's initial conservative attempt cap is 1500 ms, not a measured performance result.

B has a real Jsoup 1.21.2 static parsing component in the JVM-only `verification-local` module.
It requires finite strict UTF-8, one main/one video, an explicit primary marker and one usable
source; missing/hidden/ambiguous/article/JSON-LD-only/JS-only evidence stays unknown.
Source candidates do not prove playback, downloadability or rights. Matching is compiled,
bounded structural checking; no downloaded rules, dynamic interpreter or persisted matching memory.
This is **not yet the complete B engine**: OkHttp transport, WebView execution/lifecycle,
actual engine registration and B-with-A-disabled application behavior remain unfinished.
The module is not an Android app dependency; existing app admission remains 74 artifacts.

## Windows platform result and remaining A barrier

Original Linux execution is unsuitable on Windows: worker imports `resource` and uses `prctl`;
parent relies on `/proc`, pidfd, Linux stopped-state/subreaper proof and POSIX nonblocking pipes/select.
These files and Linux guarantees were not weakened or replaced.

New Windows Job Object primitive creates a worker suspended, assigns before resume, prohibits
breakaway by policy flags, sets kill-on-close, 24 active processes, per-process CPU and aggregate
job committed-memory limits. A real owned Python root+grandchild probe terminated both and
observed zero active job processes. Handles authorize termination, not a PID search/kill.
No other process, emulator, private media or browser was inspected or operated.

This proves only the narrow process primitive. It does **not** prove Chromium nested-job behavior,
sandbox/TLS/permission routing, hard-limit stress, IPC stall cancellation, poison/reuse handling,
clean environment, exact official Windows wheels/browser provenance or a Windows Scrapling run.
Windows bounded IPC and worker orchestration must be implemented and tested before runtime admission.
Source-only staging with SHA-256 receipt is prepared under `Scrapling_Service/source-12f97d43-20261009`;
it is not an installed service and contains no venv/browser/runtime cache.
No Scrapling service, venv or Chromium was installed or started. User's service directory remains
`D:\CodexProjects\EasySaveVideo\Scrapling_Service`; prepared source staging is separate from runtime.
No WSL/system component/security setting was enabled.

The current Android A transport accepts only public HTTPS/443 endpoints and rejects loopback/private
targets; a loopback-only Windows service cannot be connected by silently substituting cleartext or
`10.0.2.2`. A debug IPC or approved transport design must be reported before adding a new security
configuration. No credentials or TLS keys were created. The Windows A capacity benchmark can first
run locally without changing Android network policy; that does not validate Android A connectivity.

## Dependency gates

Jsoup 1.21.2 exact JAR/POM hashes matched official Maven Central SHA-256; MIT text preserved.
Only this internal parser module is admitted; exact metadata and its dependency lock were added.
OkHttp 4.12.0/Okio JVM 3.6.0 remain candidates. Their Apache-2.0 notices alone are insufficient:
OkHttp's embedded Public Suffix List has MPL-2.0 obligations. Full license/NOTICE and official source
JAR are locally retained. That source JAR contains the compiled list, so exact preferred-source
provenance and future Android notice/source availability still need completion before app admission.
JARs in the candidate directory are ignored by Git. No evaluation binary was copied from cloud.
The project license was not invented or changed, and closed-source requirements remain intact.

## Evidence categories

`artifacts/local-windows-20261009` records scoped JVM, protocol/default-HTTP and real Job Object
probe evidence. The summary JSON supplies actual final counts; build failures during missing cache/
SDK/checksum preparation are retained separately. Android Kotlin compile is compile-only, not APK,
device lifecycle, performance or public-source acceptance. Core scheduling tests use fake engines;
parser tests use actual Jsoup; service protocol tests do not import/execute Scrapling.
Final scoped JVM totals: 249 Discovery, 43 verification client, 8 real Jsoup component = 300;
all zero failures/errors/skips. Service protocol/default-HTTP: 17 pass. Real Windows owned-process
probe: pass. Debug and Release Kotlin compilation and original 74-artifact admission: pass. The full historical
2309 JVM + 57 real service acceptance was not reexecuted. The boundary checker requires Python
UTF-8 mode on this Windows GBK locale; its initial locale decode failure is not a code-boundary pass. The unchanged checker subsequently passed all seven checks through the retained Windows extended-path file-open wrapper; this also resolves Python long-path reads without changing OS settings.

Not run: real Scrapling/Chromium; full B network/WebView; A/B on the common 100; frozen-engine recovery
on the 80 mutations; public URL admission; emulator or physical-device tests; CPU/RAM/energy/total
network/end-to-end timing. Unmeasured phone values are N/A. Windows and Android resources must not
be ranked directly; eventual device CPU/RAM includes the WebView renderer and network totals must
include traffic missed by Network Inspector.

Android remains API31 minimum. VersionCode is still 2 because no package was assembled or installed.
Future installation requires a higher versionCode and the same signing certificate, preserving data.
Wait for the user's notification about emulator-5556; do not start, poll or replace it.
Vault and M7-D remain outside this work and were not reviewed or reimplemented.

The App composition root is now VerificationEngineRegistration: it accepts local engine registrations even when A is disabled. Discovery uses the common scheduler. Actual B remains unregistered until its transport/WebView and dependency gates are complete.
