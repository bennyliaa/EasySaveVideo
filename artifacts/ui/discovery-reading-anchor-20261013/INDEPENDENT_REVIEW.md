# Independent review: Discovery reading-anchor slice

Review date: 2026-10-13 UTC. Review state: **SOURCE AND BUILD-EVIDENCE REVIEW COMPLETE; DEVICE/COMPOSE NOT_RUN**.

## Scope and verdict

No remaining source-review blocker was found in the frozen seven-file slice. This is a source-review verdict, not a build or device acceptance claim. Build1 has completed against the 1,403-file input manifest. The final addendum records independently checked build evidence; this does not establish device acceptance.

The source baseline is `c794ef84344d638f20966a1cd05bb5b340ade7e0`. The reviewed input manifest was created at `2026-10-13T09:37:05.256187+00:00` and lists seven changed files. The pre-slice production APK remains separate from this slice and cannot verify it.

Review was read-only against production source. The reviewer did not change source, invoke Gradle, assemble/install an APK, run a device or execute the new tests. This report is written only to the requested scratch directory.

## Frozen files and SHA-256

| File | SHA-256 |
| --- | --- |
| `app/src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryReadingAnchorPolicy.kt` | `3a8743b6919e2a9b4f7dbef303b7718f1190b1425fcae888f0c91d149b2af72c` |
| `app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryReadingAnchor.kt` | `e1e69e000f7ed4515f80313db1da40644f7adebf977f49d1a79de0459cc2f72a` |
| `app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt` | `0983b480647f134c333d918c80120dd4525c4fe8c3298f19cbd4759beea1d19e` |
| `app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt` | `360b9d0208103e4b3c7cd52dfcda084869a2b1b912d3a78681877448fe6cb463` |
| `app/src/test/kotlin/com/easysavevideo/app/discovery/DiscoveryReadingAnchorPolicyTest.kt` | `f4f73fc284c3a46bd25d257f7641f357ef33b118d6c2efb12c2ddb98ec64b40c` |
| `app/src/test/kotlin/com/easysavevideo/app/ui/DiscoveryReadingAnchorWiringTest.kt` | `8aac45607252211e570b3d008b48e6d7e8e00b517bc1ccd66500e988493de835` |
| `app/build.gradle.kts` | `fdfe0993d2275c02a12cfdd959c3fc7304d8f4543590f2602e06a20ebdf7e779` |

The policy and policy-test hashes were independently checked against `policy-inputs/manifest.json` and matched. All seven current-file hashes were re-read before this report was written.

## Defect 1: scroll cancellation could permanently stop anchor capture — resolved in source

The initial adapter performed `grid.scrollToItem(...)` and then started `snapshotFlow` in a single `LaunchedEffect`. A scroll mutation rejected or interrupted by higher-priority user input could throw `CancellationException` before capture began. With unchanged claim keys, the effect would not restart, so later scrolling could leave a stale saved anchor.

The final adapter uses `restoreDiscoveryReadingPosition`. It catches only `CancellationException`, then calls `currentCoroutineContext().ensureActive()`. An inner scroll-mutation cancellation can yield to the user and continue observing; cancellation of the parent effect propagates. Non-cancellation failures propagate. The adapter checks the current claim again before starting capture and checks it on every captured position. It does not retry the scroll or fight the user's gesture.

The two coroutine tests distinguish an inner cancellation from parent-job cancellation and verify ordinary failures propagate. These are coroutine helper tests, not actual Compose gesture tests.

Reference inspected for the API mechanism: official AndroidX [LazyGridState](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/grid/LazyGridState.kt) and [MutatorMutex](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/MutatorMutex.kt) source. The repository pins Compose Foundation 1.8.3; reading upstream source was not execution or exact-binary UI verification.

## Defect 2: real controller recreation changes epoch — resolved with bounded display provenance

The initial key compared the full `SessionStamp`, including epoch. `MainActivity.onCreate` creates a new `AndroidDiscoveryController`; its existing initialize path invokes `VideoDiscoveryCoordinator.restore`. Restore increments epoch and restamps retained pages. Therefore a saved same-page/query/revision anchor would have reset to the top on real controller recreation, despite passing a same-key codec round trip.

The approved correction adds read-only, in-memory restore provenance to the existing controller path:

