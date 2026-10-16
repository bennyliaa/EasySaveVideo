# Independent thumbnail platform/UI source review

2026-10-16. Scope: the shared native thumbnail/backend bridge, typed asynchronous session, protected Host, measured viewport and private drawing UI. Review-only; no source edits, builds or device operations were performed by this reviewer.

## Verdict

The reviewed B and C source has no unresolved source blocker. The corrected integrated focused run passed, including both actual Debug/Release Kotlin compiles and all 58 new B/C tests. Final full regression and both lint variants also passed. Runtime/storage ownership is separately reviewed. This closes the platform/UI source and JVM/lint scope; it is not Android/native/privacy acceptance.

Production remains disabled. Actual MediaMetadataRetriever decoding, Android MediaDataSource behavior, rendering/accessibility, screenshots, capture privacy and native/GPU release remain NOT_RUN.

## Exact inputs

- Baseline: `esv-runtime-copy-20261015/auto-lock-build-source`.
- Approved design: `work/vault-private-thumbnail-design/DESIGN.md`, SHA-256 `e2d9ba75bfeccd7d7f02d98e13739625ae869ec6f0f3d2eecba5ffe44cc82e8e`.
- Test plan SHA-256: `9a9c5a55b424bae1493a589a971ed1e00171653c1e4eb2002496685c8f920cb1`.
- Pinned Android36 API evidence: `work/vault-private-thumbnail-design/PINNED_ANDROID36_APIS.txt`, SHA-256 `8975faacd796c85fec0bfabdf1f7fd69cc57c1200d65ab0bd301619849955dfb`.
- Consumed final additive contract: `VaultThumbnailContracts.kt`, SHA-256 `c6f73ee4e81b8b3e0a60e04a35225e7ad301545d2131e28e93cf5988922c0b6a`. It adds explicit retained-owner `start()` and positive native-release observation to the earlier draft. B implements these; C consumes the unchanged token/frame/completion seams.
- B manifest: `work/vault-thumbnail-backend/manifest.json`, SHA-256 `1a45eafe01a828fdd52aeda60fd8fd48930cfe93009b2f928cb3f9feb856f58a`; nine files. Patch SHA-256 `6b67a3ace970e9e36ac372f3e966ddfce784de240b9ea84f4027d7e757ee09eb`.
- C manifest: `work/vault-thumbnail-host/MANIFEST.json`, SHA-256 `d30f83c61f96970a18009779b4c1f2ba597e02b7fa10dd1fbfc46a68e99c8459`; nine files. Patch SHA-256 `93df67867e75886ea6bcf2dfc73e61442cfd66e694b8a0a6cabccbc6b20a73c3`.

Every B/C manifest target and every listed existing-file baseline hash was independently rehashed and matched. No Gradle build inputs or dependencies changed in either overlay.

## Source findings and resolved revisions

1. Ordinary extraction originally lost the decoded-frame owner if native release succeeded but later input/catalog-fence close failed. `NativeOwner` now independently retains the extraction through that failure. Ordinary FD/catalog fencing and the original process native gate/lane remain shared, without exposing private input as an ordinary reference.
2. Shared retrieval now strongly retains the returned raw Bitmap before fallible validation, recycling or wrapper construction. Failed recycling is at most once and remains unknown. Actual ARGB rejection is private-only, preserving ordinary acceptance behavior.
3. The typed asynchronous session retains the exact active job until frame adoption/discard and successful consumer settlement return. Settlement exceptions, uncertain submissions/posts and unknown late completions retain ownership. Duplicate callbacks carrying the already-adopted frame do not close it; a distinct orphan can retire only itself. Reentrant observers cannot start a replacement before settlement.
4. The Android bridge uses the existing one-frame MMR API and authenticated MediaDataSource only. Range checks avoid signed addition, positive reads are capped at 64 KiB and the remaining operation budget, valid EOF is distinguished from failure, and errors carry a fixed generic code. Transport close is not native or controlled-reader release.
5. Native admission belongs to A's once-only counted Main handoff after prepared-owner registration. B introduces no second Handler, worker or scheduler. Existing recording/save/editor paths also atomically claim the shared gate; the old Main-thread comment alone is not evidence of a double-native-owner race.
6. C uses actual measured `visibleItemsInfo.contentType` markers intersected by object identity with the current catalog. Stale same-position layouts cannot authorize replacement references. Visible demand is capped at 16, list state is non-saveable, and private references are not lazy/saveable keys. Composition alone requests no decode.
7. Host loss clears atomic authority before queued observations; existing secure-root barriers precede stopping callbacks on cover/departure/detach. Main posts and timer enqueue acceptance are checked. The original Host remains strongly retained while actual jobs, handoffs, frames or unknown cleanup survive.
8. Observed exact cover/root detach retires visual markers before aggregate host-resource and cover receipts. This avoids the cleanup cycle without treating Compose disposal, offscreen cancellation or dropped app references as GPU erasure. Successful offscreen close releases app-owned bitmap budget while retaining only bounded visual markers.
9. Chosen playback/copy/reconciliation operations first stop thumbnails and refuse while work remains. Lock is independent of thumbnail completion. Fresh sessions require a new covered generation plus actual old retirement; stop/start and scrolling cannot reset the attempt ledger. Capture conflict refuses Vault without stopping ordinary continuous recording.
10. EN/zh-CN resources provide the same eight generic labels. Drawing receives an opaque registered handle, checks its display token and uses the existing safe Android frame draw path. No Bitmap/ImageBitmap, private-name thumbnail semantics, plaintext cache, new dependency or production entry was added.

