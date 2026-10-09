# Independent ordinary-destination review

## Status

Final verdict: **no unresolved blocker found** for the ordinary-media A4a source/host scope. The final frozen compilation, JVM, lint, host-regression and channel-matrix aggregate has passed. This is development/source acceptance, not device or MediaStore acceptance.

Baseline: `a3a028d1e1de0ca20303ac7d14e4ed400e32a176`.

Reviewed frozen input set: **595 files**, manifest `evidence/frozen-review-source-manifest.json`, SHA-256 `fed8bf6ee042aa18c40335772b162910f8f3d666247cc042c45996baa9a8c014`. The hardened independent JVM rerun passed against this set, with all compiled inputs present and every manifest hash verified before and after execution.

Reviewed the new `library/.../destination` and `app/.../library/destination` implementation, their tests, and the ten authorized integration files: MainActivity, BrowserScreen, SaveTaskScreen, AndroidSaveRuntime, AndroidSegmentRecordingRuntime, RecordingScreen, AndroidEditRuntime, AndroidExportRuntime, AndroidSaveStorage and AndroidEditStorage. Read the unchanged ordinary ExportCoordinator and relevant ordinary storage/schema dependencies to verify the integration. No production code was changed by this reviewer.

## Finding resolved during review

The original admission failure callback unconditionally called `cancelSelection()`. An old asynchronous admission failure could therefore cancel a newer intent after background/cancel and re-entry. The fix introduces identity-scoped `gate.cancel(ticket)`, and the runtime reports/cleans up the failure only when that ticket is still current. Successful callbacks were already identity-fenced. The independent test covers all nine old/new preference combinations and asserts that stale failure, choice and completion cannot consume the new ticket.

The implementation also explicitly invalidates pending recording admission when the selected audio, recording mode or proportional recipe changes. Selection remains gated against foreground, screen, geometry and relevant browser version changes. A separate bounded copy lane now prevents a stalled provider copy from occupying admission/preference work. Status rendering validates the exact export request before displaying its phase, so a conflicting export journal row cannot appear as success.

## Reviewed invariants

- Selection precedes creation of Direct Save, selected static-HLS Save, basic recording, proportional recording, authorized-frame recording, and editor output. Cancel/stale completion leaves no accepted creation callback. A durable unbound admission may remain after interrupted admission, but it cannot issue an export request.
- `ASK` is a preference only; admitted choices are resolved to `APP_LIBRARY` or `ALBUM_COPY`. A ticket snapshots its preference, and a durable admission stores the resolved choice plus fixed operation/grant identities. Later preference changes do not alter an existing job.
- Direct/HLS/basic recording use the admission creation ID as the Save identity. Editor output uses it as the edit operation ID. Advanced recording uses it as the session ID and links each later processing operation to that retained admission before starting the edit workflow.
- Save and editor publication bind `MediaReadRef(itemId, revision=1, generation=1)` in the same final SQLite visibility transaction that inserts the verified ordinary item and marks the job successful. The exact read predicate additionally checks ready ordinary item/representation identity. Binding cannot change after the first source is committed.
- Legacy Save/Edit/session rows receive no admissions during migration or recovery. A retained legacy recording without an admission remains private-only. Routing scans admissions, never the historical catalog or current setting.
- Routing retains the same operation/grant/source request across repeated callbacks and restart. Existing non-PREPARED exports are observed rather than automatically copied again. A failure in one batch row does not prevent processing the other rows, and the durable rotating cursor avoids persistent first-page starvation.
- `AndroidExportRuntime` remains the sole application ExportCoordinator owner. The new copy entry point uses that same coordinator and existing Android export adapter. No second export implementation or publication protocol was introduced.
- Internal publication and the optional copy outcome remain separate. External pending, failed, cancelled or reconciliation-required outcomes do not revoke verified internal success. This new destination code has no original-deletion capability.
- V6 migration is additive for V5 and preserves the existing earlier migration sequence for V1–V4. Existing IDs, provenance, representations and export rows are retained. Foreign-key validation and helper-level transactional upgrade behavior are preserved.
- New UI uses the existing visual adaptation/components. New destination code adds no dependencies, permissions, logs, direct network calls, native capture engine or public-file implementation.

## Independent executable evidence

### Fresh-compiled JVM contracts