- Clear the proof at initialize entry and guarded failure.
- Create it only after the existing coordinator restore succeeds.
- The factory requires the same profile, authorization, source configuration and retained pool; only consistent forward restamping is accepted. Bounded, internally consistent result/page membership is checked.
- Publish the previous stamp only while the current checkpoint and displayed page match the exact proof target, the page belongs to the current pool, and storage is not unavailable.
- Resolve an older anchor only when its stamp equals that exact previous stamp and every other page/query/revision/demo identity field still matches. The selected stable result ID must exist.
- Keep live restoration claims strict on the complete current key. Previous-epoch callbacks do not gain authority.
- Normal capture writes an anchor using the current key. The saved codec remains version 1 with the same nine primitive fields; provenance itself is not persisted.

Policy tests include the real network-free coordinator `restore` path, same-query `start`, changed-source/explicit-recovery reset, malformed checkpoints, later epochs, identity mismatch and unobserved restoration chains. These test definitions were reviewed before execution. Their completed build evidence is verified in the final addendum.

## Composition, persistence and safety review

- `rememberSaveable` stores one bounded reading anchor, while `rememberLazyGridState` remains attached to the actual result grid.
- The saved anchor contains page/session identity, the applied query hash, demo boundary, stable result ID and pixel offset. It does not store editable/raw query text, URLs, titles, metadata or the result list. Diagnostic strings redact identity values.
- Codec decoding rejects wrong field count/version, wrong primitive types, malformed identities and negative numeric fields without coercion. Capture/resolve reject invalid or duplicate rendered result IDs and invalid indices.
- Root navigation uses the existing NavHost save-state/restore-state path. That supports the intended scope by source inspection, but actual navigation restoration has not been exercised here.
- Claims are recreated when page identity, result order, visibility or provenance changes. Disposal retires the old claim.
- The callback reads the live controller state, rather than only a captured compositional page snapshot. Null pages and current-key mismatches cannot replace the saved anchor.
- Capture starts only after the restoration attempt has completed or yielded to user input; it cannot overwrite the saved anchor with the initial pre-restoration viewport.
- Visibility gating covers foreground activity, content body, pending-results mode, modal options, local control confirmation, safety confirmation and rights details.
- Stable-ID resolution handles a same-identity reorder. Missing selected IDs conservatively reset to the top; genuine page/query/revision changes do so as well.
- The adapter/helper adds no search, page demand, history write, store write, provider request, timer or background scheduling command.

## Independent scope-preservation checks

1. Reverse-transforming the six documented controller insertions produced bytes identical to the saved before-image. The original controller hash is `625fc0c497eb8801116123df1285321a239c6f4868378de90db48284c4563bd8`.
2. Replacing the sole new `PreserveDiscoveryReadingAnchor` call with the original unconditional scroll effect produced bytes identical to the saved DiscoveryScreen before-image.
3. Thus the screen's existing shown-visibility tracker, history calls, paging, pending-demand and control logic were not otherwise edited. The controller's prior restore, store, coordinator and scheduling statements remain unchanged; the inserted code projects display proof only.
4. Build contract inputs include the new policy and adapter source. Existing screen/controller source inputs remain covered.

## Validation boundaries

- Source review and the two byte-for-byte scope checks: **COMPLETE**.
- New test definitions reviewed: **41 policy tests + 7 wiring/coroutine tests**.
- Build1, complete app unit tests, debug/release compilation and lint: **PASSED BY BUILD OWNER; EVIDENCE INDEPENDENTLY VERIFIED** as recorded below.
- Actual Compose navigation, saved-state restoration, user-gesture cancellation and pixel/offset behavior: **NOT_RUN**.
- Emulator/device installation or testing of this slice: **NOT_RUN**.
- An older frozen APK is not evidence for this new slice.
- Repeated controller recreations while the saved root never becomes visible may leave an anchor older than the direct previous-stamp proof. The policy intentionally does not infer an epoch range or restoration chain and will reset to the top in that case. This is an explicit conservative limitation, not claimed full lifecycle coverage.

## Final build/evidence addendum

Independently checked on 2026-10-13 after build1 completed. **No remaining source or build-evidence blocker.** This reviewer read and validated the resulting files; the reviewer did not rerun Gradle or device work.

