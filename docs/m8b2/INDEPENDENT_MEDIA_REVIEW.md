# M8-B2 independent media application review

## Scope and status

Baseline: `3ca09f768bf255b066e00b82209e7d6821c3aa7e` (M8-B1).

Independent review scope: `AndroidSaveRuntime`, the activity/recovery callback changes in `AndroidSegmentRecordingRuntime`, `SaveTaskScreen`, the `MediaTaskActivity*` helpers and their new tests. MainActivity's subscription is inspected only as the media-priority consumer. Origin identity, browser navigation and Discovery controller/core policy have a separate independent reviewer.

This is ordinary source review plus pure JVM/Kotlin/JUnit host injection. It is not Android lifecycle, WebView, MediaProjection, codec, muxer, device, APK, or native-release execution evidence. No Gradle command was run by this reviewer, no production or existing test source was edited, and no network or new artifact was used. The excluded candidate and disabled protected storage work were not inspected or executed.

Final host-run result: PASS on 2026-10-07 UTC, 47/47 tests (16 independent injected tests and 31 existing B2 helper tests), zero failures. All 155 frozen inputs retained exact byte size and SHA-256 before and after; all eight admitted toolchain artifacts matched. No remaining blocking finding in the reviewed Save/Record ownership scope. The first preflight correctly rejected a changed AndroidSaveRuntime hash before compilation while author corrections were in progress. The first compiled run completed 47 tests with one independent-fixture failure: the synthetic resumed transfer omitted byte accounting, and the protected coordinator correctly rejected it as incomplete. Only that reviewer-owned fixture was corrected to report its transferred bytes; the original failure log is retained. Neither the blocked preflight nor the fixture-failure run is counted as a full pass.

## Findings and corrections

### M1: initialization-to-main handoff could expose idle (corrected)

The original control command posted installation of the segment runtime, then decremented its pending-media counter on the control thread. Before main installed that runtime, a subscriber or queued snapshot could observe zero commands and a null segment runtime even though the independent segment recovery worker was still running.

The corrected `MediaTaskActivityHandoff` starts owned and keeps that ownership after worker return until main has installed the continuing runtime owners. The main installation releases the handoff only in its `finally`. `AndroidSaveRuntime` includes the handoff's `active` fact in its actual application signals. The installed segment runtime then supplies `recoveryActive`, which releases only after its recovery-result delivery on main. Prepare/dispatch failure paths explicitly retire their own handoff. Readiness and old journal rows do not serve as activity evidence.

### M2: periodic expiry cleanup had no media owner (corrected)

`publishRecords()` formerly called `expireWaitingUsers()` inside an uncounted read/refresh command. That coordinator path may call `finishFailure()` and perform actual private artifact cleanup. An idle application could therefore run cleanup with no Discovery media-priority owner.

The corrected path first performs a bounded read-only scan. Only a due WAITING_USER batch obtains `MediaTaskActivityExpiry` ownership. Main then publishes the counted command owner before dispatching cleanup to the control worker. Completion or rejection releases the exact one-shot batch; duplicate/late work cannot release another batch. Empty/not-due scans create no priority edges and cannot create an artificial busy/idle demand cycle. Bounded in-memory source expiry is retained separately through `sourceForTask` reads.

The callback is deliberately not treated as a synchronous main delivery when called from a worker. For expiry, the main-thread counted command is established before any cleanup worker can execute. For initialization, the handoff remains active until installation. Other newly starting Save/Record routes already originate on main and establish a command, execution reservation, or segment `active` owner before dispatch.

## Commit and optional-history boundary

