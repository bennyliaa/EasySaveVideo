# Independent authentication-origin source review

Reviewed on 2026-10-13 UTC. Scope: the approved bounded app-only original-document selection slice in `${SOURCE}`, against `PLAN.md` and the four preserved baseline files. The reviewer did not edit production or test source and did not run Gradle or device operations.

## Source-review conclusion

No remaining blocking source flaw was found in the reviewed ten-input snapshot, including the exact test-only reader correction verified at 10:21 UTC. The first build exposed a test-source API compatibility error, recorded separately below. The subsequent completed cloud build/evidence review is recorded in the final addendum; real device behavior remains outside this acceptance.

### Findings resolved during review

1. The first implementation fenced task identity and foreground generation but did not carry the browser interaction revision for stale deferred clicks. The final implementation keys the UI command by `browser.saveInspectionRevision`, rejects a mismatch as `STALE` before consuming the task command, checks the revision at host selection, and rechecks the post-`touched()` revision after `leaveCurrentTab()` callbacks. This does not bind document validity to the old capture-time tab-selection revision: switching tabs alone can still preserve an exact original document.
2. Initial availability inspection used `entries[...]` on an access-order `LinkedHashMap`, which changed the View LRU merely by rendering task guidance. The final `authenticationEntry` uses a non-touching exact-key lookup. Its document validator checks the live request context and `controller.isCurrent(request)` directly instead of calling the LRU-touching `current(entry)` helper.

### Reviewed identity and binding behavior

- Capture begins from a fresh explicit `PageSaveSelection` and the live host-accepted inspection page. Context/session, epoch and page address must agree. The host checks existing entry, active request/admission, unsuppressed status, accepted-address/View URL equality, foreground and platform availability. The pure policy additionally rejects nonready, incognito, invalid revisions and non-HTTPS documents.
- The host registry retains weak Entry/View identities and exact context/session, epoch, document revision and accepted address. Exact identity checks reject another same-URL tab, recreated entry or View, restored session, navigation/reload/history changes, and same-address ABA. A registry cannot reconstruct or resolve another registry's token.
- HTTPS origin comparison checks canonical case-insensitive host and effective port, rejects userinfo and malformed addresses, and uses the final selected resource. Direct binds its selected candidate; HLS carries a preparation claim separately and rejects a final cross-origin variant.
- The task registry binds only a newly accepted task, never overwrites an existing task association, has single-consumption capture ownership, and rejects duplicate-token binding. The current submission path uses the destination creation ID as request/task ID through the existing coordinator contract; no stored task schema was changed.
- Synchronized generation rotation rejects a late accepted worker after background invalidation. Both runtime and host clear on background. Rejection, queue rejection, preparation failure/discard, failed execution launch, cancellation, expiry and terminal/absent-task cleanup are connected. Authentication waits remain nonterminal and retain their association while otherwise valid.
- Both registries are capped at 100; issuer-clock lifetime is capped at 15 minutes. Tokens, captures, documents and registries redact diagnostic text. Runtime/UI receives no document/View reference, page path/query, title, Cookie or navigation closure.

### Reviewed action behavior

- The explicit original-page button has its own retired-on-disposal, one-shot task command. It reuses current unique task identity/revision/state/method/wait reason, coordinator snapshot compatibility, foreground generation and runtime readiness/resource-idle gates.
- Existing MainActivity Tasks-route, focused-window, foreground, dialog/picker/fullscreen/preview checks run at the click. Host capability and native-blocker checks run synchronously before selecting.
- The host operates on an exact existing entry and does not call the ordinary tab-selection path that can recreate a View from history. It pauses the old tab, rechecks after native dismissal callbacks, selects the target, resumes that entry, publishes, and verifies the selected context. An already-selected valid original tab is a successful no-op.
- Only successful selection calls the existing Browser route callback. The new origin path contains no URL load, `ensureEntry`, history reconstruction, Cookie access, task-source lookup, submit, retry, task resume or discovery authority.
- The generic Browser fallback remains separate and unchanged. English and Simplified Chinese resources use current-composition `stringResource`; unavailable and stale outcomes do not claim authentication or resume success.

### Remaining source-level observations

