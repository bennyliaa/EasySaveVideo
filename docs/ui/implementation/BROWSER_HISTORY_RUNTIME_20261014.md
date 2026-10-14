# Ordinary browsing history: runtime, recording and UI

Date: 2026-10-14 UTC. Base: development/cloud-followup-20261013 at
`a5859d0217df32302b251b5289573fe51b4e6181`. The user explicitly selected the
most recent 200 entries with a 30-day TTL and a manual clear entry point.
Original PR25 and UI03/UI05 browser-history requirements now have runtime/UI
integration beyond the previously reviewed controller/storage foundation.

## Implemented behavior

- A main-thread process singleton owns the existing HistoryController and one
  serial storage worker. Activity/UI detachment does not cancel accepted commits
  or create a second writer. The existing app-private noBackup AtomicFile
  `browser-history.bin` is the only history store; it is not exported or backed up.
- Runtime explicitly selects 200 recent distinct addresses and 30 days. Repeated
  addresses retain stable IDs and move to the front. Retention is applied during
  startup, foreground/list reads and new accepted visits, including durable
  cleanup. No periodic/background scheduler was added. The app being closed or
  an untouched continuously open list does not promise deletion at an exact time.
- Each native Entry recorder belongs to an exact context and View. Only a live
  host-issued load/back/forward followed by an accepted page start arms recording.
  Restore-only callbacks do not create history. Once genuinely live, a later
  POST/form navigation with a new accepted request can be recorded without an
  override callback. Redirects cannot remint permission for the same epoch.
- Only accepted main-document finish, exact current URL/admission, ordinary
  selected READY tab, foreground and actual Browser surface can produce a visit.
  Restore-only, History API/hash-only, stale/wrong view, duplicate, stopped,
  failed, background, unselected and incognito paths cannot write history.
- Pause, leaving a tab, stopping or destroying an Entry cancels its pending
  permission. Confirmed clear/remove invalidates the existing navigation gate;
  pre-clear navigation/redirect/completion cannot repopulate deleted history.
  No incognito mode or microphone-isolation implementation was added or enabled.
- Browser exposes a history action, wide sidebar and compact dialog using one
  real Foundation list state. Only index/offset and panel-open state are saved;
  rows, titles, URLs and deletion confirmations are not saved in instance state.
- Rows capture controller revision-bound tokens during rendering. Native open
  reads current page/row authority, rejects non-Browser/background/private
  navigation, and closes the panel only after native acceptance. Stale callbacks
  cannot inherit a newer page owner or new same-value list revision.
- Clear-all and single-row removal use existing single-use confirmation tokens.
  Confirmation retains its creation-time page authority and exact state object;
  disposal, cancellation or changed state cannot upgrade old delete permission.
  An unreadable idle store can be explicitly reset with confirmation. No device
  data was accessed or deleted during this development work.
- Feedback reflects durable controller results: loading, slow, unavailable,
  empty, removed and cleared remain distinct. Failed writes are not silently
  replayed. Bilingual resources, locale dates and existing SkinKit controls are
  reused. History content is not sent to logs, diagnostics or this report.

## Tests and review

After freezing source inputs, the final strict-offline aggregate passed:

- 3,682 tests registered; 3,680 passed, 2 existing private-corpus skips, zero
  failures/errors. App: 2,394 passed. All thirteen module test tasks and exact
  runtime-admission verification were forced to execute in the final aggregate.
- History-specific 102/102 passed, including 59 new tests: recording adapter 24,
  runtime source wiring 6, presentation/controller 14, Foundation/reading 5,
  panel action dispatcher 10. The previous history foundation remains unchanged.
- An initial aggregate found an older JS-dialog source contract expecting the
  pre-history blocker expression. The assertion was updated to require history
  blocking as well, and the complete aggregate was rerun successfully. The first
  failure evidence remains available; no assertion was removed or weakened.
- Debug/Release Kotlin compile and both lint variants passed. Zero Error/Fatal;
  Debug 60 warnings/8 hints, Release 61/8. One new non-blocking PluralsCandidate
  warning concerns the fixed 200-address retention copy; existing warnings remain.
- Exact 90-artifact admission, core platform boundary, release fake/demo exclusion,
  original reviewed license bytes and existing seven-getter ABI checks passed.
- Independent read-only review found no remaining confirmed blocking issue in
  this scope, including accepted recording, clear fencing and old UI ownership.

Evidence levels are deliberately separate: adapter/action/presentation tests
execute production implementations and real core/controller logic with synthetic
identities and in-memory storage. Wiring and most reading tests are source
contracts; the Foundation Saver is actually executed. Android AtomicFile fault
behavior, actual WebView callbacks, Activity rotation, full Compose rendering,
TalkBack, emulator and physical-device operation remain NOT_RUN. No test here
substitutes for those device checks.

## Integration and remaining work

This source/report backup is eighteen files on the existing private development
branch, with unrelated files preserved. No new dependency, SkinKit, permissions,
version strategy, source-provider activation, Seen/Save protocol or release gate
changed. The final integrated candidate is separately built from 1,481 pinned
source inputs with versionCode 8 and the existing Debug signing identity; its
artifact/hash/old-APK comparison are recorded in the candidate report. It is not
installed or represented as a store release.

The prior bookmark report retains the remaining UI acceptance inventory. History
retention is now decided and this integration is implemented. Thumbnail admission
and reference-media licensing remain separate; deferred Vault/rules/payment
remain disabled. Native save/record/editor/playback, SAF lifecycle, responsive
visual/accessibility/performance and reference-layout acceptance still require
the documented device stage.
