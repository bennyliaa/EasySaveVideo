# M8-A independent ordinary source review

Status: PASS for the ordinary M8-A source scope below. Both findings were corrected and rereviewed. No unresolved source blocker was found in this scope.

Final frozen input: `evidence/frozen-review-source-manifest.json`, SHA-256 `40ea96e1c967fc6550868d9921b3da59577907f8ae0faebe16ae23848d1d5c92`. All 44 manifest entries matched both size and SHA-256 before and after the final independent run. This approval attaches to those exact bytes, not to a moving branch name.

## Scope and limits

Reviewed the ordinary M8-A diff against baseline `35dfacf03c38b6359719e184e74e1d2ce5880625`: local result sorting, checkpoint v3, committed provider progress presentation/cache, bounded coordinator admission and cooldowns, lifecycle cancellation, and Android controller/UI wiring. Read `AGENTS.md` and `SCOPE_AND_SEQUENCE.md` before review.

This review does not cover the separately blocked rule-runtime candidate, Vault, security acceptance, or unchanged Commons maintenance internals. No candidate branch/tag was inspected, denied test repeated, production source edited, Gradle invoked, or network/service/device/APK work performed by this reviewer. Android UI was source-reviewed, not device-tested.

## Findings

1. False local-sort completion notice after transaction failure. The controller originally changed its local sort and displayed an applied-sort notice even though `reviseView` could catch a failed checkpoint write and return without committing. Reported to the implementation owner. The owner changed view/history/reset mutations to Boolean outcomes and gated their controller notices. Resolved: failure-injection probes verify rollback and false outcomes against the final frozen source. Controller source rereview confirms success notices, selected local sort, and page-index changes are gated on successful revision outcomes.
2. Expired aggregate BACKOFF state. Two empty non-exhausted pages establish a query-scoped cooldown. Once the clock reaches its deadline, the provider snapshot correctly becomes READY, but the aggregate still reports BACKOFF through its stale terminal state. Reproduced with the independent local fake-clock probe. Resolved: aggregate BACKOFF is now reported only while a relevant cooldown deadline is still in the future. At expiry it reports the finished round without starting demand. The independent probe passes with the provider READY and the request count unchanged.

## Independent evidence

- `evidence/independent-discovery-review.kt`: independent local-only source probes.
- `evidence/independent-run-discovery-review.sh`: direct existing Kotlin 2.2.10 compiler/JUnit runner; no Gradle or dependency fetch.
- `evidence/independent-initial-results.log`: first nine probes, eight passed and one failed on the expired aggregate BACKOFF finding.
- `evidence/independent-initial-source-sha256.txt`: source hashes for that diagnostic run.

- `evidence/independent-precorrected/`: expanded diagnostic run, eleven of twelve passed; the same BACKOFF finding was the only failure.
- `evidence/independent-final/independent-results.log`: final **12 tests passed, 0 failures**.
- `evidence/independent-final/independent-compiled-source-sha256.txt` and `independent-source-unchanged.log`: exact compile/runtime/review inputs and successful post-run verification.
- `evidence/independent-final/independent-toolchain-sha256.txt` and `independent-toolchain-match.log`: eight compiler/runtime/JUnit artifact hashes exactly match the prior M7-C record, comparing both artifact basename and SHA-256. Version labels alone are not the pin. The runner refuses to compile on any mismatch.
- `evidence/independent-final-freeze-verification.json`: the 44-file frozen-input verification.

The twelve local probes cover failed revision/history/reset outcomes and rollback; progress-read failure/cancellation; cooldown expiry without new demand; coalesced resume with late success and failure; source-wide rate-limit retention; query-scoped cooldown invalidation after provider-version change; exhausted empty responses; cached cursor expiry without reads, reset or new requests; anchor-preserving sort revision; and genuine v2/v3 codec round trips.

The runner creates a fresh class directory, captures exact hashes, verifies its eight existing toolchain artifacts against M7-C, runs JUnit with the existing host Java, verifies all source/runtime input hashes again, and removes transient bytecode. The final run is separate from the diagnostic failures. `git diff --check` over the reviewed production scope passed.

## Source assessment

- Sorting puts history-policy priority first, uses only the requested metadata, keeps unknown metadata last within each history bucket, and settles ties by arrival then result ID.
- Source query/profile identities are unaffected by the sort selection. Current-page IDs remain anchored on revision; later allocation uses the selected order and existing alias/history filtering.
- Checkpoint v3 adds the local enum after history policy; genuine v1/v2 inputs default to source order. Existing rights and row validation remain in the codec. The frozen v2 fixture and v3 round trips are exercised locally.
- Productive capacity replacement is measured from surviving eligible newly admitted IDs, rather than net inventory growth. Fully pinned capacity is checked before launching additional demand.
- No-new/repeated-cursor cooldowns carry query/version scope; explicit server retry deadlines remain source-wide across query/reset transitions.
- Cached provider snapshots use committed progress plus active work and current compatibility/time checks; they expose no cursor or query strings. Polling does not reset an expired cursor or launch a request.
- Coalesced immediate suspend/resume retires old cancellation/accounting before launching the replacement, so late success/failure callbacks cannot affect the new round.
- Invalid local query validation now precedes immediate suspension. The existing valid session is left running when malformed local text is rejected.

## Reusable command and remaining gates

Run `bash docs/m8a/evidence/independent-run-discovery-review.sh /absolute/output-directory`. The runner honors `ESV_REVIEW_REPO`, `ESV_REVIEW_CACHE`, `ESV_REVIEW_JAVA`, and `ESV_TOOLCHAIN_ROOT`; without the explicit output argument it uses `ESV_EVIDENCE_DIR` or `build/m8a-review`. It writes every generated report/hash/log to that output directory, so cold restores can preserve the original evidence.

Passed here: ordinary source review, the final twelve independent local probes, exact eight-artifact M7-C toolchain comparison, reviewed-scope whitespace checks, and frozen-source equality before/after testing.

Not run by this reviewer: Gradle aggregate, Android compile/lint, cold restores, APK/AAB build, device/emulator tests, live Commons requests, or service acceptance. Parent-owned aggregate, compile/lint, cold-restore, and archive gates remain separately reported. Full M8, release, Vault, and rule-runtime acceptance are not implied. Any later production-source change requires rechecking the affected review and source hashes.
