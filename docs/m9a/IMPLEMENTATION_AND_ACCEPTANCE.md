# M9-A — Fixed Commons ordinary reliability

Baseline: `dfdddbbba2ea5fc3f810f02fa2652e55bb2ecba0` (M8-B3).
This slice closes ordinary fixed-source JVM/host reliability. It does not claim
complete M9 Android compatibility, release readiness, device energy measurements
or acceptance of any blocked dynamic-rule or Vault work.

## Corrected coordinator defects

1. Retry-attempt ownership. A delayed duplicate callback from a previous attempt
   could observe the shared ActiveRequest as in flight again after a retry began.
   It could then release the new attempt's slot, publish an old page/cursor or
   schedule another retry. Each launch now captures its operation object. Callback
   completion and returned cancellation-handle assignment require that exact
   object, along with the existing active request, epoch and gate conditions.
   Retries retain the original cancellation token, absolute deadline and bounded
   attempt/round accounting. No new timer, worker, source or dependency is added.
2. Final commit deadline. Runtime I/O completion cancels its own deadline timer,
   but the subsequent durable transaction can take time. The pre-transaction clock
   check alone allowed storage publication at or after the operation deadline.
   The existing beforeCommit guard now also checks the original operation deadline.
   Results, Seen/alias facts and cursor all roll back together; no late success or
   automatic post-deadline retry is published.

The before-fix logs retain two failing callback regressions and one failing commit
boundary regression. Final acceptance must use the subsequent full current-source
aggregate, not those diagnostics. No production Commons transport/cache/parser,
Android UI, save, recording, permissions, SDK or SkinKit code was changed.

## Ordinary fault matrix

New JVM coverage comprises 11 coordinator cases, 15 Commons transport/cache cases,
7 socket-adapter recovery cases and 6 Browser/Save/codec isolation cases. Details
are in TRANSPORT_MATRIX.md and ISOLATION_PERSISTENCE_MATRIX.md.

The matrix uses bounded ordinary fixtures only: unavailable DNS/connect, slow
headers/body, partial I/O and framing, malformed JSON, normal declared response
limit rejection without an oversized payload, zero-progress input, cache expiry
and no-store behavior, original deadline, cancellation, explicit bounded recovery,
stale callbacks, incompatible/expired/exhausted cursor, failed persistence, one
failed source and zero sources. Existing backoff, Retry-After, runtime physical
admission and cancellation cleanup contracts remain authoritative. A logically
completed timeout does not free a physical slot while a worker/cleanup still owns
it; Java cannot safely force-stop an uncooperative provider worker.

Browser/Save tests execute actual platform-neutral controllers, trackers and codecs
with test transport/media ports. Host SQLite tests use source-extracted DDL and
explicitly modelled existing mutation helpers across four fresh processes. Neither
is represented as Android WebView, media download/decode, SQLiteOpenHelper,
process-kill, native process recovery, power-loss or actual saved MP4 acceptance.

## Preserved boundaries

- B1 history revision, alias grouping, independent monotonic facts and atomic
  capacity rollback remain unchanged and are rerun on current source.
- B2 native SAVED/PLAYED remain UNKNOWN without trustworthy video identity. Save
  and Record real owner priority remains immediate; unrelated owners retain only
  their previously documented sampling.
- B3 release power policy remains NOT_CONNECTED. Synthetic scenarios are debug
  only; no OS observation, polling, power calibration or energy measurement.
- Fixed Commons schema1 only, zero-provider startup/browser fallback, API31
  phone/tablet target, exact74 admitted artifacts and SkinKit contract unchanged.
- No blocked M7-D candidate/runtime/review/probe or substitute, Vault, new provider/
  library, production roots/endpoints/services/accounts/payment, APK/AAB/device
  installation, or SkinKit repository/SDK work.

## Evidence status

Compilable WIP is recorded in WIP_CHECKPOINT.md. Independent frozen review, full
aggregate and dual cold-restored verification are reported below only after they
execute. Final archive/ref/byte/mode verification is sealed in the external restore
receipt; it is not inferred from a successful local build.

## Final main-worktree results — 2026-10-07 UTC

- Strict offline/dependency verification and Kotlin nonincremental full rerun:
  **87/87 Gradle tasks executed, PASS**; both app Kotlin compilation and lint variants.
- **1,798 JVM tests**, zero failures/errors/skips. Discovery **205**, Commons **238**,
  app **859**; **39** new ordinary tests relative to B3. Other module totals unchanged.
- Each lint variant remains **0 errors, 14 warnings, 6 hints**. Existing SDK,
  metrics and deprecation notices remain; this is not a warning-free build.
- Exactly **74** admitted runtime artifacts per variant, **1,048** protected inputs
  byte-identical to B3, fixed Commons main JAR **127** classes. No artifact/source
  admission broadened. Historical source gates and their evidence remain unchanged.
- Nine current source/admission gates; all six inherited SQLite suites; DOM **11**;
  HLS EOF **81**, mux conservation **15**, local-reader and editor-release hosts pass.
- Provider host **342** (the inherited 327 plus 15 new cache/failure cases), M8-A
  Discovery **12**, B1/B2 origin/history **34**, media **47**, B3 power **24 per
  debug/release variant**, and filters **29** pass on current source.
- New frozen independent selection **49/49**, including **10** independently written
  coordinator regressions; separate Browser/Save host **6/6**; SQLite close/reopen
  **four fresh processes**. Host selections overlap JVM suites and are not added to
  the 1,798 JVM total. Standalone exact8 toolchain hashes match before/after review.
- All **520** frozen source/resource/script inputs remain unchanged. Manifest SHA:
  `fbb8b718ec059cba45274dd58767546d8528cbd559966b568cfd9d5b1d37222a`.
- Final commit/tag, archive hashes, exact tracked bytes/modes/refs and two complete
  cold-restored aggregates are recorded only in the external restore receipt after
  execution. The same full aggregate is required on both Git and source restores.

No physical Android compatibility, device process-death/power-loss, production
purchase, OS signal or energy-measurement claim is made. NEXT_INDEPENDENT_SCOPE.md
aligns remaining M9 acceptance and M10 decisions with the original milestone plan;
no repetitive new ordinary host-test phase is proposed.
