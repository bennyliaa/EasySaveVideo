# Independent Save runtime notice integration review

Status: **PASS for frozen source/copy review and independently checked current App JVM/compile/lint/runtime-admission evidence; no outstanding finding in this scope.** Other module tests were NOT_RERUN_THIS_SLICE, and all device acceptance remains NOT_RUN. See the final evidence addendum below.

Reviewed at: 2026-10-13 08:58:30 UTC. Review is independent and read-only for production/test inputs. No Gradle, APK assembly, installation, emulator/device action, or production edit was performed by this reviewer. This report is the only reviewer-created file.

## Reviewed scope and sources

- Source root: `${SOURCE}`.
- Design/inventory: `${NOTICE_PLAN}/PLAN.md` and `emissions.md`.
- Exact captured before files and substitutions: this scratch directory's `before/` and `runtime-replacements.json`.
- Frozen author inputs: `resources-manifest.json`; all seven hashes independently matched the files inspected.
- Wider perimeter comparison: `${PRIOR_REGRESSION}/host-gates/input-manifest-after.json` (1,839 baseline files). No files were missing; the only changed existing code/build/test files are the five scoped modifications. Two evidence documents also differ from that older manifest. Exactly eight new source/resource/test files belong to this notice slice; no new source outside this slice was found.

## Findings and strongest checks

1. **Exact behavior-preservation check passed.** Reversing only every explicit ledger replacement, with exact occurrence-count assertions, reproduces the complete baseline AndroidSaveRuntime bytes. Baseline SHA-256: `0c0f3c806b60ba63290db802b185df6301570211f3e4319926e5231d36e55a76`. This is not a whitespace/string-stripping approximation. All original guards, ordering, posts, cleanup, worker/retry settings and native notification labels are unchanged outside the authorized notice type/RHS substitutions.
2. **Complete inventory preserved.** The runtime has 49 assignments: 44 emissions and five clears, plus initial null. Families are 36 fixed-message sites representing 34 fixed values, one network site choosing two additional values, one capacity site, one detector site covering six reasons, and five classified failure sites. Original 13 posted emission boundaries are preserved by the exact reverse comparison.
3. **Timing and classification preserved.** `networkPolicy.initialized` is still read inside the original `if (showNotice) main.post` delivery. Its `SaveFailure` catch, false return and admission call remain unchanged. All three `SaveErrors.classify(failure)` and two `result.error` paths remain at their original immediate/posted locations after the same cleanup and generation guards. The SaveErrors source itself matches the wider baseline hash.
4. **Legacy Chinese and equality preserved.** Independent parsing compared the baseline runtime/screen literals directly with current Chinese resources and pure mappings, rather than relying only on the generated full-message map. All 34 fixed messages, six detector reasons and 45 SaveError values match their original 35 detailed Chinese groups exactly. Every pair of domain errors retains the original same-text equality relationship; both fallback values explicitly map to INVALID_REQUEST. The two existing Wi-Fi resources remain reused and distinct from the detailed WIFI_REQUIRED failure message. Capacity captures `limits.maxJournalEntries` unchanged, with exactly one `%1$s` argument and no active-task-count substitution.
5. **Resources reviewed.** EN/ZH both contain 75 new strings and one capacity plural (English one/other; Chinese other). Keys match, values are nonempty, placeholders match, and no new names collide with existing locale resources. All English copy was reviewed for the original safety/retention/manual-action/no-fallback meaning. The complete channel totals 78 display groups including two reused network messages. The initially observed test-loop gap is absent from the frozen input: `en.entries + zh.entries` checks both languages, with ResourcesTest SHA-256 `5200f555675b1800ffa7f07748beebd20b66c3bcbf81deb8278ffa8812ff43fb` fixed before build freeze.
6. **Locale/display boundaries preserved.** The new resolver uses current-composition `stringResource`/`pluralStringResource` and the existing `saveIntegerText`, whose locale comes from `LocalConfiguration`. SaveTaskScreen resolves its existing nullable notice at direct use; MainActivity resolves exactly the Browser and Recording adapters inside the existing EsvLocaleHost. No cached string, default-process locale, new effect or runtime event was introduced. Recording keeps `recordedEditorNotice` precedence and its String interface. RecordingScreen and EsvDiagnosticNotice remain byte-identical to baseline, including `remember(original)` and explicit disclosure; a resolved-text/locale change therefore retains its existing collapse rule.
7. **Privacy and authority remain bounded.** The sealed payload contains only fixed/failure/reason enum values or the captured capacity Int. No String, Throwable, Context, source/request/record, callback or identifier payload exists. Capacity diagnostics are redacted. Mapping/rendering has no coordinator, browser, service, persistence, network admission, logging or task-command authority. No schema, codec, core/save/storage/provider wire, service/notification, dependency or permission change was found in the perimeter comparison.
8. **Tests and inputs reviewed.** The 19 new test methods (model 6, resources 8, wiring 5) cover exhaustive inventories/equality, exact Chinese fixtures, bilingual keys and safety clauses, placeholder/capacity formatting, token-preserving pure en→zh→en fixtures, payload/privacy shape and the three production entrypoints. SaveUiPolicyTest's two prior safety assertions now resolve the mapped Chinese resources, retaining their semantics. app/build.gradle.kts changes register the new resources and relevant model/renderer/Recording/diagnostic source reads; existing runtime, screen, MainActivity, Browser and formatting inputs were already registered.

