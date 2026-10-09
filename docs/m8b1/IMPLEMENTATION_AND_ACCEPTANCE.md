# M8-B1 — Bounded local history and explicit controls

This is an independent development slice following M8-A, not full M8 or Android
installation acceptance. Baseline is `0762843aa22bf1907cedf5aeed84224fdb335f0f`.

## Capacity and deterministic retention

The default durable and isolated-store policy caps live Discovery state at:

- 32 occupied search profiles (query, source/safety/duration filters and locale)
- 65,536 Seen identity/alias facts in total, across all profiles
- 512 provider/query continuation records in total
- 8 session checkpoints, each retaining the existing maximum 500 results/pages

This is capacity-only retention, with no age expiry or clock-based assumptions.
A write allocates one monotonic local mutation sequence for its whole transaction.
Reads never update sequence or recency. Profiles are pruned first, then historical
checkpoints, whole explicitly linked Seen identity groups, then provider progress.
Oldest mutation sequence wins; profile/identity/provider/query/session keys settle
ties deterministically. A profile eviction removes its Discovery records only.

Every materialized checkpoint protects its profile, checkpoint, all historical
alias-group members touching its materialized results and all progress for its
profile/query. A checkpoint written by the current transaction is also protected.
If protected records alone cannot fit the hard limits, the entire write fails and
rolls back. Existing pages never lose, reorder, or silently replace their data to
make room. Old unprotected history can be forgotten and an old result may appear
again. The options UI explains the limits and this consequence.

These are live-row/data bounds, not a promise that SQLite immediately returns disk
space to the OS. Deleted pages may remain on SQLite's freelist for reuse; migration
of a previously large database does not run VACUUM or promise file shrinking.
Protected legacy content that exceeds a bound causes a preserved, unavailable
upgrade rather than destructive recovery. Device storage/power-loss acceptance
remains separate.

## Alias and event independence

An explicit alias group has one canonical identity key (lexicographically smallest
fingerprint). Transitive linking unions only existing records. Each event timestamp
merges independently using max; first-discovered uses min. Linking or retention
never manufactures SHOWN, OPENED, PLAYED, or SAVED, never interprets discovery as
visibility and never interprets opening as playback. Group eviction deletes all
known members together. Individual putSeen writes also propagate only observed
facts across an already linked group.

The Android database remains `discovery_v1.db`; SQLite schema3 is distinct from
checkpoint codec3. Genuine database versions1/2 upgrade atomically: v1 receives its
existing nullable rights column, then metadata/recency fields and profile/sequence
tables are added. Old aliases initially become independent singleton identities;
only relationships proven by persisted result identity aliases are linked.
Unknown historical relationships are not guessed from identical timestamps.
Corrupt/unsupported old checkpoints, rights or capacities abort the full upgrade,
preserving the old schema/data/version. Unknown or newer schemas are never reset.

All writes, alias merges, pruning, sequence changes and checkpoint updates commit
as one store transaction. Provider completion installs a final cancellation guard
after pruning and immediately before commit/publication; a failed guard rolls back
the entire transaction. Pure statistics are detached count-only reads and never
prune, materialize pages, rewrite cursors or initiate source demand.

## Local controls

Current-profile and all-profile history clearing remove Seen facts only. Source
progress, current page contents, media-library rows, exported files and Vault are
independent. Every matching checkpoint gets a new view revision even when its Seen
history was empty, fencing old viewport and provider callbacks. New actual viewport
observations after the clear may naturally record SHOWN again.

A per-provider reset affects only the captured current profile and selected source.
It preserves Seen facts, other providers/profiles, materialized results and actual
source-wide Retry-After constraints. It retires only matching active requests and
ends automatic demand; unrelated active source requests may complete. New requests
start only with subsequent explicit demand or a separate normal lifecycle action.
The supported app has one live coordinator per store; independent concurrent
coordinators are not a general multi-writer synchronization contract.