### Executed build and tests

- Build owner's terminal record reports exit code 0. The retained complete log independently confirms `BUILD SUCCESSFUL in 3m 1s`, with 75 actionable tasks: 20 executed and 55 up-to-date.
- All ten explicitly requested tasks have execution lines without cached, up-to-date, skipped or no-source suffixes: `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:lintAnalyzeDebug`, `:app:lintReportDebug`, `:app:lintDebug`, `:app:lintAnalyzeRelease`, `:app:lintReportRelease`, `:app:lintRelease`, and `:app:verifyRuntimeAdmission`.
- Independently parsed all 218 App JUnit XML files: **1,953 tests, 0 failures, 0 errors, 0 skips**. Each suite's declared count also equals its actual testcase element count.
- The policy suite contains **41 passed tests**, timestamp `2026-10-13T09:38:17.572Z`; the wiring/coroutine suite contains **7 passed tests**, timestamp `2026-10-13T09:38:20.963Z`. The new total is **48**, included within 1,953.
- Both lint XMLs independently contain **60 Warning, 8 Hint, 0 Error** issues. Lint passed; warnings/hints are not represented as absent.
- The build log explicitly records exact admission of **90 reviewed runtime artifacts for both variants**.
- The retained `boundaries.json` contains seven PASS entries, including their existing scoped/device limitations. `VERIFICATION.json` records the build owner's subsequent boundary-script exit code as 0. The reviewer checked this evidence and the script's frozen hash, but did not independently rerun that script.
- Other modules' test suites were not rerun by this slice. No broader all-module test result is inferred from the App suite.

### Input and archive integrity

- Rehashed and size-checked all **1,403** current source inputs against the frozen manifest: **all match; no changed-during-build input found**.
- Independently checked **1,396** baseline inputs outside the three modified existing files: **all match their baseline hashes**. Four new files and three modified files comprise the seven-file slice.
- The artifact and scratch input manifests are byte-identical. Manifest SHA-256: `98f91955c05f5923cea6e9ac7daeb9809caed8c7985fe1125bb60292a3db37ec`.
- `COMPLETE_APP_EVIDENCE.zip` contains **222 unique entries: 221 payloads plus MANIFEST.json**. The manifest names exactly the payload set. Every payload's SHA-256 and byte count matches; ZIP CRC validation passes.
- Its complete JUnit file set matches the raw build result set. Every archived suite is semantically identical to its raw build XML after excluding only hostname redaction and XML formatting/serialization differences. No count, testcase, failure, skip, timestamp or result alteration was found. The two standalone new-suite XMLs are byte-identical to their archived equivalents.
- Both archived lint XMLs are byte-identical to the raw reports after replacing only the local source-root path with `${SOURCE}`.
- The archived build log is byte-identical to the retained evidence log. It is also byte-identical to the raw scratch log after replacing only the local source-root/home paths with `${SOURCE}`/`${HOME}`.

Evidence identity checked:

| Item | SHA-256 |
| --- | --- |
| Complete evidence ZIP | `fd508436a1b8dbb0eac5b43543a7371eafa98974d16f497b0e8b1e9abe1787b3` |
| Sanitized build1 log | `5c3e90df70745b3d3f59f30baa82d1e9eaded1ea0cae441af3e0e21e18540a28` |
| Implementation report `docs/m11a/DISCOVERY_READING_ANCHOR_20261013.md` | `e4423a4731e99517504f2fe6ea88d6e727139b95124165b6b01400650ce961e9` |
| Unchanged boundary script | `538ff9070478862a09bf59ae09494a9ddeefd2a9df0fe59416afa21c9cf111ff` |

### Report and acceptance boundary

The implementation report was read in full. Its counts, input freeze, bounded provenance semantics and disclosure that other module tests were not rerun agree with the checked evidence. It explicitly preserves the distinction between source/JVM evidence and actual Android/Compose behavior.

**Actual Android/Compose restoration, drag delivery, navigation/lifecycle behavior, emulator/device tests, 200% text/window/RTL/TalkBack checks and PPT/pixel comparison remain NOT_RUN. No APK was assembled or installed for this slice.** The prior code8 APK belongs to the pre-anchor c794ef source and cannot be claimed to include this feature. The conservative repeated-hidden-recreation limitation and prior unrelated acceptance failures remain open.