## Verification boundaries

- **PASS, executed here:** read-only Python/XML/hash/static checks, exact runtime reversal, baseline resource/text/group comparisons, resource collisions/placeholder checks, frozen-input hashes, and wider source perimeter comparison.
- **Executed by the build owner, evidence independently verified here:** current App Kotlin/JVM tests and source-contract assertions. The pure locale fixture manually selects XML and formats an explicit Locale; it does not execute Android resources or Compose.
- **Current observed gates:** App JVM (including 19 new methods), debug/release compilation and lint, runtime90/admission and seven boundary checks passed as detailed below. Other module tests were not rerun for this slice; the prior full thirteen-module regression binds only production baseline b0c5647. It must not be relabeled a fresh current-slice full regression.
- **NOT_RUN:** actual device/emulator rendering, retained-notice live locale switching on API31/32 or API33+, native worker/Looper/WebView callback ordering, repeated/interrupted task flows, recording-consent/service interruptions, notification behavior, 200% text/layout and accessibility. Source preservation and pure/JVM checks do not establish these device outcomes. No APK or device acceptance is implied by this review.

## Frozen 13-file SHA-256 snapshot

- `app/build.gradle.kts`
  `0b2baf30f0117de9bbd333f8ed341a3dcac3a18cd5625dab3399d1bb2cf6ba6b`
- `app/src/main/kotlin/com/easysavevideo/app/MainActivity.kt`
  `33164b3349c1acef00ebfeaf177183a8a564bb274680cc1746c64bc42100715f`
- `app/src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt`
  `44d101eab4837219d0d0fff9a9a836b045079c5259885cfd37805c2942cd358e`
- `app/src/main/kotlin/com/easysavevideo/app/save/SaveTaskScreen.kt`
  `7b70acbc2d86849fd42d02b91abf432b2c991810eb349cae9b21ea27d06971d5`
- `app/src/test/kotlin/com/easysavevideo/app/save/SaveUiPolicyTest.kt`
  `17f6223d20d7c1c4217ab36b1fd8dd7c00031ac49854847f5e78858645500b9a`
- `app/src/main/kotlin/com/easysavevideo/app/save/SaveRuntimeNotice.kt`
  `285f3bf807d4dec4c005178522f1879ef069b82ab05e266cbd744121e8e7bacd`
- `app/src/main/kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeResources.kt`
  `a34da297d038882390053fc1041fae668163e25a936fc07049ee9bc661b52e8b`
- `app/src/main/res/values/save_runtime_notice.xml`
  `f636d24157a6c3c2462e6395bd4811757313e37c0a4a29600936ccbc0cbba831`
- `app/src/main/res/values-zh-rCN/save_runtime_notice.xml`
  `18a3c321a1368dd8bed329b33958322241c123821cf92c461ca40efdc88fa379`
- `app/src/test/kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeFixtures.kt`
  `a651bfa059f66e4683f5f7b2b0f844c4c2517385183f835d89a219a7e59a148f`
- `app/src/test/kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeTest.kt`
  `e247c2819e0961268998182fa00c3c252fafea0d5b24153efbb9dfa68da76216`
- `app/src/test/kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeResourcesTest.kt`
  `5200f555675b1800ffa7f07748beebd20b66c3bcbf81deb8278ffa8812ff43fb`
- `app/src/test/kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeWiringTest.kt`
  `b2df4ee1fb0c2f0edf1f5835145891b85897ca0571bb9dfc430294ee5e6efef2`


## JVM XML evidence addendum — 2026-10-13 08:59 UTC

The build owner ran these tests; this reviewer independently parsed the resulting XML under `app/build/test-results/testDebugUnitTest` and rechecked all 13 source hashes above. All 13 remained unchanged.