Every destructive control uses a second consequence dialog and captures profile,
session stamp, view revision and controller generation. IO execution revalidates
that exact target; changed searches/instances reject stale confirmations. Failed
mutations never display success. Statistics refresh and mutation-outcome refresh
have no page-demand, source-reinitialization or viewport-write side effects, and
clearing/resetting disables pending automatic page materialization. Demo controls
explicitly affect only demo memory state and cannot clear real local history.

## Verification boundaries

Ordinary tests cover the actual pure Kotlin planner/store/coordinator and UI action
policies. Host SQLite tests execute real disk-backed SQLite with source-extracted
production DDL and source-guarded host models, including actual old schemas and old
codec-header fixtures, failure rollback and reopen. They do not execute Android's
SQLiteOpenHelper, CursorWindow, lifecycle, rendering or filesystem power-loss behavior.

The strict aggregate preserves all existing tests/host suites and exact74 runtime
admission. The new B1 scope guard compares unchanged protected source/dependency/
SkinKit/provider/media inputs to M8-A; the historical M8-A guard is retained as its
own historical scope, not silently weakened. Independent frozen-source review and
two complete source/bundle cold restorations are separate gates.

No APK/AAB or install, live-source request, production account/root/service, payment,
new dependency/external implementation or SkinKit upgrade is part of this work.
Vault remains disabled; the blocked M7-D candidate and denied review/probes are not
read, retried, integrated or substituted by these ordinary history tests. B2/B3
remain pending.

## Final main-worktree results — 2026-10-07 UTC

- Strict offline, strict dependency verification and nonincremental Kotlin: all
  **87** aggregate Gradle tasks rerun, PASS. Debug/release compile and lint passed.
- **1,602** ordinary JVM tests, zero failures/errors/skips: Discovery **144** and
  app **739**, with all other module totals unchanged. This adds **32** ordinary
  tests to M8-A (12 retention + 9 core-control + 11 UI-policy tests).
- Both lint variants: **0 errors, 14 warnings, 6 hints**, matching M8-A. Existing
  SDK/deprecation notices remain in logs; this is not a warning-free build.
- Both variants retain exactly **74** admitted external artifacts. Protected
  dependencies, vendor/licenses, media modules, current SkinKit contract and fixed
  Commons schema1 are unchanged; compiled Commons main JAR remains **127** classes.
- Ten scope/admission guards, all historical Discovery/Library/export/editor/
  recorded-segment host SQLite suites, the new history suite, and **11** DOM checks
  passed. Existing media host suites include HLS EOF **81**, mux conservation **15**,
  local-reader and editor-muxer release checks. Ordinary Provider host **327/327**
  and prior independent Discovery **12/12** also passed.
- New host SQLite suite passes genuine1/2→3 schemas, 12/11 migration fault positions,
  17 corruption/protected-upgrade refusal cases, close/reopen, scope/alias/cancellation
  contracts, and 1,000 seeded bounded mutations (990 commits, 10 expected protected
  rollbacks). These host-model counts are not added to the JVM total.
- New independent ordinary review suite: **16/16**, including both old-codec
  header/profile/query-key bridges. All **40** frozen source/test inputs and **8**
  toolchain artifact hashes matched. Final manifest SHA-256:
  `97c4cff59a4cc25d0fbf7f37a245e2a3eccee5783a74184fdc6d1002bde81101`.
- A pre-review parity correction removed unintended profile recency updates from
  automatic SQL checkpoint eviction. The independent fixture-authoring expectation
  error and stale incremental-bytecode diagnostic are retained separately. The
  final complete nonincremental and independent runs both passed.

Final Git/tag, full bundle/source hashes, tracked byte/mode equality and the two
complete cold-restored reruns are sealed in the external recovery receipt. This
source document does not claim a cold pass before that receipt exists. B2/B3 and
actual Android device behavior remain deferred as described in the handoff files.