- Renderer replacement/death is not an eager token-invalidation hook in every path. Exact-entry/context/epoch/View validation still rejects the old token, and weak references, TTL and capacity bound retention. This was not classified as an unsafe-selection blocker.
- `AuthenticationOriginDocuments` relies on the host to supply a live admitted snapshot and serialize registry operations on main. Those obligations are fulfilled by the reviewed host callers.
- `retainNonterminal` relies on a complete nonterminal task snapshot. The reviewed `readTaskSnapshot` merges the bounded unfinished query with recent history before calling it.

## Test-source review

Reviewed all 36 `AuthenticationOriginPolicyTest` methods and all 9 `AuthenticationOriginWiringTest` methods. Coverage includes exact referential identity, context/session/epoch/document/address mismatch, same-address ABA, HTTPS/effective-port parsing, final HLS origin, weak-reference retirement, TTL/cap bounds, owner and generation isolation, late-worker background barriers, concurrent binding, terminal cleanup, interaction-revision freshness, redaction, actual submit branches, source/resource wiring and generic fallback.

Pure tests do not execute Android WebView. Source substring contracts supplement behavior tests and cannot prove platform callback or UI behavior. The reviewer did not execute these tests.

## Baseline equivalence and reviewed hashes

Every recorded production substitution reverses once to its preserved baseline file, with exact text equality for all four modified existing files. The baseline manifest contains 1,403 inputs: four approved modifications and 1,399 unchanged inputs; none was missing. The reviewed change consists of four modified inputs plus six new inputs, ten total.

The aggregate SHA-256 is computed from sorted `path<TAB>sha256<LF>` lines for these ten inputs:

`8cad4d733f41cfdb3295838bcafd6c207c7c3ea9eacbcc89aa5316969243cff7`

This is the final-input aggregate after the single test-helper correction. The initially reviewed/build-1 aggregate was `159a0d73fe4355232b6d05f962252b4e7439187f80f0779f0ef40b98ec98a31e`.

| Input | Reviewed SHA-256 |
| --- | --- |
| app/build.gradle.kts | f34afacf39c71ec38f6004f3738b0d1da6f135d4d32aa659761f1301ffe954a1 |
| app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt | c4d2b5af2ecc04453d01cfe6c2c57a863f1f5af2fd742bb846edc69093a9879d |
| app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt | 0212b3a72679d4fb17ea428922223b195cb4e76e04f964e205a5c1528729ec41 |
| app/src/main/kotlin/com/easysavevideo/app/save/AuthenticationOriginPolicy.kt | f895b764f43f40a0d7d9b223903f9072fa10f094a34a159378cafb6d63f11293 |
| app/src/main/kotlin/com/easysavevideo/app/save/SaveAuthenticationOriginGuidance.kt | 464afaa7076d72265d7f22b4be8d7a3a8166b2a69fb1927529106e279b060db9 |
| app/src/main/kotlin/com/easysavevideo/app/save/SaveTaskScreen.kt | 6a794dc9e493824a98887e016beb0817b3338b4b9b740bb594c36150467166d4 |
| app/src/main/res/values-zh-rCN/save_authentication_origin.xml | 062cff3c4c2b3aa404aad908fe84090ce15a8e4972f76adcecc0345674e87440 |
| app/src/main/res/values/save_authentication_origin.xml | a306f6051cbc6108bd65c9d22371c63305d444dcaa50c83221b29037bfda5d5a |
| app/src/test/kotlin/com/easysavevideo/app/save/AuthenticationOriginPolicyTest.kt | d627356460a906b7521a96f637dc36ef834cdd6f01654316fbea7802d715ef2a |
| app/src/test/kotlin/com/easysavevideo/app/save/AuthenticationOriginWiringTest.kt | 7e75de0dcb95d2737e0c61c3bf309ce6172ca680aea2ca3b6ab79df44feabc54 |

## Build evidence status and test-only compatibility correction

At 10:19 UTC, the reviewer inspected `build1.log` and independently confirmed `:app:compileDebugUnitTestKotlin FAILED` with `AuthenticationOriginWiringTest.kt:11:46 Unresolved reference 'readString'`. This was a test helper API compatibility defect under the project toolchain. At 10:21 UTC, the same log was terminal: `BUILD FAILED in 2m 43s`, with the unit-test compilation failure and 58 actionable tasks (21 executed, 37 up-to-date).