One A-side timing observation was separately sent to its author: a queued Main start suppressed only by an invalid Host token should call `revoke(entry)`, rather than `requestPlayerRevoke(entry)` while `entry.revoked` can still be false. The existing authority check already prevents reading/drawing, but the latter waits for the watchdog to retire a never-started owner. This was resolved in the separate `work/vault-thumbnail-runtime-followup/DELTA_MANIFEST.json`, SHA-256 `92c3b28be642c4acac70d8d093636475c4c228301979eecf22abea6380d6a8e6`. Its two target/base hashes match; the production delta is exactly the one-line else branch. Its test fake accurately models never-started revocation and adds the queued Host-loss case (zero native starts, reader/file each close once, immediate cleanup without watchdog). The associated isolated log reports 83 passing tests, SHA-256 `65bb515cfd88a7a4941102852c5f81c13a0a0a2104c00ae4b2a93004094b4ef0`. The parent will integrate it in the next build window, preserving the initial run's historical inputs.

## Evidence limits

B's actual isolated log (`isolated.log`, SHA-256 `2a82d18610286d9b2fbbb4857dbc2627f04190d4e4cb7274493405921ec89db0`) reports 78 passing JVM tests: 38 existing ordinary-thumbnail tests plus 40 new tests (11 typed async, 10 transport, 6 shared extraction, 13 platform-factory fake-port cases). The production transport/session/extraction/factory code is exercised with public fakes; the native Android decoder is not executed.

C adds 18 cases: seven actual pure viewport tests and eleven Host/UI source-contract tests. C did not execute them; integrated execution is pending. Source-string assertions are wiring checks, not Android lifecycle or rendering tests.

Central build-owner evidence and its exact merged source manifest must be appended before any broader execution verdict. No isolated or integrated JVM result establishes native/device/security completion.

## First integrated evidence, 2026-10-16

`esv-runtime-copy-20261015/evidence/vault-private-thumbnails/INITIAL_SOURCE_MANIFEST.json`, SHA-256 `35bc65f76a72a1bcc3001685e12b47d4cc92d522f7651f55c1ab31e2038bceee`, contains 26 code/test/resource/build inputs. All were rehashed against `thumbnail-build-source` and matched before follow-up integration.

Both `:app:compileDebugKotlin` and `:app:compileReleaseKotlin` actually executed successfully. The first focused task exited 1: 494 suites, 5,054 tests, 5,053 passed, one failure, no errors or skips. The only failure is the old `VaultPlaybackHostSourceContractTest` line 80, which expects the previous constructor's terminal string without the new `thumbnailFactory` parameter. This does not constitute an overall passing run.

The six new B/C XML suites independently inspected all passed: typed async 11, transport 10, factory 13, extraction 6, Host source-contract 11 and actual pure viewport 7, totaling 58. C's earlier NOT_RUN-by-author status is historical; these 18 cases now have central JVM evidence. Host source-contract tests remain static wiring evidence.

- First focused log SHA-256: `bc464fbb1c20fd531f8406862402b5f2e3da7aa197a8a9fd48f76e37077cfd15`.
- First totals SHA-256: `21d279a305629a967d482817a715cce2c4b12a2236d2d787671db5f1d8a5dc34`.