Command: `bash docs/m5a4a/evidence/run-independent-destination-review.sh`

Result: **6 tests passed**. The runner compiles the selected production ordinary models, destination contracts/router, selection gate and existing ExportCoordinator directly from source with the cached Kotlin/JUnit toolchain. It does not consume the Gradle build classes.

Coverage:

1. Two concurrent destination routing passes against the same real ExportCoordinator produce exactly one external create and publish, including twenty subsequent replays.
2. Durable PREPARED creation followed by synthetic acknowledgment loss preserves exact operation/grant/source identity; restart/replay produces one copy.
3. External source failure leaves the admission's private publication intact and cannot silently mint a replacement copy.
4. A stale source returned by the copy adapter is rejected before external creation.
5. Stale ticket failures and completions cannot cancel or consume newer selections across all preference pairs.
6. An unbound/cancelled-before-publication admission cannot issue an external copy request.

Artifacts:

- `evidence/IndependentDestinationReviewTest.kt`
- `evidence/run-independent-destination-review.sh`
- `evidence/independent-destination-run/results.log`
- `evidence/independent-destination-run/source-sha256.txt`
- `evidence/independent-destination-run/source-unchanged.log`
- `evidence/independent-destination-run/toolchain-sha256.txt`
- `evidence/independent-destination-run/toolchain-match.log` (all eight artifacts match admitted hashes)
- `evidence/independent-destination-run/frozen-before.log`
- `evidence/independent-destination-run/frozen-after.log`

### Host SQLite migration and exact-read contracts

Command: `python3 docs/m5a4a/evidence/independent-destination-sqlite.py`

Result: **4 tests passed, covering 108 injected rollback boundaries**.

The independent runner extracts production SQL literals and checks modeled migration ordering against production branch text. It uses frozen historical schema fixtures and synthetic rows. It verifies all old versions 1–5 reaching 6, preservation of prior columns/rows, close/reopen, no destination backfill, restored foreign-key validity, rollback of schema/rows/version at every DDL boundary, and exact-publication rejection of wrong IDs/revisions/generations and deleted/pending-delete representations.

Artifacts:

- `evidence/independent-destination-sqlite.py`
- `evidence/independent-destination-sqlite.log`

## Final aggregate evidence reviewed

The implementation-owner-controlled final driver completed on the same 595 frozen inputs. Reviewed result files establish:

- `evidence/strict-offline-aggregate.log`: strict offline debug/release compilation, JVM tasks, lint and runtime admission; **87/87 tasks executed**, build successful in 2m 17s.
- `evidence/aggregate-summary.log` and `evidence/junit-summary.json`: **1,875 tests, zero failures/errors/skips**.
- `evidence/lint-summary.json`: debug and release each have **zero errors, 14 warnings, 6 hints**. This is not a zero-warning claim.
- `evidence/channel-matrix/matrix-summary.json`: **7 explicit identities × 2 variants × 15 host tests** pass; five invalid build values are rejected; final default remains unconfigured and payments remain unconfigured for every channel.
- `evidence/frozen-inputs-after.log`: all **595 input hashes unchanged**. The manifest SHA-256 still matches the value recorded above.
- Both independent destination runners were rerun successfully within that final aggregate; their exact-input/toolchain records remain verified.

The source-scope gate protects all 2,968 baseline files outside the ten permitted integration edits, including dependencies, manifests, existing ordinary core/native engine implementation and excluded feature areas. The existing exact runtime dependency admission remains unchanged.

## Boundaries and limitations

These are source, freshly compiled JVM contract and host SQLite tests. They are not Android SQLiteOpenHelper/CursorWindow, real device, Compose interaction, process-kill, playable-media, MediaStore or system-consent acceptance. The V1 migration fixture intentionally has a failed Save and no readable legacy media; it does not claim to execute the legacy media backfill codec. SQL migration sequencing and synthetic export adapters are explicitly test doubles.

No Gradle was run by this reviewer; the implementation owner controlled the build slot. The final aggregate logs and summaries listed above were inspected read-only after completion. Source/runner inputs were not changed afterward; only this review prose was finalized.

No device, APK/AAB, account, payment, network capture, publication or deployment action was performed. Vault export and M7-D are outside this review, remain disabled/isolated, and were not reviewed, retried or used as substitutes. Their blocked review status is not changed by this ordinary-media result.
