# Central existing browser preferences

Date: 2026-10-14 UTC. Base development commit:
`0c162f44effa557050a0e9649f752e6811b4637f`.
Product PR-20/PR-25 and the existing browser settings support a unified access
point. The original UI framework did not specify a separate Browser category or
require all four field names: this implementation centralizes existing behavior,
not a claim that a particular original slide mandated these exact controls.

## Scope and existing behavior

Central Settings → Browser and the old browser panel compose the same detail.
The four existing preferences and storage keys remain JavaScript, third-party
cookies, saved-tab restoration, and 1–4 active WebViews (default 3). No new UA,
site permission, incognito, Cookie isolation, dependency or backend is introduced.
The four fields use the same live AppSettings/BrowserPersistence owner and the
same SystemBrowserHost.applySettings. No second settings store or cached display
snapshot is added. The shared detail has no nested scroll; its parents retain
compact/split scrolling. Switch semantics and minimum 48dp rows are explicit;
active-limit actions wrap in a FlowRow.

Existing impact copy is reused verbatim in both languages: JS changes may affect
video/login but do not inject a native bridge; ordinary tabs share cookies and
this is not incognito or per-tab isolation; turning off restore removes stored
tab addresses without clearing cookies and restored pages require manual opening;
the active-view limit is not the total 12-tab limit and may release older WebViews.
Camera/microphone/location are denied in the current implementation, and file
upload still requires explicit per-request confirmation and system selection.
Actual browser/save/native behavior is unchanged except applying the user's
existing preference through the additional UI entry.

## Ownership and loading

Both entries dispatch field-level BrowserEdit intents; +/- uses a bounded delta
against the current owner. No captured whole browser or AppSettings snapshot is
accepted. The shared view retires callbacks when disposed and reads latest
editable/dispatch closures; owner-level admission independently requires successful
settings loading, foreground, alive Activity and the expected Browser/Settings
route. Preview-host controls remain disabled. A new view after returning does not
revive a retired view's callbacks. Load and save warnings stay in the existing
central settings heading and browser notice paths.

Browser edits are blocked before successful startup loading. Therefore the
startup completion can merge the loaded browser section after an early appearance,
language or unrelated edit without losing a user browser edit. The merged section
is applied to the existing host before any queued merged save, readiness and tab
restore. Other sections remain with their existing merge/persistence rules.

## Acceptance boundaries

14 production model tests exercise the actual reducer, admission, disposable-action
owner and startup merge, with synthetic AppSettings. Seven source contract tests check
composition reuse, MainActivity ordering, real host/store references, localized
impact copy and absence of new permission/UA calls. They do not execute Android
WebView, shared preferences, real process restart, Cookie mutation or UI gestures.
Focused final: 43 tests passed. The initial focused run had one test-contract
failure because it incorrectly expected restoreTabs in SystemBrowserHost; that
contract now checks its actual MainActivity/BrowserPersistence owners. Production
code was not changed to satisfy the incorrect assertion. Existing navigation,
Wi-Fi isolation and browser-copy contracts were updated for the shared detail
and field-level intents while preserving their original behavior checks. The
first aggregate additionally exposed an old RecordingNotificationWiringTest
startup-line assertion. It now verifies browser merge before host application
before notification-language mirroring, retaining existing accepted-language and
save-section ordering assertions. No notification production code changed.

Independent read-only review inspected both entry paths, disposal, startup
merge, real impact copy and the final focused XML, with no blocking finding.
It did not run Gradle or operate a device.

Final strict-offline aggregate exited 0: all 13 module test tasks freshly
executed; 3,795 registered tests, 3,793 PASS and two existing private-corpus
SKIP, zero failures/errors. App: 2,505 PASS. Debug Kotlin compiled in the focused
run and was UP-TO-DATE in the aggregate; Release Kotlin executed. Both lint
variants passed with zero Error/Fatal: Debug 60 warnings/8 hints, Release 61
warnings/8 hints, unchanged baseline. Exact 90-artifact runtime, import boundary,
Debug-only fake/demo exclusion, original license bytes and seven-getter ABI
gates passed. Aggregate 102 tasks: 34 executed / 68 up-to-date. Sanitized source
hashes, task statuses, test and lint summaries are adjacent.

Actual device/SAF/permission/rendering/TalkBack/font200 acceptance remains NOT_RUN.
No real user preference or user data was modified by development tests.

## Remaining original work

The queue now explicitly identifies PR-25 incognito and per-site permission
management as incomplete: fail-closed platform denials are not a complete manager.
These need a separate isolation/security design and review before implementation.
Next executable source work is reconciliation/design of original bounded save
concurrency or supported PR-12 fallback; exposing a number over a single execution
lane would not satisfy the original concurrency requirement.