- `SaveRuntimeNoticeTest`: 6 tests, 0 failure/error/skip, XML timestamp `2026-10-13T08:57:13.930Z`.
- `SaveRuntimeNoticeResourcesTest`: 8 tests, 0 failure/error/skip, XML timestamp `2026-10-13T08:57:13.895Z`.
- `SaveRuntimeNoticeWiringTest`: 5 tests, 0 failure/error/skip, XML timestamp `2026-10-13T08:57:13.938Z`.
- `SaveUiPolicyTest`: all 11 tests, 0 failure/error/skip, XML timestamp `2026-10-13T08:57:13.956Z`. This includes `audioLabelsNeverPromiseAudibilityFromPermissionOrPcm`, whose source retains the explicit-choice audio clause and unsupported-media safety assertion through the new detailed resource resolver.
- Current App XML aggregate: 216 suites, 1,905 tests, 0 failure, 0 error, 0 skip. The three new suites contribute exactly 19 tests.

These results establish JVM/pure-model/XML/source-contract execution. They do not establish actual Android/Compose locale switching, native callback sequencing, device rendering or accessibility. At this intermediate XML check, the final Gradle tail and other gate evidence were still pending; the final addendum below resolves that status without claiming a fresh full-module regression.


## Final build/evidence addendum

Reviewed the build owner's `artifacts/ui/save-runtime-notice-20261013/VERIFICATION.json`, `TASK_STATUS.json`, `JVM_CENSUS.json`, `INPUT_MANIFEST.json`, `build1.log`, all archived App XML/lint payloads and `docs/m11a/SAVE_RUNTIME_NOTICE_DISPLAY_20261013.md`. No code/test input was changed by this reviewer.

- The retained log ends `BUILD SUCCESSFUL in 3m 14s`, with `75 actionable tasks: 33 executed, 42 up-to-date`. Owner reports terminal process exit 0, consistent with this successful log.
- All ten requested tasks have actual unsuffixed execution entries, not UP-TO-DATE/FROM-CACHE/SKIPPED entries: App unit tests; Debug and Release Kotlin compilation; both variants' lint analysis, report and entry tasks; exact runtime admission. The 42 up-to-date prerequisite tasks are not represented as freshly rerun.
- App XML remains 216 suites / 1,905 tests / 0 failures / 0 errors / 0 skipped, including all 19 new tests and all 11 SaveUiPolicy tests. Other modules' tests were not rerun in this slice; the prior complete thirteen-module results bind b0c5647 only.
- Independently parsed both archived lint XML files: each has exactly 60 Warning and 8 Hint entries, with no Error or Fatal entries. The runtime admission log explicitly verifies 90 exact reviewed artifacts for both variants.
- `INPUT_MANIFEST.json` SHA-256 is `f4e03c1d1b911c8a1610477ba372058d0c5c84fc9a6c13a511986803efcecb00`. All 1,399 manifest payload hashes match current source files; all 13 changed-file hashes in this review remain unchanged.
- `COMPLETE_APP_EVIDENCE.zip` has 220 unique entries: 216 App JUnit XMLs, two lint XMLs, a build log and a manifest. ZIP CRC integrity passed; all 219 payload hashes and sizes match its internal manifest. All sanitized suite attributes and testcase attributes/outcomes agree with the original XMLs, apart from expected hostname redaction and XML serialization. Archive SHA-256: `1c093bf093e5c6fc733eb29ab159f2929bbd6571c26026611c09ffa35c23f97c`.
- The owner's unchanged `scripts/check-boundaries.py` was reported executed after both compilers with exit 0. Independently read `scratch/boundaries.log` and verified the retained evidence `boundaries.json` is equal: all seven reported checks pass (core platform/provider/store import boundary; fake entitlements debug-only; synthetic discovery debug-only; release configured-source/zero-source fallback; 99 original license payloads plus manifest; SkinKit release JAR hashes; seven-getter ABI). The release registration/two-mode JVM item explicitly retains device NOT_RUN. This reviewer did not rerun the script.
- The published-slice report accurately keeps source/JVM verification separate from actual Compose rendering, native behavior, device tests and the previous full-module regression. Existing OPPO and Windows gaps remain explicitly open. No fresh device, APK, visual/pixel, accessibility or full-thirteen-module acceptance should be inferred.

Final independent conclusion: **no outstanding implementation or evidence-integrity finding in the bounded source/App verification scope**. The originally planned fresh full thirteen-module regression remains unperformed for this final slice and should be tracked explicitly if that broader acceptance step is still required. Native/device acceptance remains NOT_RUN.
