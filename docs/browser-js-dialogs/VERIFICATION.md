# Verification — 2026-10-13

## Final result

- App JVM suite: **1,607 tests, 0 failures, 0 errors, 0 skipped**, 181 suites.
- New JS-dialog tests: **34 passed**: controller 20, input/source policy 6, wiring/resources 7, synthetic Foundation scrolling measurement 1.
- Debug and release Kotlin compilation: passed on the final production-source snapshot.
- Debug/release lint: **0 errors, 60 warnings, 8 hints each**, same totals as the preceding pagination baseline. A new preliminary PluralsCandidate warning was removed by making the fixed input-limit label grammatically unambiguous; no lint suppression was introduced.
- Final independent read-only review: no remaining definite P1/P2. Five findings fixed; see INDEPENDENT_REVIEW.md.
- No dependency changes, APK build/install, device/emulator operation or public-site/account test.

## Commands and evidence

All Gradle execution was serialized using the existing build owner's `run-gradle.sh`, with `--offline`. No parallel Gradle execution was launched.

Focused command: `:app:testDebugUnitTest --tests '*BrowserJsDialog*'`.

Full command: `:app:testDebugUnitTest :app:compileDebugKotlin :app:compileReleaseKotlin :app:lintDebug :app:lintRelease`.

1. First focused run failed: **25 tests / 1 failure**, `sourceLengthIsBounded` threw `IllegalArgumentException` from IDN canonicalization. Preserved unchanged in `evidence/failed-focused.log` and `evidence/failed-focused/`. Fixed locally by total nullable parsing at the JS boundary, including acceptance revalidation.
2. Focused follow-up passed 32 tests after ownership/layout/resource/measurement additions. A further focused pass passed **34 tests** after scroller identity and load scoping regressions; log `evidence/focused-34-tests.log`.
3. `evidence/final-full.log`: full final-production-source build succeeded, **75 actionable tasks / 29 executed / 46 up-to-date**, including actual debug/release Kotlin compilation and both lint analyses. App suite passed 1,607 tests.
4. Reviewer suggested explicitly checking `key(request)` exists before index ordering comparisons. After strengthening only those test assertions, the full command ran again successfully: `evidence/final-verification.log`, **75 actionable tasks / 7 executed / 68 up-to-date**. App tests and changed debug-unit-test lint analysis executed; production compile tasks and unchanged release lint analysis were up-to-date from step 3. This is the final frozen test/source state.
5. Final new-suite XML is under `evidence/final-focused/` (copied from the final full run, not a filtered-only run). `FINAL_RESULT_SUMMARY.json` enumerates all final App suite counts and source-report SHA-256 values. Final lint XML is preserved for each variant.

The wrapper's existing analytics-directory/deprecation warnings remain non-fatal. No build output is a device or website-login acceptance result. The layout measurement exercises the actual Foundation scroll node using synthetic heights for 80/160/240/600px viewports and 1×/2×/3.2× font-scale scenarios; it does not render glyphs, keyboard or Android windows. Wiring tests inspect registered source/resource inputs and do not instantiate WebView.

## Publication boundary

Publish only the separately frozen explicit whitelist. Existing unrelated source changes belong to their respective slices. This slice adds only app browser/UI logic, tests, localized strings, Gradle test-input registration and these documents/evidence. No core-domain or Provider implementation was changed.
