# Independent source review: batch export recovery

Verdict: PASS_SOURCE_REVIEW. No correctness blocker found in the approved A/B change. This is permission to proceed to serialized validation, not a build, device, publication, or product-acceptance PASS.

## Exact reviewed inputs
- INPUT_MANIFEST.json SHA256: `70396c4ce19d5248566acc23c1617205080468411e5a31b567a708e7bba80a40`
- IMPLEMENTATION_DIFF.patch SHA256: `c5f4ad0bd30a1173db10a12abd8f42a2d9a4646e0966437b2f03f6e97de60a45`
- All 1435 manifest inputs independently match current SHA256 and byte size. Five declared modifications and five additions are the only predecessor changes; 1425 predecessor inputs remain unchanged. Original copies for all five modifications match their recorded before hashes.
- Detailed ten-path hashes and individual checks: INDEPENDENT_SOURCE_CHECKS.json.
- Direct predecessor is the tested Editor Back freeze, manifest `c425c1e0a4ef58073e5366a77dd8e11e1bc1f4b109f3c165c72d64fb5feb4e09`. Its verified publication head is still required. Historical 24bc is not this slice's direct publication parent.

## A: exact once-only directory consent
AndroidExportRuntime creates one opaque token per DirectorySelection (lines 83–86). LibraryExportStatus captures that exact token for enablement and onClick (51–54). consumeDirectoryBatchConfirmation rejects a wrong identity or non-pending lifecycle before evaluating current proof (17–21). IGNORED returns without cancellation, notice mutation, or worker submission; only a still-pending matching request with failed proof retains STALE/cancel (runtime 355–365). Lifecycle is consumed before submission. Thus repeated accepted callbacks and old callbacks after cancellation/reopen cannot adopt or cancel another selection.

The picker continues to launch once per admitted batch and grants/copying still wait for explicit confirmation. Source/foreground proof is not loosened. Single-file export and share paths are untouched. Terminal submit rejection clears selection, gate and busy UI as before, and now clears the token to null (558–560). That branch is statically reviewed, not runtime-executed here.

## B: actual route-exit ownership
MainActivity removes cancellation from navigation intent (navigate) and dispatches it from OnDestinationChangedListener (203–208). The production predicate calls the existing cancelBatchForLeavingLibrary only for previous LIBRARY and current non-LIBRARY. A successful Back pop and a successful fallback both produce the route event; failed/no-op pop without an exit, same-Library navigation and local dismissal do not. Picker Activity pause is not a route event; the existing expected-picker-pause exception remains unchanged.

Initialization uses the Activity's initial/restored destination until the first observed route. Cold start has no admitted batch; recreated picker ownership is already retired by existing old-host/background paths. The cancellation callback does not synchronously navigate, so updating activeDestination after it is not a present reentrant defect. Do not turn this limited finding into a guarantee for arbitrary future callbacks.

## Cancellation, results and durability
DirectoryBatchCopy, BatchGalleryCopy, export journal/schema and recovery code are byte-unchanged. The worker retains captured per-attempt cancellation and exact DirectorySelection identity guards on both success and failure delivery. Partial completed rows remain completed; not-started rows are stopped rather than replayed. No rollback, per-item chooser, implicit grant recovery or auto-resume was introduced. Source contracts bind these unchanged paths; real Android executor/SAF behavior remains a separate gate.

## Test depth and boundary
- 23 new source test methods: confirmation helper/owner model 9; route policy/model with DirectoryBatchCopy and BatchGalleryCopy calls 8; source wiring contracts 6.
- Existing FolderPickerResultLifecycleTest has 8 updated methods and directly invokes the production picker bridge/lifecycle plus the production confirmation helper. These are existing tests, not eight new tests.
- The changed identity/phase/proof decisions execute production code. Partial-result tests execute the production copy engines against a fake journal.
- Busy/rejection/completion and route-event harnesses model host behavior. Source contracts inspect Android wiring. They do not execute AndroidExportRuntime, actual NavController, Compose callbacks, host detachment or real SAF providers. Comments and test names now identify these limitations.
- No Gradle/JVM/build/device command was run by this reviewer. Test method count is inventory, not a passing execution count. Parent owns forced unit tests, Debug/Release compilation/lint, final input rechecks and evidence review.

## Exclusions and remaining gates
Page-2+ picker-return refresh and same-item delete confirmation ABA remain unchanged. Android lifecycle ordering, rotation/process recreation, real providers/grants, copied-media integrity, visual/accessibility acceptance and all prior device issues remain NOT_RUN/open as applicable. Publication remains gated on verified predecessor head and complete exact-byte validation evidence.

## Final build and evidence review

PASS_SOURCE_JVM_EVIDENCE_ONLY_PENDING_PUBLICATION. Independent evidence inspection found no blocking mismatch. No reviewer build was run.

- Rechecked all 1,435 pinned source hashes after build. Input manifest remains `70396c4ce19d5248566acc23c1617205080468411e5a31b567a708e7bba80a40`.
- Complete archive SHA256 `46a66cfcb9c0844aeb69eda4e86a59502c9f49418c263e572270839396ddc141`: 237 unique entries, ZIP integrity valid, every one of the 236 payload hashes/sizes matches the internal manifest. Exactly 233 current App XML suites, both lint XML reports and build log are present.
- Recounted every testcase and suite: 2,100 tests, zero failures/errors/skips. Compared exact testcase identities against the manifest-bound Editor Back archive: 23 added, none removed; new suites 9/8/6. Existing picker suite remains 8 updated tests, not new. All suite timestamps fall after source pin and before evidence recording (2026-10-13T12:37:00.378000+00:00 to 2026-10-13T12:37:08.336000+00:00).
- All archive JUnit bytes match current raw XML after disclosed hostname/path redaction; lint and build log match disclosed path substitution. This is verified sanitized correspondence, not raw XML byte equality.
- All ten forced tasks are present as executed, not UP-TO-DATE/FROM-CACHE: App unit, Debug/Release Kotlin compilation, both lint analysis/report/aggregate triplets, and exact runtime admission. Build log says successful in 3m36s; 75 actionable tasks, 20 executed, 55 up-to-date. Build owner observed terminal exit 0.
- Each lint variant has 60 warnings and 8 hints, zero errors/fatals; independently compared issue ID/severity/message multisets with the frozen Editor Back archive and found them equal. Both raw report mtimes are fresh in the pinned build interval. Runtime log confirms exact 90 reviewed artifacts; seven fresh boundary result outputs match their archived copies.
- Report `docs/m11a/LIBRARY_BATCH_RECOVERY_20261013.md` matches these results and explicitly preserves App-only scope, model/source-contract limits, no APK/device run, excluded page-2/deletion ABA, and pending direct predecessor publication SHA.
- SCOPED_SOURCE_DIFF.patch is modified-file hunks only. The independent source review covered the full ten-file implementation; include all five new source/test files in the final source package.

Detailed verification and evidence SHA256 list are in INDEPENDENT_EVIDENCE_CHECKS.json. This review does not approve Android/SAF behavior or a publication lineage that bypasses the tested Editor Back predecessor.
