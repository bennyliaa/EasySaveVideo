# Opt-in Provider callback timing (slice08)

## Scope and interpretation

This checkpoint adds bounded, in-memory technical timing at the existing Discovery coordinator's real request/terminal callback boundary. Collection is explicitly opt-in and disabled for all existing callers. It does not enable a provider, authorize a search, change retries/deadlines, grant candidate or save authority, or create UI, persistence, logs, export, or upload paths.

The metric is **Provider callback latency**: elapsed monotonic time from the coordinator handing an admitted attempt to its runtime until that attempt's accepted callback. It includes whatever network/parsing/validation work the runtime actually performs, but cannot partition these phases. A failure or timeout callback is a valid terminal observation; it is not successful result publication. Cancellation without a callback is incomplete, not a measured callback. Retry attempts are separate observations. Unstarted sources have no observation.

A timeout callback can precede physical worker/resource release. The existing IsolatedProviderRuntime deliberately retains occupied slots for uncooperative workers; timing does not relax that protection. Coordinator callbacks and page materialization do not establish the Compose first-visible frame, playback readiness, or user-perceived first-screen time.

## Identity, privacy and lifecycle

Attempt observations are bound to the actual operation object and current coordinator session. Source references are represented only by bounded per-session aliases in returned observations; raw source IDs, URLs, queries, titles, candidates, credentials and exception messages are not part of timing snapshots. There are no external event listeners. Session replacement, restore, cancellation and close retire outstanding attempt authority. Late/duplicate callbacks do not change a newer attempt or session.

Elapsed time uses an injected monotonic source independently of the existing epoch-based budget clock. Invalid elapsed calculations must remain visibly unavailable, not clamped into invented fast results. Capacity limits must be explicit and overflow/truncation distinguishable from a complete sample. No unbounded request history is retained.

## Verification boundary

Deterministic JVM fixtures exercise the actual coordinator wiring, identity fences, retry and cancellation paths. Their controlled clock values demonstrate accounting correctness only. They are not measurements of network performance, real public provider breadth, first-screen latency, energy use or device UI responsiveness.

Original requirement references: PR-04 and PR-22 in `docs/requirement and design/01_Product_Requirements.md`; TR §3 deadline/isolation controls and §13 fixed-device performance verification in `04_Technical_Requirements.md`. `PUBLIC_SEARXNG_HTML_20261017.md` still governs unverified public-search breadth and real timing work.

## Local API and units

An explicit test harness may construct `ProviderCallbackLatencyRecorder(enabled = true, clock = ..., capacity = ...)` and pass it as the coordinator's `providerCallbackLatency` argument. Existing constructors and trailing retry lambdas remain supported. The recorder is independently disabled by default even when supplied. This checkpoint does not enable collection from any production UI.

Call `snapshot()` for a detached bounded view. Durations use nanoseconds (`elapsedNanos`), not epoch timestamps. Negative raw monotonic ticks are valid; negative elapsed differences, subtraction overflow and clock read failure remain unavailable with an explicit timing status. The clock is trusted application infrastructure and must be prompt, nonblocking and free of I/O. Measurement is captured at callback entry before acquiring the coordinator lock, so coordinator-lock waiting is excluded. Retired callbacks behind an immediate lifecycle/power/source fence do not read the diagnostic clock.