- New direct and fixed-HLS selections capture a process-local handle; accepted existing/idempotent tasks cannot bind a new one. Accepted, queued, running, provisional COMMITTING, failed, cancelled, interrupted and waiting results are insufficient.
- `launchExecution` invokes the association only after the existing synchronous Save coordinator returns. `mediaTaskHasCommittedSave` requires the exact task, DIRECT/STREAM, SUCCEEDED, matching receipt/commit/artifact identifiers, byte length/hash, and full existing verification against the transfer and selected expected-audio requirement.
- The protected coordinator still verifies, prepares a provisional commit, freshly inspects it, then publishes SUCCEEDED through its final atomic journal/index decision. No codec, muxer, verifier, storage-publication or cancellation algorithm was changed by B2.
- Association removal occurs before completion. Cancellation, unlaunched/rejected work and failed final-record reads consume/discard only the optional association. Resume, journal refresh and process recovery do not create one. Duplicate or late completion cannot repeat history work through that link.
- Optional history errors cannot call Save execution, alter its state, undo a commit, or cause another publication. UI outcomes are keyed by the stable task ID and displayed only on a SUCCEEDED row; the runtime stores only COMMITTED-media outcomes. Delayed history results retain their own task key.
- Media COMMITTED is distinct from proof that the selected resource is the exact Discovery video. The final native integration's `IDENTITY_UNVERIFIED` outcome truthfully keeps that video's saved status unknown when only page provenance exists. This review does not upgrade page provenance into verified media identity or claim a production SAVED history write in that case.

## Actual activity and screen lifecycle

The application singleton owns activity independently of screens. SaveTaskScreen only observes records/outcomes. The MainActivity subscription replays the current snapshot; closing it detaches one observer and does not cancel, release, or declare media idle.

Signals cover initialization, pending media commands, direct execution, HLS preparation, selected variant ownership, cleanup, pending recording consent, Basic recording owner/service, segment capture/preview/processing, asynchronous segment recovery, and known blocked/draining resources. Paused recording remains active because its application owner survives execution-state changes. HLS stale-result delivery/discard and normal worker cleanup retain the relevant reservation until retirement. Segment ownership persists across native uncertainty and exceptional storage/processing results; leaving the recording screen is not a release signal.

The tracker serializes snapshot publication, replay and detach, isolates throwing observers, and rejects stale reentrant revision delivery. Snapshot inputs do not use the current screen or a count of historical nonterminal rows as evidence of active work.

## Explicit limits and missing signals

- This slice provides immediate owner-driven signals for Save/Record and their cleanup. Shared global `OrdinaryMediaNativeResources` and `VerifiedMediaPreviewResources` are sampled, not subscribed to. A claim/release/unconfirmed transition originating solely in unrelated editor, thumbnail or ordinary preview activity may be learned at the next foreground poll (nominally one second) or another owner update. This is not proof of instantaneous notification for every global native gate.
- No Android/native device evidence is provided. Host tests cannot establish actual platform callback ordering, MediaProjection behavior, codec/muxer resource exit, or physical cleanup. Those remain separately authorized validation work.
- No trustworthy playback or exact discovered-video identity signal is manufactured. Conservative unknown states are intentional when evidence is absent.
- This independent media report does not substitute for the build owner's strict aggregate, lint, runtime admission, complete backup/cold-restore verification, or the separate origin/browser/core review.

## Evidence

- Shared exact source freeze: `evidence/frozen-review-source-manifest.json`, SHA-256 `4d149074b291ea448dc46e93d4a4f86d05fb3c080b368f3b7375dd3b9170cdaa`.
- Final test log: `evidence/independent-media-run.log`; detailed per-run hashes and freeze checks: `evidence/independent-media-run/`.
- Independent injection source: `evidence/independent-media-review.kt`.
- Reproducible offline standalone runner: `evidence/independent-media-run.sh`.
- Exact byte preservation: `evidence/independent-media-protected-bytes.json` verifies 79 baseline ordinary Save/Record/HLS files outside the three authorized application orchestration edits.
- The runner records all compiled input hashes, verifies the shared source manifest before and after, and matches all eight compiler/runtime/JUnit artifact hashes to the already admitted ordinary host toolchain reference.

The injected cases exercise the production pure coordinator, association and activity helpers: held provisional commit, fresh-inspection failure, cancellation versus late commit return, duplicate acceptance, explicit resume, process-local restart, history failure isolation, delayed task-keyed result, held expiry cleanup, initialization main handoff, main-before-worker expiry ownership, empty scan stability, observer detach/replay, final-record failure, and all 1,024 signal-owner combinations. Test fixtures provide synthetic media facts only.
