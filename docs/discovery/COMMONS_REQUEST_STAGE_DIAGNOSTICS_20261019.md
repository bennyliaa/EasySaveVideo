# Commons request-stage diagnostics — slice09

This independent source snapshot begins from the frozen code11 candidate. It does not replace or modify that installed/tested APK or its receipts. The observed code11 search symptom (no candidates) and separate device DNS/TCP probe do not establish the app UID's TLS, HTTP, parser or policy outcome. This feature provides evidence for a later explicitly authorized device trial; no real network outcome is claimed by offline tests.

## Bounded scope

Only an explicit Debug evaluation action enables collection. It does not itself run a search, activate a source, bypass a filter or retry a request. Normal operation remains disabled, and Release has no controls. The user performs the existing search action after enabling diagnostics. Local preview is bounded and does not create an export, log, persistence, upload or new network endpoint.

Opaque capabilities connect the actual coordinator session and exact operation to the Commons metadata request and its original socket transport. A retry obtains a new capability rather than copying the prior attempt's authority. A replacement session, stop, lifecycle retirement or close prevents old callbacks from modifying newer observations. The process-global Commons source does not retain a UI owner or recorder.

## What observations mean

- DNS: the actual configured resolver call.
- TCP: the actual socket connect call.
- TLS: the actual SSLSocket handshake (not a separate probe).
- Request write: existing request write/flush, if separately recorded.
- HTTP exchange: existing strict response reader, including bounded body processing where the existing parser reads a body. Its 401/403/429/503 denial/slowdown paths deliberately stop after bounded headers. This is not time-to-first-byte, always-full-body, or server-only latency.
- Parse: the actual CommonsResponseParser invocation, not evidence that a result was published or playable.
- Cache hit: explicitly recorded; skipped network phases remain NOT_OBSERVED, never invented zero-millisecond successes.
- Cooldown: an observed admission gate or server slowdown response, not time spent sleeping.
- Cancellation/deadline: distinguish operation cancellation, deadline and normal resource cleanup. Metadata cleanup invokes transport cancellation even after success; that cleanup must not fabricate a cancelled search.

An unfinished phase at a terminal callback remains incomplete. OPERATION rows describe runtime callbacks/retirement; even SUCCESS is not proof that the coordinator accepted, committed, or displayed a candidate. Runtime callbacks can occur before physical worker/socket cleanup finishes, including a blocked resolver or close. Not observing a phase does not prove that the network is broken at that phase. Monotonic elapsed values are local measurements of the named boundary only. No UI-first-frame or internet-wide search effectiveness claim follows.

## Data and safety boundary

The view contains only bounded aliases/indices, closed stage/status/error enums, safe HTTP status integers and valid monotonic elapsed durations. It contains no URL, host/IP, query, title, response body, raw headers, exception class/message/stack, credentials, service contact or email. Clock anomalies and sample truncation remain explicit rather than producing plausible-looking numbers.

The existing hostname/DNS/public-address checks, peer pinning, TLS authentication, redirect refusal, HTTP parser bounds, SafeSearch refusal, resource release, Retry-After and retry budgets remain unchanged. No extra request, altered user-agent, endpoint, robots policy or alternative connection path is introduced. Production hooks do not invoke arbitrary diagnostic listeners or injected clock callbacks. The recorder uses a short bounded internal monitor and the fixed monotonic clock. Immediate lifecycle, power and source cancellation fences only set the original cancellation state and never acquire the diagnostic monitor. Traces retain only that minimal atomic cancellation state, not the operation token or its request closures. An observed cancellation/deadline also fences subsequent stage events; unfinished stages stay incomplete rather than becoming late successes.

## Subsequent real acceptance (not performed here)

On the specifically authorized device and exact new APK: enable the Debug panel, issue one existing Commons search, and inspect observed stages, terminal result and truncation flags. Preserve NOT_OBSERVED/incomplete entries. Test stop/navigation/replacement session and late responses. Record exact APK/source identity and only the safe preview; do not collect search text, contact configuration or raw traffic. Repeated attempts must still respect existing cooldown and user cancellation.