The parent then changed exactly one test helper from `Files.readString(Paths.get("src/main/kotlin/com/easysavevideo/app", path))` to `String(Files.readAllBytes(Paths.get("src/main/kotlin/com/easysavevideo/app", path)), Charsets.UTF_8)`. The reviewer independently verified one replacement occurrence and reversed it in memory: the reconstructed original hash was exactly `05a5430328c39b7b5ea4e6ae4f0863c838c57081d6756e7db8a402fa51f00a63`. The corrected file hash is `7e75de0dcb95d2737e0c61c3bf309ce6172ca680aea2ca3b6ab79df44feabc54`, now recorded in the table above. Comparison of `INPUT_MANIFEST.build1.json` and `INPUT_MANIFEST.json` showed this as their sole changed input. Production inputs and all test assertions are unchanged.

At 10:21 UTC, Build 2 was in progress against the corrected pin and its evidence review was pending. The completed results and independent evidence checks are recorded in the final addendum below.

## Unexecuted acceptance and boundary of conclusion

Real WebView/device acceptance remains **NOT_RUN**: original-tab retention and actual no-reload routing, touch/focus/background races, native callback ordering, renderer death, Activity/process recreation, same-URL multiple tabs, owned same-origin authentication fixture traffic, actual Cookie transmission, manual sign-in/manual resume, and cross-origin/unavailable presentation. No APK, install, account action or live-site operation was performed by the reviewer.

The implementation is the deliberately limited live, same-origin, process-local subset. It does not establish universal recovery after background/process death, login endpoint discovery, cross-origin identity-provider navigation, or authentication/resume readiness.

## Final build/evidence addendum

Completed on 2026-10-13 at 10:27 UTC. **PASS for the reviewed source and cloud build/JVM evidence within the approved bounded slice.** No blocking source or evidence issue remains. Publication and real-device acceptance are not established by this review.

The reviewer independently read the final build logs, task observations, input manifests, JUnit XML, lint XML, boundary results, complete evidence archive and final implementation report. No Gradle task was rerun and no production/test source was edited by the reviewer.

### Final build and census

- `build2.log` ends with `BUILD SUCCESSFUL in 3m 2s` and `75 actionable tasks: 17 executed, 58 up-to-date`. The parent observed process exit 0. The sanitized published log is exactly the scratch log after the documented workspace/home-path substitutions.
- All ten requested task names have actual execution lines and no cached/skipped/failed observations: App unit tests, Debug and Release Kotlin compilation, each variant's lint analysis/report/entry tasks, and runtime admission. Repeated display lines for a compilation task in the log do not change that execution status. `TASK_STATUS.json` exactly matches independently parsed log observations.
- All 220 App JUnit XML suites were inspected. Suite test totals equal the counted testcase elements: **1,998 tests, zero failures, zero errors, zero skipped**. The two new suites contain **36 + 9 = 45 tests**. Each suite has a timestamp after the final input pin, ranging from `2026-10-13T10:21:25.268Z` to `2026-10-13T10:21:32.061Z`; the final pin timestamp is `2026-10-13T10:20:04.679829+00:00`. The independently derived suite census exactly matches `JVM_CENSUS.json` and `VERIFICATION.json`.
- Both current lint XML reports contain **60 Warning, 8 Hint, and no Error/Fatal issues**. In addition to count equality, the complete `(id, severity, message)` multisets match the corresponding prior reading-anchor evidence reports for both variants. Locations were not represented as byte-identical across source changes.
- The final build log contains the runtime-admission success for **90 exact reviewed artifacts for both variants**. The seven archived boundary-check results all report PASS and match the scratch boundary result bytes. These are inspection of completed check evidence, not an independent rerun of those checks.

### Archive completeness, hashes and semantic equality

