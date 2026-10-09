# M8-A local ordering, progress and bounded Discovery correctness

This independent development slice follows M7-C. It does not complete M8, authorize
installation or certify a live service. `SCOPE_AND_SEQUENCE.md` lists open work.

## Local ordering and persistence

`SearchProfile.sortOrder` supports source arrival order, newest publication first,
shortest duration and longest duration. These are local ordering choices. They do
not change the normalized query key, profile ID, provider cursor or source query.
No invented relevance score is assigned to provider responses. `PREFER_NEW` first
places unshown results ahead of shown ones; within each history bucket metadata
sorting puts missing values last. Arrival order and identity provide stable ties.
The other three history policies keep their existing meaning and per-session
deduplication remains active even under SHOW_ALL.

Already materialized pages do not reshuffle when results arrive. An explicit view
change preserves the entire current page as the new anchor and applies the new
order only to later pages. Old revision callbacks cannot mark the new view. The
revision transaction returns a confirmed outcome; the Android controller displays
success and updates its local choice only after success. Failed writes preserve
committed data and surface unavailable storage rather than a false success notice.
History/progress reset notices follow the same rule.

Checkpoint format 3 stores the sort enum. Strict readers accept genuine frozen v1
and v2 inputs as source order. Unknown sort values and corrupt/trailing bytes fail
closed. The v2 fixture was produced by the unchanged old writer, including a real
synthetic rights envelope; its provenance and generator are preserved. The SQLite
schema and separate history/progress tables do not change. Older v1/v2-only binaries
cannot read new v3 checkpoints; they must preserve them as unsupported, not reset them.

## Provider progress and low-waste behavior

Status snapshots expose provider ID, technical state, committed last-success time,
continuation-presence boolean, retry-not-before time and failure metadata. They
never expose the raw cursor/query and never invent a page count or percentage.
States distinguish active request, pending retry, live cooldown, filter/configuration
refusal, committed continuation, exhaustion, expired/incompatible cursor, suspension
and unavailable storage. Functional snapshots have redacted string representations.

Inventory and progress are cached together after an atomic read. Repeated UI status
reads perform no writes, cursor resets or new requests; changing time can update
cooldown presentation without starting a new round. Source compatibility is reset
only when demand is evaluated. Background/hidden discovery retains immediate
cancellation. The existing Save/Record priority API is tested as a coordinator
contract, but has no actual caller yet; cross-page task-state wiring remains open.
This slice does not claim that end-to-end task-priority integration is complete.

Corrections covered by ordinary and independent local fixtures include:

- New eligible identities replacing unallocated entries count as productive even
  when total inventory cannot grow. Hidden or deduplicated results still do not.
- Empty/repeated-cursor cooldown and consecutive counts are query/version scoped;
  actual service retry-not-before constraints remain source-wide across searches,
  resets and versions. Promoting a service constraint discards unrelated paging time.
- Reset removes local paging counters/cooldown, but not real source rate constraints
  or independent history facts. A subsequent demand explicitly resumes discovery.
- Fully pinned pools are detected before issuing another provider request.
- A coalesced rapid resume retires cancelled requests before reopening scheduling;
  their late failures cannot penalize the provider or enter a newer session.
- Live replenishment and buffered inventory are not masked by stale round state.
  Mixed source refusal does not falsely label a successful aggregate query invalid.
- Invalid local UTF-16 input is rejected before cancelling a valid current search.

## Verification boundary

Focused work is recorded in `WIP_CHECKPOINT.md`. Final local results are appended
only after the strict-offline aggregate and independent frozen-source rerun pass.
The aggregate preserves all prior JVM, exact74, SQL, DOM, Media3 and ordinary
Commons/management host suites, replacing only the previous scope guard that
intentionally prohibited any Discovery change. The M8-A guard instead compares
all Commons/provider/security, dependency, media, entitlement, core and SkinKit
inputs byte-for-byte to M7-C and limits changes to this slice.

No APK/AAB, emulator/device installation, live Commons requests, production trust
roots/endpoint, real payment, new external dependencies or service deployment occurs.
Vault stays disabled and the blocked M7-D rule candidate is not integrated or retested.

## Final main-worktree local results — 2026-10-07 UTC

- Strict offline verification with all **87** requested Gradle tasks rerun: PASS.
  All ordinary JVM modules and debug/release Kotlin compilation/lint passed.
- **1,570** ordinary JVM tests: zero failures, errors or skips. Discovery **123**,
  app **728**. This is 39 additional ordinary tests over M7-C; independent probe
  counts and reused host-suite counts are not added to this aggregate.
- Both lint variants: **0 errors, 14 warnings, 6 hints**, matching M7-C totals.
  SDK/deprecation notices remain in raw logs; this is not a warning-free build.
- Both variants retain exactly **74** admitted external artifacts with strict
  coordinate/file/hash verification. All dependency/vendor/license inputs and the
  current SkinKit contract are unchanged. The fixed Commons main JAR has exactly
  **127** classes and no candidate rule runtime or test signing fixture.
- Ten static guards and production-extracted Discovery/Library/export/editor/
  recorded-segment SQLite contracts, plus 11 DOM-detector checks: PASS.
- Existing host media suites: HLS EOF **81**, mux conservation **15**, local HLS
  reader release and editor muxer release: PASS. No hardware/media-quality claim.
- Prior independent ordinary Commons/management host suite: **327/327** PASS.
- New independent ordinary Discovery probes: **12/12** PASS, including transaction
  failure outcomes, expired cooldown status and cancellation on failed progress read.
  The runner also enforces all eight prior M7-C compiler/runtime/JUnit artifact hashes.
- Independent review found and rechecked two source defects: failed mutations could
  show a success notice, and expired cooldown could leave aggregate BACKOFF stale.
  Diagnostic failures and the earlier precorrected full matrix remain preserved.
- All **44** frozen source/test inputs matched before/after the independent run and
  after the final aggregate. Manifest SHA-256:
  `40ea96e1c967fc6550868d9921b3da59577907f8ae0faebe16ae23848d1d5c92`.
  No scoped source blocker remains; see `INDEPENDENT_SOURCE_REVIEW.md`.

The final Git/tag, full bundle/source hashes, exact byte/mode comparisons and both
full cold-restored reruns are sealed separately in the external recovery receipt.
This document does not infer a cold pass before that receipt exists. M8-B/M9-A
independent follow-on options and acceptance boundaries are described in
`NEXT_INDEPENDENT_SCOPE.md`.
