# Central default saving destination

Date: 2026-10-14 UTC. Base development commit:
`b4cd734f1bccaa2fb42cb9251c4bba810ea69993`.
Original Product PR-20 and original UI framework settings section require a
central saving destination control. The existing Browser settings already had a
real ordinary destination runtime; this slice makes that same control available
in central Settings → Saving, below the Wi-Fi preference.

## Implemented scope

The existing four preferences remain App Library, Ask, Album copy and Folder
copy. Both entries use the same runtime, persistence, native picker and notices.
There is no additional preference owner, optimistic saved state, automatic
folder selection or new platform permission. Selected semantics read the actual
runtime preference; loading and pending-storage text are paired EN/ZH resources.
The selected value changes only after the existing storage acknowledgement.
The central entry relies on the existing global notice/dialog rather than
rendering a duplicate result. No real user directory was selected in development.

A production DestinationSettingsActions adapter reads current ready/saving/
choosing/asking/admitting/folder state at each click and retires UI authority on
disposal. The central entry additionally requires the same current foreground
Settings generation and no competing prompt. Repeated or stale callbacks do not
start new writes/pickers. Choosing a missing folder respects the existing
admission/asking exclusion. Changes to future-task preferences preserve the
existing frozen destination of already accepted creation tasks.

Retiring this UI does not cancel an accepted native picker or persistence job.
System picker pause/resume remains owned by the existing runtime ticket. Its
current/related associations are independent of Settings foreground/generation;
receive/commit is fenced by the existing folder gate before storage update and
selection acknowledgement. Cancellation is an explicit action. Forget folder
only uses the existing forget-current-reference operation: it does not delete
files or revoke old system permissions. Existing explanatory copy remains.

## Evidence and limits

17 production-adapter tests use fake runtime ports and cover all preferences,
state combinations, missing-folder admission, duplicate writes/launches, cancel,
forget, background/closed owner, late picker return and persistence acknowledgement.
These are not AndroidOrdinaryDestinationRuntime or SAF integration tests.
Eight source contracts cover central reuse, single owner/notice, actual selected
semantics, retirement, foreground wiring, paired resources, and the existing
runtime ticket-to-storage continuity. Source inspection does not prove native
picker lifecycle behavior on a device.

Focused final: 35 tests passed. The initial focused run exposed three obsolete
source-string assertions in OrdinaryDestinationResourceContractTest; these were
updated for the adapter callbacks, exact new busy gates and lifecycle effect,
while retaining original resource, typed notice and task-dialog checks. The first
aggregate exposed one additional old ban on all remember/DisposableEffect usage
in OrdinaryDestinationPresentationTest; it now specifically permits only the
callback owner and still forbids remembering localized display strings. No
production workaround was introduced for these test failures.

Final strict-offline aggregate exited 0: all 13 module test tasks freshly
executed; 3,774 registered tests, 3,772 PASS and two existing private-corpus
SKIP, zero failures/errors. App: 2,484 PASS. Debug Kotlin compiled in the initial
focused run and was UP-TO-DATE in the aggregate; Release Kotlin executed.
Both lint variants passed with zero Error/Fatal: Debug 60 warnings/8 hints,
Release 61 warnings/8 hints (unchanged baseline). Exact 90-artifact runtime,
import boundaries, Debug-only fake/demo exclusion, original license bytes and
seven-getter ABI gates passed. Aggregate: 102 tasks, 34 executed/68 up-to-date.

Independent read-only review inspected the implementation, final new XML and
updated contracts without a blocking finding. Final aggregate results are in
adjacent `settings-destination-20261014/` sanitized evidence. Android/SAF,
process-death, font200/TalkBack, real storage and actual permission acceptance
remain NOT_RUN. No APK, installed app, user data, dependency, SDK, billing gate,
Vault status or SkinKit asset was changed.

## Next executable original work

Continue central browser settings through the existing preference owner, with
field-level edits and honest browser-application/persistence semantics. Keep
actual concurrency scheduling and PR-12 fallback reconciliation as separate
original-requirement work. Packaging this slice does not complete ESV.
