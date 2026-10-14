# Library display controls and search recovery

Date: 2026-10-14 UTC. Source base: development/cloud-followup-20261013 at
b9761a8676fe3216ceb5bfff196c09c1beae1fdc. This is a bounded source and JVM
verification increment, not Android device or complete UI acceptance.

## Requirement and observed gap

Original UI requirements 03 UI-R02/UI-R08/UI-R13 require accessible selected
states, translated visible/semantic copy, and clear recovery exits. Tester
requirements 04 T-05/T-07 and interface contract 05 section 10 cover locale,
selection semantics and media-library sort/filter state.

The library had four sorting actions but no selected semantics; Name/Date used
a concatenated check mark, direction labels were hardcoded Chinese, and the
list/grid control named the next mode without an explicit current-mode state.
A nonmatching search had no one-click route back to the current folder contents.

## Implementation

- Reuse the existing SkinKit-backed EsvButton/EsvText and the existing library
  sort/reverseSort callbacks. No sorting implementation or query owner is added.
- Show a localized current sort/direction/view summary. All four sorting buttons
  expose the real selected property. Direction and view actions describe their
  next action separately from the current stateDescription.
- Keep original enablement: sort/reverse/create require foreground, ready, idle,
  nonloading catalog; the display-only view toggle remains available while a
  catalog read is pending. Existing move-mode controls remain separate.
- Reuse Foundation FlowRow so direction/view/folder actions can wrap, without
  reducing text or introducing independent theme tokens.
- Reuse the existing 48dp InputClearButton for nonempty library searches. Clear
  invokes the existing debounced search path with empty text, retaining folder,
  sort, direction and page size while resetting the cursor.
- Clear consumes only the exact immutable LibraryViewState snapshot captured by
  the rendered action. Old callbacks cannot clear a newer same-value query after
  A-to-B-to-A, foreground-owner changes, read completion or another state update.
  Existing runtime availability/busy/move checks remain enforced.
- Independent review identified the initial structural-query equality weakness.
  It was corrected to snapshot identity before final validation and publication.
  No other confirmed blocking issue remained in that bounded review.

## Verification

Final strict-offline aggregate passed:

    :core-model:test :core-domain:test :ui-theme:test :discovery:test
    :save:test :recording:test :library:test :hls:test :commons-provider:test
    :searxng-provider:test :verification-client:test :verification-local:test
    :app:testDebugUnitTest :app:compileReleaseKotlin :app:lintDebug
    :app:lintRelease :app:runtimeInventory

- 3,576 registered tests: 3,574 passed, 2 existing private-corpus tests skipped,
  0 failures/errors. Affected app tests executed; unchanged module tasks may be
  UP-TO-DATE. This is not a claim that all unchanged tests were freshly rerun.
- App: 2,288 tests passed. This change adds 25 tests: 16 parameterized production
  mapping/semantics cases, one bilingual resource contract, and eight real-runtime
  query/clear regressions. The independently reviewed three classes contain 81
  passing tests including pre-existing runtime coverage.
- Actual production SemanticsModifierNode properties are checked in the JVM for
  all 4 sort fields x 2 directions x 2 views. This does not mount the complete
  Compose screen or establish TalkBack/focus/touch-target device acceptance.
- Runtime tests cover actual query-lane dispatch, cursor reset, same-folder
  recovery, retained sorting, pending debounce replacement, repeated clear,
  newer query ownership, late read completion, busy/background/closed rejection,
  same-query ABA and foreground-owner changes. Synthetic repositories only;
  no user media, accounts, filesystem output or network sources are exercised.
- Both Debug and Release Kotlin compilers executed successfully.
- Both final lint variants passed with zero Error/Fatal. Debug: 58 warnings and
  8 hints; Release: 59 warnings and 8 hints. Existing warnings are not claimed fixed.
- Exact reviewed runtime graph remains 90 artifacts for both variants. Existing
  boundary, release fake/demo exclusion, approved-byte and seven-getter ABI checks
  passed. No new dependency or SkinKit modification.
- Ten new resource keys have matching English and Simplified Chinese sets and
  matching format arguments. Locale changes remap presentation only.

## Remaining acceptance and scope

Rendered compact/expanded layouts, 200% font, pseudolocale, TalkBack order and
actual touch interactions remain NOT_RUN on devices. No APK/AAB was built or
installed. Existing device acceptance remains open; no screenshot or native-media
success is inferred from JVM tests. Vault, dynamic rules, payment, unconfigured
Commons and thumbnail dependency gates are unchanged. No delete, export, save,
recording, media ownership, repository visibility or minimum-Android rule changed.

Only this increment's eight source/test/resource/build files and this sanitized
ESV report are included in the backup. No APK, private media, credentials, raw
user logs, third-party source bundle or unrelated project file is included.
