# Offline notices viewport and Discovery power copy

Base: verified source snapshot of 20fddc9c33b399ab75f8b8eee0f21e41e6f5b06f.
This cloud work has no reconstructed Git history, device operation or APK build.
It addresses the existing notices P2 review and the finite internationalization
checkpoint's explicitly remaining detailed power-policy copy. Original UI baseline
requires Chinese/English resources and accessible compact/large-font presentation;
this is not completion of UI-E or physical energy acceptance.

## Notices

The fixed title and Back control formerly consumed height before a weighted
LazyColumn. At short height or large text, the list could receive zero height.
Title, Back, state messages and bounded notice chunks now belong to a single
fillMaxSize LazyColumn. There is no nested scroller, weighted body or fixed header
subtraction. Text/control styling still uses SkinKit; controls remain localized.

Loading/failure use a separate transient LazyListState. The caller's content state
is only measured against the complete loaded item set. Stable content keys and the
external saved state retain current-version reading position across close/reopen;
real Android restoration remains untested. The extra title/Back rows change numeric
indices: an old-version index-only restore can resume up to two chunks earlier.
There is no cross-version index migration or claim of exact old-version position.
All content remains reachable; no old text is omitted or rewritten.

The 552250-byte bundled asset is unchanged:
SHA256 27df093ea522ae12733b400e54856dbadd68b695b258ce0b7e31a778d5101a5e.
The bounded reader, asynchronous loading, Unicode/CRLF-preserving 4096-character
chunking and original attribution bytes remain unchanged.

NoticeViewportContractTest explicitly combines source-structure checks with an
arithmetic viewport model (1/48/96/240-pixel heights, 1x/2x/3x synthetic text sizes).
It guards the single full-height scroller and proves model row intersection at
valid scroll offsets, including the final content. It is NOT a real Compose
measurement, rendered font, screenshot, TalkBack or Android device acceptance.
Existing reader/chunk tests retain the real bundled-text reconstruction checks.

## Discovery power localization

16 finite keys have paired English default and Simplified Chinese resources.
Every existing observation/status/reason explanation, signal origin, pending
fence, budget and recovery disclaimer is localized. No additional sensor values
or causes are invented; the policy has no separate reason enum. All existing
branches retain their meanings, including unknown/expired observations, paused
requests and no measured energy-saving claim.

The composable reads LocalConfiguration and resolves resources through a context
created for that current configuration. Locale is not memoized or persisted in
the presentation model. Unsupported locales use Android's default English
resources. Actual runtime Android locale selection/fallback is NOT RUN; JVM tests
parse and format the actual paired XML and switch text providers on the same
immutable policy snapshot. Exhaustive battery/thermal/network combinations,
all observation branches, all text keys and numbered interpolation are covered.
Core policy, budgets, thresholds, freshness, demand/recovery, debug isolation and
callbacks are unchanged. No dependency or permission has been added.

## Validation status

Asset length and SHA256 checked unchanged. Initial targeted JVM run: this group
31 tests PASS (17 notices, 14 power presentation/resources), zero failures/errors/
skips. Both Kotlin variants and lint passed. Lint exposed an added English budget
plural warning; the label-style wording was corrected without suppression or
numeric changes. Final resource verification forced App test execution after the
XML-only change: these same 31 tests PASS again (0 failures/errors/skips), within
the final 468-test App run. Both Kotlin variants compiled; final dual lint reports
0 errors, 60 warnings and 8 informational/hint entries each. One new power-copy
warning was removed; the remaining warnings are not all claimed preexisting.
Aggregate evidence is artifacts/cloud-validation-20261012; local six-suite XML,
final resource hash and scoped counts are in
artifacts/ui/cloud-notices-power-20261012/JUNIT_RESULT.json.
Independent discovery PowerPolicy7 + Coordinator22 tests also passed, separately
from the presentation count. No full-project or device acceptance is implied.

Important build-input limit: these JVM tests parse XML files directly, but resource
XML alone is not declared as an input to testDebugUnitTest. An incremental run can
report UP-TO-DATE without re-reading the changed text. Final resource verification
therefore forces test execution. Future copy-only changes must do the same unless
the test-task input contract is separately improved; this slice does not change
build scripts. Device/emulator/phone, real Compose layout, TalkBack,
physical battery and real network/media execution are NOT RUN/NOT MEASURED.
