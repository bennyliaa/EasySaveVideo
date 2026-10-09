# M6-B runtime, UI lifecycle, and publication admission review

Review status: **COMPLETE for the scoped source/host review. No remaining runtime/UI/
publication-admission source blocker found after corrections.**

Final frozen manifest: `docs/m6b/evidence/review-source-manifest.json`, **415 entries**,
SHA-256 `073eff4a2da2ec532380cf545061cda6ff7207465be456f0bb322199b4a97f73`.
All entries were independently rehashed and matched on 2026-10-06 at 23:20 UTC.
The final focused offline host run passed **53/53 tests**, and all compiled source/test
files were unchanged during that run. This does not claim the separate aggregate build
result or Android/device acceptance.

Reviewed against baseline `9b25083134221bb4a86d71e76601cc65b7d10937`, WIP checkpoint
`b28b6b4`, and the moving post-WIP tree on 2026-10-06. This review is restricted to
runtime/UI ownership, consent correlation, safe stop/discard/recovery, and publication
admission. The separate native/storage review owns GPU, codec, file-identity and journal
internals. Vault remains disabled and outside this review.

No production source edits, Gradle builds, APK/AAB creation, device use, real projection
consent, playback, or physical pixel/audio testing were performed by this reviewer.

## Findings and correction criteria

### R1 — P1: cancellation and final catalog publication lack a shared admission winner

Observed in `AndroidEditStorage.publish`, originally lines 196–224, and
`AndroidSegmentRecordingRuntime.discard`, originally line 180. The final transaction
checks the volatile cancellation token at entry, then creates catalog rows and marks
the recorded session PUBLISHED. Discard independently changes the token and segment
fence. No shared state decides whether discard or irreversible publication admission
won, despite the runtime comment describing an atomic cancellation fence.

Deterministic source schedule: final transaction passes `checkContext`; main-thread
discard returns; final transaction commits; the runtime subsequently reports Published.
The same gap exists for a deadline. The publisher's entry check could be specified as
an admission point, but the current discard API and presentation do not expose that
winner and may say cancellation/non-save while publication continues.

Correction reviewed in the final frozen tree: a bounded atomic publication-admission/cancellation
state machine shared by main-thread cancellation and the final publisher. Cancellation
before admission excludes visibility. After admission, cancel reports that commit is
underway/result pending, never promises discarded output. Admission should follow
output promotion and immediately precede the final visibility transaction. Storage or
commit uncertainty after admission retains files and recovery state. No main-thread
lock may wait for SQLite, hashing, fsync or native work.

Required tests include both race winners, barrier-interleaved concurrent contenders,
expired-deadline admission, committed late cancel, admitted failed/uncertain commit,
old-token isolation, and no publication after pre-admission cancellation.

`EditPublicationCancellation` now serializes cancellation and final admission with a
short in-memory monitor, never held across storage/native work. Both ordinary and
recorded edit entry points use it. The final publisher admits after promotion and
source revalidation, then marks committed after the visibility transaction. Definite
pre-admission rejection has a separate workflow result allowing owned-output cleanup;
post-admission uncertainty remains recovery-required. The correction is present in the
verified final manifest.

### R2 — P2: partial source cleanup previously mislabelled deleted rows

The original runtime selected every row with historical duration and represented it
as an available closed private fragment. A partial discard could delete ordinal 0,
fail to remove ordinal 1, and still label both as waiting for independent verification.

**Corrected source reviewed:** runtime `updateRows` and `showSnapshot` now carry
`independentlyValid`, `retained`, and `cleanupPending`; the card distinguishes removed,
pending-cleanup, verified, and unverified rows. Historical ordinals remain intact.
The complete-cleanup branch has no remaining private rows and preserves any published
result reference. The correction is present in the verified final manifest.

### R3 — P2: advanced admission can precede old foreground-service destruction

The advanced `otherMediaIdle` callback originally checked current ID, executing and
resource gates but omitted `recordingService == null`. Advanced owner release clears
the ID/executing fields after `finishSession` requests service termination, before
`Service.onDestroy` necessarily runs. A quickly started advanced session can therefore
send ACTION_START to the still-finishing old service, whose old request ID rejects it.
The new request then waits for start rejection. Basic M4 explicitly guards this boundary.

