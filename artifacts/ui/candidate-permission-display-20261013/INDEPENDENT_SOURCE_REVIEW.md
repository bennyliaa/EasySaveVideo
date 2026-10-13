# Final candidate generated-label review

Result: PASS — independent static review; no blocker found in the candidate-label slice.

Review date: 2026-10-13 UTC.
Scope: nine inputs in `candidate-inputs.json`, including the shared finalized `app/build.gradle.kts` contract input registrations. Browser permission implementation is outside this review; its input registrations were checked for build-only scope. Review was read-only against source. No Gradle, compilation, JVM test execution, Android installation, or emulator test was performed by this reviewer.

## Verified invariants

- `PageSaveCandidate` has one production constructor, in `PageSaveDetector`; the generated label has one production presentation consumer, in `SaveTaskScreen`. The new typed indices are not used by serializers, Providers, persistence, or the save request wire model.
- A byte-level comparison against the supplied baseline proves that the detector changes only `label: String` to `displayIndices: PageSaveCandidateIndices` and the one generated-label constructor argument. JavaScript, URL validation and source-byte preservation, item bounds, deduplication, HLS upgrade via `copy`, opaque identity, and selection timing remain unchanged.
- The new factory preserves the old video clamp at 1..32 and the original array ordinal `i + 1`; filtered or duplicate entries cannot renumber surviving candidates. Resource input indices originate from the existing bounded 0..31 loop.
- A byte-level comparison proves that `SaveTaskScreen` changes only the label rendering call. The original source host, resolution and duration rendering, action callbacks, entitlement checks, supported/protected checks, and current-selection gate are unchanged.
- Labels resolve through the current composition resource context and existing locale-aware integer formatting. There is no cached localized DTO string, process-default locale selection, Chinese text splitting, webpage-title parsing, filename translation, or source URL transformation.
- The existing redacted `PageSaveCandidate.toString()` remains unchanged. HLS `copy` continues to carry the original indices object, ID and SourceUrl unless explicitly overridden; only the existing supported/method fields are overridden in production.
- Both resource files parse, contain 15 uniquely named string resources, and add only `save_display_candidate_label`, using complete English and Chinese templates with two `%s` arguments.
- The finalized build file has only four additional contract inputs relative to the supplied baseline: `PageSaveCandidateIndices.kt`, `browser_permission_notice.xml`, `BrowserNoticeResources.kt`, and `EsvLocaleHost.kt`. No plugin, dependency, build mode, runtime admission or other build behavior changes were introduced by this delta.
- All nine current input hashes and all seven baseline hashes match the supplied manifest. Shared build hash is `abf68c18cd75c00f9f6dd8f562e585fd12be384dcf7171c6b0b9cb67674e2d0f`.

## Test-source review

The six new test methods cover 32 resource positions times eight video bounds (256 cases), gaps caused by omitted source entries, DTO/HLS copy identity and redaction, both complete resource templates with US/Chinese/Arabic numeric formatting, current-composition source wiring, and absence of title parsing or serialized localized labels. The existing resource test now expects 15 strings and the new label consumer while preserving source/action assertions.

These are reviewed test sources, not a claim that the tests passed. Source-wiring assertions do not render Compose or exercise actual WebView parsing. Compilation, executed tests, aggregate gates, and Android locale rendering remain the build/device owner's verification responsibilities.

## Reviewed SHA-256 inputs

- `app/src/main/kotlin/com/easysavevideo/app/save/PageSaveDetector.kt`
  `3e2e8566ba951e6c467f66e3c2e2afa7e21b0fa46e126685047c70c4856d6567`
- `app/src/main/kotlin/com/easysavevideo/app/save/SaveTaskScreen.kt`
  `c0481df8906cfd7ad39c80988b5cd018c2639ee066d438b1e2c052a3d980d25c`
- `app/src/main/kotlin/com/easysavevideo/app/save/SaveDisplayResources.kt`
  `0bd9c36407c284235c1f74c996061a007ba87f160462dca7b38885cc1596e266`
- `app/src/main/res/values/save_display.xml`
  `093e065472b55f26c461c8c14842e827d064a2ffcabacb9e1418e6a53ea93c37`
- `app/src/main/res/values-zh-rCN/save_display.xml`
  `6affbd7d12d7b30e3b73184ea5f695ebeac4b138d7559ed63aab5f510ab380ed`
- `app/src/test/kotlin/com/easysavevideo/app/save/SaveDisplayResourcesTest.kt`
  `5c795a7577dc63879514114357314ea9ec45540789a1c8031957693248110a46`
- `app/build.gradle.kts`
  `abf68c18cd75c00f9f6dd8f562e585fd12be384dcf7171c6b0b9cb67674e2d0f`
- `app/src/main/kotlin/com/easysavevideo/app/save/PageSaveCandidateIndices.kt`
  `176cade88300911937e731a25163aae8b35840183a45956b54187d5d143634b1`
