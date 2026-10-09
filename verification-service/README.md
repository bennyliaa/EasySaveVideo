# Optional verification service: original development prototype

This directory contains original Python source and tests only. The Android client
has no Python/Scrapling/browser dependency. Read the parent repository's
`docs/verification-service/CONTRACT.md` for the exact wire contract.

## Scope and default behavior

`python -m esv_verify --port 8765` starts a stdlib-only, loopback-only development
HTTP endpoint. It has **zero admitted target sources**. Valid requests return
`UNSUPPORTED` without importing Scrapling, resolving DNS, opening a target socket,
or launching a browser. It is not a production/deployment command, and supplies
no TLS termination, authentication, public target policy or hosting configuration.
No environment variable, HTTP field or CLI option enables fixture access.

The test harness directly constructs `FixtureCapability` with exact owned
127.0.0.1 origin/path rules. A constructor-only logical alias, restricted to
`https://fixture.example.org`, supports the genuine Kotlin-client integration
checks. Fetcher still reads only the exact owned loopback mapping; neither the
alias nor any public URL is resolved or fetched. The browser sees replayed
responses at the logical page identity. This is not public-source admission.

## Real framework integration

The adapter imports the separately evaluated, unmodified **Scrapling 0.4.15**.
It uses real Fetcher requests with a bounded streaming callback, real Selector
CSS extraction, and real DynamicFetcher JavaScript execution/XHR capture. It
contains no replacement HTML parser, copied upstream implementation, fake success
response or mock runtime on the end-to-end path. Adaptive matching is not used.

L1 only accepts exactly one video in one main region, bearing the admitted fixture
rule's explicit `data-esv-primary="true"` marker and usable source evidence.
Related article videos, JSON-LD, HTTP success, missing/blank sources and ambiguous
videos do not qualify. L2 requires exactly one observed video whose configured
video ID matches the one exact page-bound captured JSON response. Observed DOM
title/duration are cross-checked; absent fields remain null. Neither path verifies
playback, downloadability, saving rights or metadata completeness. No media or
thumbnail is downloaded. These deliberately narrow fixture rules are not generic
site extraction rules.

## Boundaries

- Request 8 KiB; response 32 KiB; headers 16 KiB; one HTTP request per connection
- No cookies/auth/compression, redirects, retries, persistent state or request logs
- At most four connection handlers and two physical worker slots (constructor
  bounded to four); duplicate active identities return `BUSY`
- Child processes have hard 12-second CPU, 8 MiB output-file, 256-fd and zero-core
  limits; static-only workers additionally have a hard 512 MiB address-space limit
- Parent enforces the request's 1–10000 ms monotonic deadline and disconnect /
  shutdown cancellation, including worker-envelope transmission and a final fence
  after cleanup. The private envelope is capped at 32 KiB before spawn. Unbuffered,
  nonblocking pipes use bounded select/partial-write handling; a child that never
  reads stdin cannot strand the parent before the watchdog loop
- Browser workers use a **sampled 768 MiB aggregate RSS watchdog**, 24-process
  watchdog and 64 MiB V8 old-space limit. RSS sampling is not a kernel-enforced
  aggregate-memory guarantee and can overshoot between samples. Chromium's huge
  virtual reservations prevent applying the static RLIMIT_AS configuration.
  Production memory/cgroup admission remains unresolved; no claim of hard total
  browser-memory containment is made
- Worker root is a Linux child subreaper. Cleanup pins PID/start identities, uses
  pidfds (never killpg), repeatedly stops/discovers until all owned processes are
  observed stopped, kills descendants before root, and reaps the root. Unproven
  cleanup or cleanup exceptions poison service capacity rather than permit respawn
- Per-request clean HOME/TMPDIR/environment; no inherited proxies, credentials,
  netrc, profile, cookies, startup hooks or durable runtime state
- Chromium executable SHA-256 is checked against the prior admitted fixture
  artifact. Sandbox and TLS checks remain enabled; ordinary automation is exposed;
  prior hardened default-argument exclusions are preserved and checked at runtime
- Browser geolocation, notifications, camera and microphone permissions are denied
  and checked. Service workers, downloads, popups, media/resources and websockets
  are blocked; setup failure closes the page before Scrapling can navigate
- Browser routes never call `continue_` or browser `fetch`: bounded HTML/API bodies
  are replayed, all unapproved URLs/methods/frames and repeat requests abort. CSP
  also limits active content. Adversarial resource attempts may yield a bounded
  timeout; incomplete evidence cannot publish success

Linux `/proc`, pidfd and subreaper support are required for admitted fixture
execution. Missing optional runtime/artifact or a worker failure degrades this
path; it does not affect Browser, Save, Record or application startup.

## Reproduce inside the previously admitted environment

Do not install dependencies or accept new terms as part of these tests. Set
`ESV_SCRAPLING_EVAL` to the existing isolated evaluation directory. Its `.venv`,
immutable admission files, reviewed browser and notices must already exist.
No wheel, venv, native library, browser/Node binary or old evidence is copied here.

The build owner first compiles `:verification-client:writeFixtureClasspath`.
Set `ESV_KOTLIN_FIXTURE_CLASSPATH` to the contents of that task's
`verification-client/build/fixture-classpath.txt`, and use its JDK via `JAVA_HOME`.
From this directory:

    PYTHONDONTWRITEBYTECODE=1 "$ESV_SCRAPLING_EVAL/.venv/bin/python" -m unittest discover -s tests -v

For the acceptance run, every test must execute: a missing runtime or Kotlin
classpath produces skips and is not an accepted complete result. The parent
repository's evidence runner additionally produces a machine-readable report.
The suite covers real static/dynamic/XHR positives and negatives, genuine
Kotlin→HTTP→Python→Scrapling typed results, strict framing/Unicode/identity,
source ambiguity, dependency failure, timeout/cancellation/disconnect, process
identity reuse, poisoned cleanup, forbidden resources and default no-network.
Additional IPC regressions use real 4 KiB pipes with stalled/early-closed children,
full-pipe deadline/cancel/shutdown, retained slots until cleanup, interrupted and
short writes, broken-pipe/zero-progress handling, and pre-spawn envelope rejection.

## License / distribution status

Source development and isolated owned-fixture runtime evaluation only. Existing
component selection, license, native provenance, browser terms and redistribution
gates remain unchanged. This source does not grant a public license for the user's
project or approval to ship Scrapling/Python/Node/browser/native artifacts.
Public M0 accuracy, public SSRF/DNS and per-source policy, hosted service security,
operational sizing, authentication, deployment and binary distribution all need a
separate reviewed scope.