- `COMPLETE_APP_EVIDENCE.zip` is **195,732 bytes** and contains **225 unique entries**: 220 JUnit XML, two lint XML, both build logs, and one archive manifest. CRC verification passes; no absolute or parent-traversal entry exists.
- Every one of the **224 payload hashes and byte sizes** matches the archive's `MANIFEST.json`, and its payload inventory exactly covers the other archive entries.
- All 220 archived JUnit XML documents are semantically equivalent to the corresponding current build output after only the documented hostname/path redaction and insignificant serialization whitespace normalization. All two archived lint XML reports are likewise semantically equivalent to current reports after path redaction. Both separately exposed new-suite XML files are byte-identical to their archive entries.
- Both archived build logs are byte-identical to the corresponding sanitized standalone evidence logs; each standalone log was also checked against its scratch original after only the recorded path substitutions. The initial failed build remains preserved rather than being replaced by the successful run.
- The archive manifest's own SHA-256 is `971d04ac0f1fca69fb82cf34627bcf80794ad56cd8503b6a99e506006461cdd1`.

### Final frozen-source verification

- Rehashed every one of the **1,409 final pinned inputs** after the completed run and again after the documentation correction: no drift was found. The final ten changed-input aggregate remains `8cad4d733f41cfdb3295838bcafd6c207c7c3ea9eacbcc89aa5316969243cff7`.
- Repeated the exact inverse substitutions and verified UTF-8 byte equality to all four preserved baseline files. Rehashed all **1,399 untouched baseline inputs** and found no changes.
- The build-1 and final input manifests differ only in the exact `Files.readString` compatibility correction to the new wiring test already documented above. No production change occurred between attempts.

### Report correction and limits

The first evidence narrative incorrectly attributed the test helper failure to a Java API 8 target. The reviewer found explicit Java 17 source/target compatibility at `app/build.gradle.kts:36–37`. The implementation report, `VERIFICATION.json`, `BUILD_ATTEMPTS.json` and their templates were corrected to the directly supported statement: `Files.readString` was unavailable in this Android unit-test compilation/toolchain. The reviewer rechecked the corrected published wording. No production/test source or evidence ZIP changed for this narrative correction.

The final implementation report explicitly preserves **NO_DEVICE/NOT_RUN** for actual WebView no-reload selection, native callback/renderer/lifecycle behavior, owned fixture/Cookie traffic, manual login/manual resume, Compose 200%/RTL/TalkBack, and pixel comparison. It does not claim an APK build/install, current device acceptance, universal post-background/process recovery, or a rerun of unrelated module suites. Its historical APK and unrelated issue limitations remain disclosed. The accepted outcome is limited to the live, same-origin, process-local action plus the verified cloud build/JVM checks.

### Final evidence hashes

| Evidence | SHA-256 |
| --- | --- |
| COMPLETE_APP_EVIDENCE.zip | 57e2f0cb16f0442893e44debd9e3f9b856401d9937838586ac597afcfee06442 |
| INPUT_MANIFEST.json | 81bbf71978f8ba1112aaac769827c368b8f53f75dfc0ece31ee3b604e67c4c5f |
| INPUT_MANIFEST.build1.json | 0cf4a5ef8950797427943a81efacbffed0b312cec87b2eab55d03c6f9e53d429 |
| VERIFICATION.json | 0432d73b5f71f57913a1a6bc15d97beb5a503ac1b18ca93eaa787064de60424f |
| BUILD_ATTEMPTS.json | 733fc4e0e8dccbd6ba4e98bc2d1b1854d04842985896336d675ff0b0d4edb10a |
| TASK_STATUS.json | 26eb97c0a7b687c69782cdfd12303858838929a05f934f79f531b59c3733a56f |
| JVM_CENSUS.json | b85d1e5d0f7b01b9149445e89215b79bbe81d91a07e017da497cf505e38c1696 |
| build1.log | 2ef83de4a0e06f7a1c7d9039d0f58313b219fb1bd6bd9ac043c95cc1ba70dd47 |
| build2.log | 42af41323ae61172a64e19d6d6da46c1d1b0b10fc8c4c96f5e01e7afff67024a |
| docs/m11a/AUTHENTICATION_ORIGIN_NAVIGATION_20261013.md | 38a0c41dfa315f5366a07a72eb302363e43c5c7145db75d7e81fece0aeb00d7b |