Documentation-only overlay `work/vault-thumbnail-docs/MANIFEST.json`, SHA-256 `f40e42a59fa43fd3f13a36f65ae258d5fb90db1a74b19923e1db9e8d2030741e`, was reviewed. Its single target `CURRENT_IMPLEMENTATION_STATUS.md`, SHA-256 `fc986309510136020d41e1b279394539aedc5b1970ec62a89b8610ef7a680a61`, accurately marks the connected source and preserves untested native/Android/pixel/GPU/capture boundaries, disabled production entry and ordinary continuous-recording behavior. It is suitable for integration after the corrected focused run.

The follow-up was then frozen as three files, superseding the earlier two-file manifest/log above: `DELTA_MANIFEST.json` SHA-256 `982e231d7c77b56e66cdf3945ee608bfb8653736a48197bccc2328b49d516380`, patch `c2d177cfee497af4f45013b3232d82cdaf9f6e10029c594340cf1fcf046cabd4`, isolated log `2932055ea6f1c954b95bbd905e45e5a9632b0ac4db227b4fadd3b7e1f3b698ea` (92/92 passed). All three target/base hashes match. The added test-only adaptation limits the assertion to the actual playback constructor section, verifies both section markers exist in order, and still requires the complete `presentPlayback`/`tryAcknowledgeCleanup` wiring with the additive thumbnail factory. No protection was removed or weakened. The production one-line retirement correction is unchanged. This exact follow-up is suitable for the second integrated run; it does not retroactively change first-run results.

## Corrected focused execution and final source

The second focused run exited 0, `BUILD SUCCESSFUL in 3m 45s`. Both Debug and Release Kotlin compilation actually executed again. Frozen `focused-second-xml` and `focused-second-TOTALS.json` show 494 suites and 5,055 tests, all passed, with zero failures/errors/skips. The six B/C suites remain 58/58 passed; the corrected prior Host source suite is 9/9, and the runtime thumbnail suite is 22/22. No review-worker test execution is claimed.

- Tested 27-input `SECOND_SOURCE_MANIFEST.json`: `02ae13d6482174de3f8a949040ff6b5cfe9a602353224cb87e8347603b7942a4`.
- Second focused log: `ba867b5e3e68e322ccaf90cd185510bf3f3d9aaa3cdee70946cfd2140d8da69e`.
- Second focused totals: `3360fbb3202965b44e2776ecc0c7bd150ec742cc6dab255b6ef3aab7759c02f3`.
- Final 28-input `FINAL_SOURCE_MANIFEST.json`, adding only the reviewed status document: `ed529c9e9fa9e9f8935204ba0639568a343bceb16719749c8c70a7f4084d1e4e`.

All 28 final target hashes were independently compared with the integrated source and matched. Native/device/security acceptance and production enablement remain unchanged.

## Final full execution, independently recomputed

The parent-owned final full run exited 0, `BUILD SUCCESSFUL in 8m 23s`. Independent parsing of every frozen `full-final-xml` suite yielded 591 suites, 6,563 tests, 6,561 passed, zero failures/errors and two pre-existing verification-local skips:

- `FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent`
- `FrozenCorpusHostTest.same100FrozenBodiesActualStaticB`

Both frozen lint SARIF files have zero errors, 86 warnings and 10 notes each, using each result's explicit severity or its rule's `defaultConfiguration` severity. Full-run Debug/Release compile tasks were UP-TO-DATE; both actual lint tasks executed. The preceding first and corrected focused runs establish actual compilation of both variants, rather than claiming every run recompiled them.

- `FINAL_TOTALS.json` and `full-final-TOTALS.json`: `f071bb3904eadb350639afbdf9ff7f138d32574d6c43837a7d18d4d04c470e8d`.
- `full-final.log`: `9c6883e48870ea26c738ea038d5cc6cc2c5e45d676962e8a25ccfcb005e00859`.
- Each frozen lint SARIF: `c0028a5e1bee8d50652df87e6a87743286e4038f72def36bf998e5e62d3b4a79`.
- `LOCAL_SOURCE_MANIFEST.json`: `01a7e7fefe866b2b7d57800d6194ab1ce2c184fb8d8f967ff5e092c99eb87434`.

All 2,788 local source-manifest files were independently rehashed and matched the integrated source. The final 28-input manifest remains `ed529c9e9fa9e9f8935204ba0639568a343bceb16719749c8c70a7f4084d1e4e`, with all 28 targets unchanged and matching after the full run. No test success, source manifest or lint result changes the explicitly unexecuted native/device/security gates or the disabled production entry.