Correction criterion: retain the old service-destruction admission boundary for advanced
start, with identity-safe service destruction callbacks. A stop request is not service
destruction evidence.

**Corrected source reviewed:** the advanced idle predicate now also requires
`recordingService == null`.

### R4 — P2: exceptional recovery operations can escape the worker boundary

`process` calls `bridge.verifyAndPublish` directly. The bridge catch itself calls
recovery-state queries and journal updates without protecting secondary failures.
A SQLite exception followed by a failed `retainAfterFailure` can escape the executor,
leave active/global ownership held, and leave VERIFYING until the 90-minute processing
watchdog fires, although the worker has already exited.

Correction criterion: top-level worker exception containment must post an immediate
truthful recovery result, retain uncertain native/filesystem state, and avoid uncaught
exception logging. A failed recovery write alone must not be treated as proof of native
closure or safe publication.

**Corrected source reviewed:** `RecordingWorkerBoundary` wraps capture, retry and
cleanup jobs. An escaped exception posts immediate generic recovery, does not query
the failing storage again, and retains the global owner/files with a restart fence.

### R5 — P2: historical advanced presentation changes fresh basic-session disclosure

`RecordingScreen` originally used `advancedMode || segments != null` for scope/limit
disclosure. The latest historical advanced presentation remains non-null when no
recording is active. Selecting basic mode then starts basic capture but still presents
advanced default-display/crop/30-minute disclosure.

Correction criterion: use the selected mode or an actually active advanced session for
fresh capture disclosure; show retained-group history separately without changing the
selected mode's explanation.

**Corrected source reviewed:** `showingAdvanced` now uses selected advanced mode or
an active advanced presentation, rather than any historical presentation.

### R6 — P2: recovery discard can ignore an active job before its first closed segment

`discardRetained` originally required `selected` before checking `active`. Preparation
clears selected. A finalization timeout before the first closed-segment snapshot sets
RECOVERY_REQUIRED, whose discard button routes to `discardRetained`; it returns early
instead of setting the active job's discard fence. A late confirmed native close can
therefore retain raw media after the user explicitly chose discard.

Correction criterion: route the active job first, then require a selected snapshot only
for an inactive retained-group operation. Native uncertainty must still prevent deletion
until actual closure.

**Corrected source reviewed:** active-job dispatch precedes the selected-snapshot guard.

### R7 — P2: swallowed terminal snapshot failures can leave an inactive spinner

Several runtime terminal paths swallow a journal snapshot failure as null and only
change presentation when the snapshot exists, then clear the actual owner/current ID.
For example, `process` can return Published or Stopped while leaving presentation
PROCESSING/VERIFYING. That presentation remains active, hides new-start controls, and
its discard callback has no current ID. Similar omissions occur in failed processing
admission and incomplete cleanup.

Correction criterion: every actual terminal callback needs a truthful terminal or
recovery fallback presentation even if journal detail cannot be read. A Published
return may retain its verified catalog reference without inventing private rows.

**Corrected source reviewed:** null-snapshot terminal paths now have honest
fallbacks. The retry/retained-cleanup executor-rejection catches were also reported:
they cleared ownership but left VERIFYING/CANCELLING active. A one-slot queue can be
filled by repeated retained-navigation or count-refresh work before these submissions.
They now reconstruct the retained snapshot's recovery or published presentation before
releasing the rejected owner, preserving retry/cleanup affordances as well as history.

### R8 — P2: repeated discard can erase the nonrenewable waiting warning

After the processing watchdog changes the presentation to RECOVERY_REQUIRED, another
discard originally unconditionally changed it to CANCELLING, or PROCESSING if final
publication admission had won. The watchdog's stop ticket is intentionally not renewed,
so no new warning would restore RECOVERY_REQUIRED. Cancellation intent must still be
recorded, but an existing same-owner recovery warning must not be downgraded.