Final verdict: **PASS for this bounded source/JVM/build-evidence slice; device/UI acceptance remains pending.** This review does not authorize or establish publication, deployment or a release.

## Ledger refresh addendum

Additional authorized read-only review, 2026-10-13, covering the minimal factual refresh of two existing status documents. **No factual or stage-boundary blocker found.** The reviewer did not edit either ledger, change production inputs, rerun Gradle, or perform APK/device work.

| Refreshed ledger | SHA-256 |
| --- | --- |
| `docs/m11a/MILESTONE_STATUS.md` | `62b0a4a4bc9ea772bea0e9773ef80ee4a481154fa2dab47be54876862552145f` |
| `docs/m11a/PROJECT_COMPLETION_EVIDENCE_2026-10-13.md` | `1355cbfd0f412e4b5eaf2f38be2bf88b79b58aee1cfa418ca0fdc3ded78e7331` |

Both documents were read in full and their current changes compared with `before-docs` snapshots, rather than relying solely on the earlier generated diff. Final table/indentation cleanup and three subsequent MILESTONE phase clarifications were reviewed. Those clarifications explicitly bind host gates and Theme's full regression to b0c5647 and state that other module tests did not rerun in the notice slice; they do not alter the counts or acceptance states.

Checked factual boundaries:

1. **Published Save notice stage.** The retained receipt identifies c794ef84344d638f20966a1cd05bb5b340ade7e0, parent ef5d6fc35328e8881db26df3982c0e675c37dd4d, on development/cloud-followup-20261013. It records 32 exact changed-blob readbacks, no deletion/forced update, unchanged main, private visibility and no workflows/runs/checks/status entries. The ledgers correctly record **1,905 App tests / 19 new tests** and the notice channel as published, without making it remaining implementation work. Service notifications, other owners' text and whole-app localization remain outside that completed scope.
2. **Current reading-anchor stage.** **1,953 App tests / 48 new tests** and the build results match the independently verified evidence above. The ledgers correctly mark this stage and their own refresh as awaiting publication at this checkpoint. Actual Compose/device restoration remains NOT_RUN. Other module tests were not rerun.
3. **Historical full regression.** The full-regression receipt and VERIFICATION record bind **13 modules / 3,149 registered / 3,147 passed / 2 skipped**, App 1,886 and Theme 17, to b0c5647. The ledgers neither apply this full regression to later notice/anchor inputs nor add overlapping totals. The old host/admission/SQLite limits and unavailable owned-corpus cases remain explicit.
4. **Separate local APK.** Packaging evidence binds code8 / 0.8.0-ui-dev, min31/target36, **45,988,354 bytes**, to c794ef inputs before anchor work. Existing matching test-signature reuse, local input equality, and the single pre-existing metadata POM CRLF/LF caveat agree with the packaging report. Local-only/no-upload/no-install/not-release status, unchanged global cleartext allowance, and the distinction from the older OPPO APK remain intact. No claim that this APK contains the anchor slice was found.
5. **Remaining acceptance.** Outstanding OPPO edit/share and historical Windows issues, missing PPT imagery, unexecuted native acceptance, incomplete OpenOriginForLogin and deferred product gates remain open. Published Save runtime notice work and the already source-tested anchor are not relisted as new coding targets.
6. **Publication planning.** The ledgers correctly describe a pending combined publication of their two modifications, anchor evidence and small packaging evidence, excluding the approximately 46 MB APK. Final union-whitelist verification and remote byte readback remain the publication owner's subsequent work; this review does not claim publication has occurred.

Cross-check sources read: the two latest publication SUMMARY receipts; the Save runtime notice report and receipt validation; the full-regression VERIFICATION; the current reading-anchor evidence/report; and DEBUG_CANDIDATE_STATIC_ACCEPTANCE_20261013.md with packaging VERIFICATION. Earlier history remains dated context. This added review is not a new remote repository probe or a new historical test run.

All **1,403 frozen production/test/build inputs still matched** immediately before this addendum was written. The two ledger documents are post-build documentation outputs, not retroactive additions to the executed build's input manifest. The original seven-file source/build review remains unchanged.
