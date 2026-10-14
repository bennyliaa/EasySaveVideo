# Browser bookmark reading and feedback

Date: 2026-10-14 UTC. Base: development/cloud-followup-20261013 at
821dc8ed4673407ee979761e2e7d133df87e8061. Source/cloud validation only.

## Requirement and observed gaps

Original product PR25 and UI03 UI-R04/UI-R06/UI-R08/UI-R13, UI04 T02/T05/T07,
and UI05 sections 7/10 require bookmark access, truthful action feedback,
responsive reading continuity, and lightweight state restoration.
The two bookmark hosts previously created separate scrolling state, while
operation feedback was outside the modal and could be hidden behind it.
Panel labels were Chinese literals rather than existing locale resources.

## Implemented behavior

- Browser owns one standard rememberLazyListState across sidebar/dialog hosts.
  Its Foundation saver keeps index and offset only; titles, URLs and rows are
  not added to saved instance state. The bookmark panel has no folder/filter
  context, so no existing per-folder reset policy is changed.
- A bounded, independently scrollable panel header contains actual controller
  feedback, status and explicit reread before the longer privacy explanation.
  Long rows no longer push operation feedback behind their list scroll position.
- Loading, slow and unavailable remain distinct from an empty successful read.
  Dismissing an unavailable notice does not hide persistent unavailable status.
  Reread is explicit; no background retry or optimistic success was added.
- Open/remove remain enabled only when the existing controller is ready and
  idle. Durable add/remove, duplicate and uncertain-write semantics are unchanged.
- Eighteen English/Simplified-Chinese resources cover panel labels, feedback,
  privacy copy and row-specific accessible action descriptions. Action buttons
  wrap using the existing SkinKit-backed controls; URL text retains LTR layout.

## Validation

Final strict-offline 13-module aggregate and Debug/Release Kotlin compilation,
Debug/Release lint completed successfully. 3,623 registered tests: 3,621 passed,
2 pre-existing private-corpus skips, zero failures/errors; app 2,335 passed.
Unchanged module tasks can be UP-TO-DATE; not every test was freshly executed.
Twelve new tests cover actual controller durable/failed/slow/reread transitions,
production presentation mapping, real Foundation saver round trips, bilingual
resource argument parity and source ownership/wiring. The bookmarks scoped run
passed 88 tests. Earlier compile errors in the intermediate header/saver code
and one incorrect aggregate task selector were corrected before this final run.

Independent read-only review found no blocking defect. Its low-cost feedback
ordering recommendation was applied before the final aggregate. Both lint
variants have zero Error/Fatal: Debug 59 warnings/8 hints, Release 60/8.
Each has one new PluralsCandidate warning for the bounded bookmark count heading;
it is non-blocking and is not claimed fixed. Existing warnings remain.
No dependencies, SkinKit, version, Seen/Save protocol, or disabled feature gates
changed. The source backup includes eight implementation/test/resource/build
files plus this report, preserving all other repository content.

## Remaining implementation/acceptance inventory

- Browser ordinary history runtime/UI: approved for the most recent 200 entries
  and 30-day TTL with manual clear; implemented next as a separate tested slice.
  Existing isolated controller/storage foundation alone is not full integration.
- Thumbnail loader/source rights, reference imagery rights and deferred billing,
  Vault and dynamic-rule gates remain separately constrained; no new admission
  or feature activation is implied by this bookmark slice.
- Existing Task Center and Editor already bind their documented core actions;
  this source review found no additional bounded missing action to invent.
- Native save/verification/library/playback, recording/editor native output,
  SAF destination/export interruptions, real window changes/rotation, 200% fonts,
  short landscape, TalkBack, performance and reference-layout device acceptance
  remain NOT_RUN. Source contracts and Foundation saver tests do not mount or
  render a complete Compose window and cannot guarantee first-screen visibility.

No user device or real browsing data was touched. No APK was installed. The
subsequent integrated Debug candidate will retain code8 and is not store release.