- `app/src/test/kotlin/com/easysavevideo/app/save/PageSaveCandidatePresentationTest.kt`
  `f49d9ea2f5205e9d38d3520f77b992a810a5cb602668121dc4cb23f39ba1c1a9`


# Final Browser Permission Notice Review

Result: **PASS — no blocking issue in the reviewed source changes.**

Reviewed on 2026-10-13 UTC. This report supersedes the earlier review of the pre-redaction BrowserNotice and nine-test revision.

## Scope and method

Independent read-only source review of the seven permission-notice files, plus the final app/build.gradle.kts contract-input registration. No production files were edited and no Gradle, compilation, JVM test, lint, Android rendering, WebView callback or device test was run by this reviewer. Parent-owned serialized build results are separate evidence and are not assumed to pass here.

## Findings

- Both existing permission-denial callbacks still perform exactly one completion before publishing notice state. Camera/microphone retains request.deny(); geolocation retains callback.invoke(origin, false, false). No grant policy, callback ownership or execution ordering changed.
- Existing BrowserInputPolicy.origin projection still supplies only the sanitized origin; invalid origins remain nullable and render the localized unknown-source fallback. No additional page content or private inputs are collected.
- The single nullable Compose notice state and existing dismissal remain shared. The wrapper retains ephemeral display data only, without Android permission requests, callbacks or persistence.
- All eleven legacy literal notices and showNotice(text) retain their exact raw values. BrowserNotice.Text is rendered by directly returning notice.value, with no parsing, formatting or truncation. Data-class equality retains value comparison.
- Permission message and unknown-source resources resolve at the current Compose display boundary. They are not flattened into stored text or cached. The existing API31/32 LocalContext and LocalConfiguration override remains applicable.
- The two resource files contain the same three unique keys and matching positional placeholders. The Chinese denial wording preserves its original meaning; both English strings explicitly report refusal and unavailable access.
- Independently reversing only the authorized typed-state, raw-wrapper and two permission-display substitutions yields the exact SystemBrowserHost baseline. BrowserScreen differs only by its display-adapter call, and captured MainActivity remains byte-identical.
- Final diagnostic redaction is correct: Text.toString() returns only BrowserNotice.Text; PermissionDenied.toString() includes only its finite static enum kind. Neither output exposes the raw value or origin, while the original display values and equality remain intact.
- The final test file has ten tests. The added diagnostic test verifies exact redacted outputs and retained original values. The earlier nine source/value/resource contracts remain present, including denial count/order, raw Text preservation, origin projection, both language renderings and current-Compose lookup.
- Final app/build.gradle.kts registers browser_permission_notice.xml for both locale directories and BrowserNoticeResources.kt plus EsvLocaleHost.kt as explicit source inputs. Existing SystemBrowserHost.kt and BrowserScreen.kt registrations remain. These inputs are attached to every Test task with relative path sensitivity. BrowserNotice is exercised through the compiled test classpath and does not require an extra raw-source registration. The accompanying PageSaveCandidateIndices registration is present but candidate implementation review is outside this report's scope.

## Remaining validation

Compilation, the ten JVM contracts, lint and runtime/UI behavior require the parent's serialized validation results. Source inspection and these static checks do not establish on-device acceptance. No code change is requested by this review.

## Hash-bound reviewed inputs

All seven current files were independently verified against the refreshed author manifest. The five files not involved in the final redaction change retain their previously reviewed hashes.

Author manifest: ${BROWSER_SCRATCH}/changed-files.json

Manifest SHA256: f2dafeeebd0e97beb008fa8854e065308366b95e1abc49bbd8b5fcea2f505550

- app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt
  - SHA256: 05e44c99217a9df6c0f71817612ab601ab78a3bd03606c7a4e81518a35b726c2
- app/src/main/kotlin/com/easysavevideo/app/browser/BrowserNotice.kt
  - SHA256: e33594fab2412e5b68d2df9146c6cba56b3c9aedf48b7fb5d97acf395c00526e
- app/src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt
  - SHA256: 79074751c839b356e54caf23ec728ec508b3fc3f0cf31435d4cf516401d57725
- app/src/main/kotlin/com/easysavevideo/app/ui/BrowserNoticeResources.kt
  - SHA256: c8adec37a8790557bcaec1044b99ad4c1b9156c7348a465e7598352397e7bda6
- app/src/main/res/values/browser_permission_notice.xml
  - SHA256: 9844e430c03f39598a93fd2f2a55c6cc8f07cc7ed7042617636d7d961b46d360
- app/src/main/res/values-zh-rCN/browser_permission_notice.xml
  - SHA256: bbfeb9cd18bf392fb656aac75ceabc6ce80af8089a1030c9c966fb2934f2533b
- app/src/test/kotlin/com/easysavevideo/app/ui/BrowserPermissionNoticeContractTest.kt
  - SHA256: 2554d522998eb9d0645df6eb43505db44a4bddc18a35f434d821e0841631fb35
- app/build.gradle.kts
  - SHA256: abf68c18cd75c00f9f6dd8f562e585fd12be384dcf7171c6b0b9cb67674e2d0f
