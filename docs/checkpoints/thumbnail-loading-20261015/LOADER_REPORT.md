# Discovery thumbnail loading: implementation and evidence

Use **revision-04**, the immutable final functional overlay. Earlier revisions and root working-copy base/overlay are historical, not merge targets.

- Functional manifest: `revision-04/MANIFEST.json`, SHA-256 `db6fc2161d128ec31ac1ce0b80a7787874c3109a14bd50619132f6da191121b4`
- Delta from revision 03: `revision-04/DELTA_FROM_REVISION_03.json`, SHA-256 `86996f353b42db6e6d8870e34cdb01e7c5e6b296bbd8f04fe647eebbfcb411d8`
- Central combined 151-file source/dependency/build-input manifest: `__WORKSPACE__/esv-thumbnail-loading-checkpoint-20261015/FINAL_SOURCE_MANIFEST.json`, SHA-256 `90999cf2f91302cdf474d3e2397b23719103cc1a512a8a13562f165534188464`

## Result

The production route connects the trusted registered Commons result through anonymous bounded OkHttp, platform bounds-only inspection and Coil 3.3 core decode to actual Compose Image card drawing code. Existing Capability/Binding/Session and DiscoveryCancellation are reused. A fixed process lane, actual worker/hook drain, independent completion Handler, exact published generation and pending-versus-applied intent checks prevent old page/configuration work from obtaining a new read epoch.

Scope: 9 production/resource files, 7 new test files with **54 new tests**, and 2 narrowly revised existing source/resource tests. The central dependency owner separately admitted the Coil/OkHttp graph and owns Gradle/locks/licenses. This functional overlay changes none of those files. Its three source-contract input additions are listed in revision-04/BUILD_INPUTS.md.

## Actual central validation chain

These counts were independently recounted from the frozen XML, not inferred from test source:

- First compile exposed OkHttp Dns Kotlin-interface construction, fixed in revision 02.
- Revision 02: both Kotlin variants compiled; 63 focused tests, zero failures/errors/skips. First full run later exposed exactly two old source/resource expectations.
- Revision 03: both variants compiled; 83 focused tests, zero failures/errors/skips, including the two revised existing contracts. Fixes covered unconfirmed-release hook drain, exact published generation, synchronous intent withdrawal and settings deduplication.
- Revision 04: both variants compiled; 86 focused tests across 10 suites, zero failures/errors/skips. The 86 consist of 54 new tests, 19 existing thumbnail tests and 13 existing source/resource tests. Discovery was UP-TO-DATE with unchanged revision-03 test/source bytes in this fourth focused invocation; app tests executed. The final full run separately forces all 13 test tasks and both lint variants.

**Final forced full run passed:** 515 suites, 5,692 test items = **5,690 passed + 2 skipped**, zero failures/errors; exit 0, BUILD SUCCESSFUL (4m17s). Both Kotlin variants compiled in the focused chain and the final build confirmed unchanged outputs. Both lint variants completed with zero errors: debug 77 warnings/10 hints, release 78 warnings/10 hints.

All 18 functional central source/test/resource postimages independently match the immutable revision-04 manifest (`FINAL_SOURCE_MATCH.json`). Frozen final evidence is in `full-final-xml`, `full-final-lint`, `FINAL_TOTALS.json` and matching log/exit files under the central checkpoint directory. Earlier frozen evidence is in `__WORKSPACE__/esv-thumbnail-loading-checkpoint-20261015/{focused-second-xml,focused-third-xml,focused-fourth-xml}` and matching logs/exit files. Failed earlier logs remain preserved.

## Fixes and exact evidence limits

Revision 04 closes the later-discovered refresh race: refresh captures generation before page/store work; pending command intent publishes -1; each original serialized command acknowledges only its own token; same-valued settings cannot acknowledge another command. Only a subsequent current snapshot can authorize a read. The two added publication cases are one real-thread/latch test and one deterministic sequential test, both using actual Capability/Session. They do not instantiate AndroidDiscoveryController.

Storage review verified the final 18/18 functional postimages and four-file final delta. Normalization review accepted the actual ByteArrayFetcher→BitmapFactoryDecoder route and bounds/output policy. See IMPLEMENTATION_DECISIONS.md for source/privacy/cache/resource details.

No Android pixel decode, Handler/Compose runtime, real HTTP/TLS interoperability, platform-close or native/GPU peak measurement is claimed. Host tests cover actual body policy, actual anonymous client configuration without requests, process-lane behavior, real cancellation hooks and Capability/Session, plus production source-wiring assertions. Independent Handler refusal is bounded fail-stop and may retain one bounded frame; it does not certify release. Platform-recovered/static first-frame output is accepted display-only, without custom parsing or reuse/download authority.

## Lint findings retained without suppression

Lint reports StaticFieldLeak at the process singleton's CommonsThumbnailReader reference. This retention is intentionally process-scoped to preserve the one transport/decode lane and cooldown; the producer, process reader factory and reader normalize the supplied Context to applicationContext. No Activity, View, Compose scope or controller is stored in that reader. This source review explains the intended lifetime; it is not a heap-leak measurement or claim that lint is globally clean. Two new AutoboxingStateCreation hints recommend mutableLongStateOf for the adapter's revision/visible-generation counters. They are retained as non-blocking optimization hints; no warning suppression or unrelated refactor was added.
