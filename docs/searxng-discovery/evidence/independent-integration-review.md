# Independent SearXNG integration review

Review date: 2026-10-08 UTC. Baseline: `9e9dea537c5f`.

## Scope and method

Reviewed the ordinary first-layer SearXNG module, its Android configuration/storage/network/UI integration, pending candidate channel, coordinator/checkpoint changes, and relevant existing discovery contracts. Read AGENTS.md. Production changes were made by the implementation owner, not this reviewer.

No Gradle invocation, endpoint probe, APK/AAB, emulator/device, account, server deployment, or blocked candidate-code inspection/execution was performed. Independent host tests use the exact eight previously admitted Kotlin/JUnit jars, verified by SHA-256. These tests are not native Android UI or live-service acceptance.

## Findings reported during review

1. High: the initial configuration-edit fence reused the lifecycle suspension generation; lifecycle/priority updates could reopen the old query during a pending configuration write. The owner added a separate irreversible source latch. Independently checked that lifecycle, priority, mode, explicit demand, and late provider callbacks do not bypass it.
2. High: clearing progress only for the current profile left other profiles compatible after endpoint disable/re-enable or edit-away/edit-back. The owner bound production provider compatibility to the complete configuration generation. Independently checked same-URL revision changes and inactive-profile cursor rejection through the production generation wrapper.
3. Medium: strict JSON Unicode escapes accepted signed/non-hex input through numeric parsing. The owner added exact ASCII hex validation. Independently tested `+041`, `-001`, space, and `0x41` escape rejection.
4. Medium: error snapshots defaulted to configuration intent zero and were silently dropped after a configuration edit. Matching busy flags could remain set. The owner corrected publication to the current intent through DiscoverySourceUiFence and settles the matching flag. Inspected the helper, its two owner regression tests, and controller call sites.
5. Medium: a failed runtime initialization made an identical configuration reload return early, preventing recovery after storage became readable. The same-generation no-op now requires an initialized, healthy runtime. Recovery restores force and persist the explicit-search requirement; independently tested recovery and a subsequent restart.
6. Medium: source editing left old control targets usable for queued local history/progress commands and OPENED Seen writes. The owner added immediate target/history invalidation, executor intent checks, and core latch checks including transaction commit guards. Independently tested that old Seen/history/progress commands fail closed.
7. Medium: ephemeral pending cards were lost on restart while durable candidate-only exhausted/cursor state prevented a fresh same-query search. The owner added session-only progress for candidate sources. Independently tested cursor continuation, non-persistence, repeated explicit searches, restart, legacy exhausted-marker rejection, and preservation of existing default durable progress.

All seven findings above were addressed. The adjacent Commons/demo source-change path also calls the same source fence. Subsequent lifecycle review identified and resolved the two additional findings below; the earlier 19-test result alone did not establish lifecycle ownership acceptance. No outstanding blocking finding remains in the corrected ordinary first-layer slice. Final frozen aggregate, native Android UI, live-service and second-layer validation acceptance are separate from this review.

## Lifecycle ownership and initialization follow-up

8. High: an Activity-owned configuration controller could finish an accepted write after closing. A replacement Activity could already have loaded the previous generation and would receive no completion notification, leaving a disabled endpoint enabled in its runtime. A deterministic host reproduction established this sequence against the actual controller and file store. The separate file-lock suspicion was rejected: file-store instances already share a process-wide lock and stale-generation compare-and-set protection.

   The owner added SearxngConfigurationHub and AndroidSearxngConfigurationOwner. One process-owned controller/store/I/O lane survives Activity detachment. Current subscribers are fenced synchronously before accepted writes are enqueued; replacement subscribers observe pending state; terminal completion reaches current subscribers, including when the initiating Activity closed. Activity closure detaches only. UI claim invalidation has a separate epoch and does not cancel accepted operation publication.

9. High: runtime initialization could read ready state, then lose an accepted-write fence directed at the previous/null coordinator, publish a new coordinator from the old configuration, and reopen the old query. The owner added captured initialization claims and generation/intent/busy validation before queued application, after publishing the new coordinator reference, and again before lifecycle dispatch. Stale coordinators are irreversibly latched. Applied intent is the captured intent rather than a later value. A null unavailable snapshot is allowed only as a fallback that registers no SearXNG provider. Storage failures also use the irreversible source latch.

The corrected final host suite passes 33 tests: the original 19 integration tests, 9 actual Hub/controller lifecycle tests under deterministic I/O/main queues, and 5 actual pure-claim/real-coordinator interleaving tests. Eighteen static wiring assertions additionally check process ownership, subscription order, detachment, pending-state handling, captured configuration/intent, post-publication rechecks, and irreversible error fencing. Exact eight admitted toolchain hashes and unchanged compiled/inspected source hashes were verified.

Android Handler, Compose state, and the UI state constructor have explicitly labeled host doubles. These tests exercise the real Hub/controller/pure fence/coordinator code; they do not instantiate AndroidDiscoveryController or model Compose rendering/thread snapshots. Its integration is source-reviewed and hashed. Native Android/device acceptance remains separate.

## Positive observations

- No configured URL by default; exactly three ordered stable slot IDs; explicit endpoint/upstream query disclosure and acknowledgment.
- SafeSearch UNKNOWN remains fail-closed before transport creation. Existing requested filters are not silently rewritten.
- Process-owned physical transports survive endpoint edits and disable/re-enable. DNS checks every answer; TLS checks the selected public peer, hostname, platform trust, SNI, and HTTP/1.1. No ambient cookies, authenticator, client keys, proxy selector, redirects, decompression, or unbounded replacement stream.
- Candidate results have a distinct SOURCE_REPORTED type. They do not become video-confirmed from the videos category, do not write Seen or verified-pool entries, do not persist pending metadata, and have a separate 500-record cap.
- Pending cards are text-only; original-page navigation is an explicit user action. No automatic page, thumbnail, or media fetching and no license/actual-duration claim.
- Default historical checkpoint encoding remains version 3; configuration-key/query-authority extension is additive version 4.
- The documented future target still requires actual Scrapling validation before promotion to the confirmed pool; this first-layer increment does not claim that integration is complete.

## Evidence

- `IndependentSearxngIntegrationTest.kt`
- `run-independent-integration-review.sh`
- `IndependentConfigurationLifecycleTest.kt` and `IndependentInitializationFenceTest.kt`
- `independent-lifecycle-doubles/` and `check-independent-lifecycle-wiring.py`
- `runs/review-lifecycle/results.log`, `exact-test-count.log`, and `source-wiring.json`
- `runs/review-lifecycle/source-sha256.txt`, `source-unchanged.log`, and toolchain evidence
- Historical `runs/independent-review/results.log`
- `runs/independent-review/source-sha256.txt` and `source-unchanged.log`
- `runs/independent-review/toolchain-sha256.txt` and `toolchain-match.log`

The final corrected standalone run under `runs/review-lifecycle/` passed 33 tests plus 18 source-wiring assertions with unchanged compiled and inspected source hashes. The earlier pre-Hub run passed 19 tests. Earlier successful 12-test and 17-test runs are preserved separately. An earlier exploratory harness used unwrapped raw providers and reproduced the original cross-profile compatibility failures; its logs are preserved under `pre-binding-harness/`. The production wiring uses `bindGeneration`; the final 33-test suite exercises that wrapper, session-progress behavior, process-lived configuration ownership, and initialization fences. Android controller/UI behavior was source-reviewed; this runner does not instantiate Android or execute Compose.
