# M8-B3 — Bounded injected power policy and admitted filters

Baseline: `d20b1c9579af65977a4edb79210e5e9643daa2d9` (M8-B2).
This is ordinary JVM/host and source-level development. Complete M8, physical
Android acceptance and measured energy savings are not claimed.

## Dynamic policy boundary

Pure caller-supplied battery, thermal and network enums have no OS reader, clock,
threshold calibration, background worker or new poller. Disabled inputs report
NOT_CONNECTED, not a healthy device. Enabled unknown inputs, critical battery,
severe thermal state or offline network pause requests. Low battery, warm state or
metered network cap work to at most one concurrent operation, three attempts per
explicit round, and one/two-page low/high watermarks. All values intersect existing
static mode, limits, foreground/visibility and real Save/Record priority.

Every changed power snapshot or immediate power fence ends automatic demand and
cancels active requests/retries without refunding attempts. Relaxation and status
reads do not launch work. A fresh explicit search, next-page/request-more or existing
user mode/view action supplies bounded demand. Lifecycle/Save-Record interruption
may resume only the unexhausted prior round; repeated busy/idle/hidden transitions
cannot mint fresh six-request rounds. An immediately fenced pending policy update
also prevents local page materialization from resetting old request accounting.

Power and lifecycle fences use separate issued/applied generations. Old callbacks
cannot acknowledge newer restrictions, commit canceled results, revive old retries
or interpret cancellation as provider failure. Application lifecycle snapshots
capture a generation before reading foreground, visibility, Save and Record state.
New coordinator initialization applies its initial power snapshot at generation 0,
so it cannot consume a later debug-selection fence. View revisions keep transient
policy on the same scheduler; restoring a new process starts NOT_CONNECTED and
never restores synthetic observations or in-flight requests.

Production Android has no approved OS signal adapter and deliberately keeps this
policy NOT_CONNECTED. Existing static mode remains functional. Debug builds expose
explicit transient synthetic scenarios through a source-set allowlist; release has
an empty allowlist and refuses those selections. The app's coalesced input lane
has at most one queued update and one latest payload. It drops stale-controller
updates and old pending-page demand, and uses a read-only refresh. There is no new
periodic collection. UI states that neither OS monitoring nor energy measurement
has been performed.

## Admitted source filtering and exact restore

The UI receives the exact active registry descriptors used by the coordinator. A
pure presentation mapping recognizes only the fixed admitted Commons namespace
and the isolated debug demo namespace. Unknown descriptor IDs are not treated as
namespaces and do not automatically enable a provider. No provider, dependency,
endpoint, query interpretation or transport behavior changes.

Applied SearchFilters remain in the existing checkpoint/profile/query identity.
Draft changes never query a source or silently rewrite applied values. Obsolete,
disabled or case-preserved namespace selections stay visible until the user removes
them explicitly. Empty selection retains the existing meaning, all current enabled
sources, including zero. Minimum/maximum duration editors round-trip every allowed
nonnegative Long millisecond bound without dropping fractions; unsupported and
unknown metadata remain distinct, with explicit include/exclude unknown duration.

Drafts survive screen navigation in the controller, and process reconstruction uses
the last applied checkpoint exactly. Unreadable/initial UI placeholders never replace
an existing draft or present unverified default filters as applied. Submit and safety
confirmation use the complete controller/session/view target, exact current draft,
availability and a main-thread busy claim, with serialized revalidation for search.
Source changes fence controls immediately; published source revision prevents old
main snapshots from accepting a confirmation during registry reinitialization.
Text edits rebase only their own field under the same target/baseline, allowing
successive IME events before recomposition without losing another field's edits.

Commons still truthfully refuses unsupported safe search without sending a query.
Changing that draft choice requires a target-bound confirmation; applying the whole
draft is a separate explicit search. Dates may be unknown, durations are source
reports and rights metadata is not authorization. Count-only history statistics,
source option display, draft editing and policy status never create demand.

## Preserved boundaries and remaining work

B1 history revision, alias grouping, independent monotonic event columns and capacity
rollback remain unchanged. Native video SAVED and PLAYED remain UNKNOWN: page origin
cannot prove provider-video identity. Save/Record owner priority remains immediate;
unrelated shared editor/thumbnail resources retain existing sampled observation,
not a new promise of immediate subscription.

Commons fixed schema1/zero-provider fallback, exact74 artifacts, SkinKit contract,
API31 phone/tablet target and debug-only fake entitlements are unchanged. M7-D blocked
candidate/runtime/review/probes or replacements, Vault, new providers/libraries,
production services/accounts/payment, ESV APK/AAB/device work and SkinKit edits are
excluded. OS wiring, real power measurement and M9-A remain separate work.

## Evidence status

The compilable WIP result is recorded in WIP_CHECKPOINT.md. Final aggregate,
independent source/host review and two complete cold restoration results are added
only after execution. Source inspection does not prove Android Looper/Compose,
SQLiteOpenHelper, native device lifecycle or actual battery behavior.

## Final main-worktree results — 2026-10-07 UTC

- Strict offline/dependency verification, Kotlin nonincremental compilation and all
  **87/87 Gradle tasks** rerun: PASS. Both compile and lint variants passed.
- **1,759 JVM tests**, zero failures/errors/skips: Discovery **194**, app **846**.
  This adds **61** ordinary tests to B2. Other module totals are unchanged.
- Each lint variant retains **0 errors, 14 warnings, 6 hints**. Existing SDK,
  metrics and deprecation notices remain; this is not a warning-free build.
- Exactly **74** admitted runtime artifacts in each variant. B3's current source
  gate verifies **919** protected files unchanged and fixed Commons main-JAR
  **127** classes. Historical M7-A/B1/B2 gates and B2 evidence remain unchanged.
- Nine source/admission gates, all six inherited SQLite host suites, **11** DOM
  checks, HLS EOF **81**, mux conservation **15**, local-reader/editor release
  checks, ordinary Provider **327** and M8-A Discovery **12** host tests pass.
- Inherited B2 origin/B1 history **34** and media **47** tests pass on B3 source.
  New independent power tests pass **24 per debug/release variant**, and new
  filter/restore tests pass **29**. These host selections overlap other tests and
  are not added to the JVM aggregate count.
- All **504** frozen inputs and exact **8** standalone toolchain artifact hashes
  matched before/after independent runs. Shared manifest SHA-256:
  `cd97d5a2f347489b1b138fd1dae065e81ab6c6d5ac9e4d7ab255e4883d143d4c`.
- Review corrections were rerun in the complete aggregate, including init-power,
  old lifecycle/Save resumes, pending materialization accounting, repeated submits,
  stale source/safety confirmation, unavailable defaults and consecutive IME edits.
  The initial legacy counting diagnostic remains distinct from passing final logs.

Final commit/tag, archive hashes, exact tracked bytes/modes/refs and **two complete
cold-restored aggregate reruns** are sealed in the external recovery receipt after
execution. This document does not claim those cold passes before that receipt
exists. Device/OS/energy behavior and native video identity remain explicitly
unimplemented or unverified as described above. M9-A has not been started.
