# Independent review: Editor destination acknowledgement

Date: 2026-10-13 UTC
Final result: PASS for source, JVM/build evidence and documentation. No remaining review blockers; publication remains pending.

## Binding

The eleven reviewed production, test and build inputs are bound by INDEPENDENT_SOURCE_CHECKS.json in this directory. Six modified before snapshots match the baseline input manifest exactly; all other 1,411 existing baseline input hashes remain unchanged. The published baseline is 1f25214fcea79113b0278ac24ddc316e6910a9f0.

## Source findings

- Receipt callbacks are both consumed before invocation. Only Accepted invokes creation, then terminal executes in finally, including accepted callback exceptions. Throwing terminal callbacks remain consumed. Exceptions from source-current fences become Failed; current-owner identity is rechecked after predicates to fence reentrancy.
- Destination completion consumes its exact ticket and Pending identity, clears asking/admitting/pending and writes notice before external callbacks. Late admitted success, storage failure, queue rejection, stale cancellation, and a reentrant replacement cannot clear or overwrite newer ownership. ASK with no remembered folder remains recoverable and pending; an automatic invalid folder selection rejects.
- Editor submission begins an identity-based attempt before validation. Only its matching terminal can advance revision and clear the runtime latch. Duplicate submissions cannot replace the attempt, and synchronous rejection cannot install a stale returned receipt. Owner-specific editor cancellation cannot cancel a later Save or recording selection.
- Runtime submissionPending defaults false and is propagated through copies and editability. The screen combines it with the existing composition latch, preserving suppression across recreation. Native task phases continue locking controls after accepted admission finishes.
- Background and explicit cancel retire only the editor receipt. Opening a replacement draft retires its previous attempt; draft controls remain disabled while destination selection waits. Draft restoration counts a pending attempt as an existing owner and cannot replace it.
- During review, the first partial patch blocked accepted restored-draft metadata probing. The final patch corrects this with the sole explicit inspectMissingSources(selectionAccepted = true) call in submitSelected's missing-geometry branch. Ordinary lifecycle probing stays blocked during selection; cancellation/rejection/failure do not start new probing. The source contract covers both paths.
- Existing public select(isCurrent, accepted) remains compatible with both Save and all three recording call sites, whose bytes are unchanged. Destination gate, durable admission/storage, media workflow, recording, export routing, worker lanes and main-thread dispatch remain unchanged outside terminal delivery. There are no dependency, wire/schema, permissions, logging or editor-return-confirmation changes.
- The 24 new tests use real pure receipt/attempt/gate/click-state classes (15 tests) plus real source-file contracts (9 tests), not mocked Android runtime. Build inputs add the two newly read source files, with all other contract targets already registered. Source contracts supplement, rather than establish, Android execution.

## Review limitations

The reviewer performed read-only source inspection and hashing; no Gradle, APK, installation, emulator, Compose, native-media or provider execution. The final build-owner evidence and three documents were subsequently checked below.

## First build and narrowly reviewed test correction

The first build registered all 2,050 App tests and reported one failure in the pre-existing OrdinaryDestinationPresentationTest assertion that required exactly 26 textual enum references. Production still uses exactly the same 26 localized kinds; the new source-failure and missing-folder outcome branches reuse LOCATION_UNAVAILABLE three times and REMEMBERED_FOLDER_REQUIRED twice, producing 29 total references. The sole post-build1 source/test change replaces that obsolete occurrence assertion with the exact duplicate-frequency map. Exhaustive enum-set equality, four null resets, resource parity, safety text, current-Composition resolution and no raw-text contracts are unchanged. Existing seven test methods and all production hashes are preserved. The final pin now contains 11 changed inputs and 1,422 total inputs. The complete first 226-suite JUnit archive, log, census and input manifest are retained. The final full rerun passed as recorded below.

## Final build/evidence

PASS. The independent check reparsed all 226 final JUnit suites: 2,050 tests, zero failures/errors/skips. All 24 new cases passed (8 receipt/source-check, 7 attempt/view-state, 9 source-wiring); the corrected existing seven-case presentation suite also passed. The 31 cases in changed test classes are not misreported as 31 new tests. Every final suite timestamp follows the final input pin, and suite facts/test identities match the fresh local Gradle XML.

All ten requested task lines are EXECUTED, including App JVM, both Kotlin compilers, both lint analyses/reports/entry tasks and runtime admission. The terminal log says BUILD SUCCESSFUL in 3m10s, 75 actionable tasks (17 executed, 58 dependency tasks up-to-date). Both variants verify the exact 90-artifact runtime; the unchanged compiled boundary checker reports seven passes. Each lint has 60 warnings, 8 hints, zero errors; the complete issue-ID/severity/message multiset equals the published notification baseline for each variant.

All 1,422 final pinned input hashes match after the build, and the eleven changed hashes equal the final reviewed set. Only the documented existing-test assertion differs from build1; no production hash changed between attempts. The final complete evidence ZIP has 231 entries (230 independently hashed payloads), including 226 JUnit XMLs, two lint XMLs and both build logs. The first-run ZIP has 227 entries (226 independently hashed JUnit payloads) and preserves the single expected-26/actual-29 assertion failure. Both CRC checks and every payload length/SHA-256 pass. JUnit XML is deliberately hostname-redacted, reserialized and path-sanitized; this is not an unredacted raw-byte equality claim.

The final three document hashes are bound in INDEPENDENT_EVIDENCE_CHECKS.json. Their current editor result is correctly separated from the earlier published anchor/authentication/notification chain, the b0c5647 thirteen-module full regression, and the unchanged local pre-anchor APK. First failure/correction history, historical gate failures, OPPO edit/share and ANR/second-recording issues, and Windows limitations remain visible. The slice does not claim device, Compose interaction, native editing, provider, accessibility, PPT or release acceptance. Publication is still pending and was not performed by the reviewer.