**Corrected source reviewed:** a pure waiting-phase policy preserves same-owner
RECOVERY_REQUIRED across repeated controls/deadlines. Runtime presentation ownership
scopes that policy to the active job; a new explicit retry or cleanup does not inherit
the prior owner's warning. The generic recovery label now says save status requires
verification, rather than claiming an uncertain commit has not saved anything.

### R9 — P2: native-driven safe-stop reason was absent from successful output notice

Projection resize/system stop can set the native session's reason without going through
the runtime's main-thread stop method. The runtime originally ignored the reason in
`RecordingCaptureProgress`, then based its final safe-prefix warning only on its own
fence reason. Paused safe-stop can also return the previous closed segment's expectation,
so that expectation alone cannot recover the final session reason.

**Corrected source reviewed:** the native progress receiver synchronously records the
non-cancel reason in the matching job fence before posting the UI observation and before
`run()` returns. Final notices include non-user stop reasons and only claim the verified
closed prefix. Actual capture safety was not found broken by the original omission.

## Source observations so far

- Consent remembers only an in-process request ID/stage/scope bit. Fixed-code results
  after process death are consumed without restoring projection authority. Cancelled
  tombstones prevent an old system result from being assigned to a new request.
- Advanced scope is correlated through the audio/projection steps. API34+ requests the
  default display; the code does not treat that request or matching dimensions as proof
  of application/content identity. The ratio schematic applies to the later authorized
  image, as explicitly stated in the frozen scope document.
- Preparation claims the global ordinary-media owner. Native timeout, cancellation and
  release uncertainty do not release it. Native-lease release requires actual worker
  cleanup. Processing has a separate operation watchdog and nonrenewable waiting notice.
- Start evaluates REGION_RECORDING, pause/resume SEGMENT_RECORDING, while safe stop,
  owned-source finalization/retry and cleanup remain free. Ordinary later editing keeps
  its independent feature gates.
- Retained-group navigation reads off the main thread, checks current selection identity
  before posting, and does not restore or reuse recording grants.
- A 15-second advanced preparation timer waits for an actual encoded RECORDING progress
  event. A separate 30-second queued retry/cleanup timer waits for actual worker start.
  Both are identity-bound; their timeout only cancels/revokes and retains ownership.
  Queue rejection clears its timer after definite rejection. Neither timer implies
  native release or deletion authority.

## Host evidence and remaining acceptance boundary

Independent offline host compilation and execution, without Gradle:

- Initial fence/watchdog run: **15 tests passed**.
- Current pure recording/core/library sources, fence/watchdog, worker containment,
  publication token, and edit workflow run: **33 tests passed**, including concurrent
  admission/cancel races and ordinary/recorded recipe paths.
- An intermediate expanded frozen run added presentation and startup-policy tests:
  **53 tests ran, one stale presentation-label assertion failed**. The production recovery label had
  correctly changed to uncertain save status, while the test still required the old
  "not yet saved" wording. The owner corrected the assertion without changing production
  behavior. The final immutable-manifest rerun passed **53/53 tests**.
- Local evidence is under `../esv-m6b-review/runtime-host/` and
  `../esv-m6b-review/runtime-host-current/`, each with `results.log` and exact tested
  `source-sha256.txt`. These are host state/order tests, not Android/device evidence.
- Final evidence is under `../esv-m6b-review/runtime-host-final/`: `results.log`,
  `source-sha256.txt`, `source-unchanged.log`, and `frozen-manifest-sha256.txt`.
  Full-manifest verification is recorded in `../esv-m6b-review/runtime-manifest-final.txt`.
  The independently runnable host command is
  `bash ../esv-m6b-review/run-runtime-review-host.sh <absolute-output-directory>`.

All R1–R9 corrections and the added startup/queue deadline paths were rechecked in the
final manifest. Host tests establish pure ordering, fences and presentation rules;
they do not establish
Android Activity/service behavior, projection consent, physical crop pixels, codec/GPU
release, audio synchronization, or phone/tablet/OEM compatibility. These remain NOT_RUN.
