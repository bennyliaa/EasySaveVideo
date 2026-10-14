# Central Discovery settings

Date: 2026-10-14 UTC. Base development-branch commit:
`99146ffc86df94e951e8cff48b0e7601d0bac025`.

## Requirement and implemented gap

Original Product PR-20 / section 10, UI framework settings category/detail, PPT
21–23 and contract section 11 require discovery preferences in Settings. The
baseline persisted those preferences and exposed them only inside Discovery's
filter panel; the central settings category list did not contain Discovery.

This slice adds that category and its compact/detail and wide split-panel body.
It edits actual preferred columns (1–6), results per page (1–100), all four repeat
policies and all three search intensities. Explicit Restore discovery defaults
sets 2/20/Prefer new/Balanced only; no history, provider progress, source config,
media or unrelated preference is cleared. It uses existing SkinKit-backed
controls, paired EN/ZH copy, radio selected semantics, >=48dp rows and wrapping
buttons. There is no new theme, dependency, provider, endpoint or permission.

## Single owner and operational behavior

- Both the new category and original filter panel send field-specific intents
  to the same current AppSettings owner. Relative adjustments apply to its live
  values, not a remembered whole-preference snapshot. Original panel +/-10
  behavior remains; its stale decrease is a no-op if the live value is <=10.
- Both dispatch paths use a production guard requiring a successfully completed
  settings load, live foreground Activity and matching current destination.
  An old filter callback cannot overwrite the current Settings page and vice
  versa. Invalid deltas are rejected; legal value boundaries are saturated.
- The existing AndroidSettingsStore keys and BrowserPersistence serialized,
  coalesced IO remain authoritative. Existing controller.updateSettings performs
  revision/mode changes. The category never submits a keyword search, turns on a
  provider, clears history or requests more results on its own.
- Existing semantics preserve the current materialized page while revising later
  pages for repeat-policy/page-size changes. Source scheduling still honors the
  foreground Discovery visibility and power gates. Settings stays outside that
  visibility. A faster mode does not bypass those gates.
- On startup, successfully loaded discovery values are merged into the current
  owner without overwriting an early theme/language edit. If early settings were
  queued, the merged owner is saved again through the same IO queue. This bounded
  change does not claim to redesign startup merging for every unrelated setting.
- Central Settings now displays translated load/write failure warnings rather
  than relying only on a Browser notice. Failed loading disables discovery edits
  to protect saved values. Write warnings remain conservative and do not assert
  that a later write succeeded. No persisted-success toast is fabricated.

## Review and tests

Independent read-only review caught two integration omissions: the older filter
panel still supplied a whole captured value; and persistence failures were only
visible in Browser. Both were fixed as described above. The final follow-up
review found no remaining blocking defect in this slice. The reviewer did not
rerun Gradle or perform Android acceptance.

16 new app tests exercise production reducers/guard/merge functions, all legal
column and page values, enum mappings, repeated/cross-entry edits, unrelated
preference retention, defaults, all destination/lifecycle/load combinations and
startup merges. XML/source contracts check translation keys/format arguments,
actual MainActivity/store wiring and control structure. They are explicitly not
mounted Compose or Android preference-IO tests.

Two added real-coordinator tests (with synthetic runtime/store) check inactive
Settings and background adjustments: no source request, unchanged query/session,
retained current page/history/progress and no destructive effect of defaults.

Final strict-offline aggregate exited 0: all 13 module test tasks freshly executed,
3,700 registered tests, 3,698 PASS and two existing private-corpus SKIP, zero
failures/errors. App has 2,410 passes; Discovery has 309. Both Debug/Release Kotlin
compilers executed and passed. Both lint variants passed with no Error/Fatal:
Debug 60 warnings/8 hints, Release 61 warnings/8 hints, unchanged from baseline.
The exact admitted runtime graph remains 90 artifacts. Existing boundary,
Debug-only fake/demo exclusion, original license bytes and seven-getter ABI checks
passed. Aggregate: 102 tasks, 32 executed and 70 up-to-date; only the 13 test
executions are described as fresh, not every unchanged build task.

Sanitized summary, task statuses and source hashes are in the adjacent
`settings-discovery-20261014/` evidence directory. Full local logs and intermediate
failures are retained separately; no raw environment/user data is published.

An initial focused compile failed on test-only Files.readString being absent from
the Android stub; readAllBytes corrected it. The first aggregate run found two
older exact-source assertions (indentation and changed conditional expression);
these were aligned to the actual guarded wiring without changing Wi-Fi or
recording-language production behavior, then the whole suite was rerun.

## Acceptance and continuation

Actual SharedPreferences failure injection, Activity lifecycle/rotation, mounted
Compose rendering, phone/tablet/200% font, TalkBack and device interactions remain
NOT_RUN. No APK/AAB assembly, signing, installation, device access, network source,
real media or cleanup action was performed. The existing integrated candidate
APK remains unchanged. Vault, interpreter and payment remain disabled.

The next independently executable original requirement is the central privacy
entry, reusing established browser-history and cache controls with explicit user
confirmation and accurate scope/feedback. The project continuation queue is
`ORIGINAL_REQUIREMENTS_REMAINING_QUEUE_20261014.md`. This source slice's backup is
not product completion or a reason to wait for deferred device testing.
