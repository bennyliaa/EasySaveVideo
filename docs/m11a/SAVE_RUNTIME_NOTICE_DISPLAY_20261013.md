# Save runtime notice: complete typed display channel

2026-10-13. Publication parent `ef5d6fc35328e8881db26df3982c0e675c37dd4d`;
production baseline `b0c5647e6759dd212423647742e4923c8d0dce93`.
Scope is the process-local `AndroidSaveRuntime.notice` channel and its three existing UI displays.
Original UI 01 §7 and 02 UI-B/UI-D require bilingual resources, current Locale formatting and
continuous task behavior across appearance/language changes. This slice implements their display
requirements for this channel only; it does not complete all App internationalization.

## Verification

App **1,905 tests actually executed**, including **19 new tests** (6 pure payload, 8 resource/XML and 5 real-source wiring contracts); zero failures, errors or skips. Both Debug/Release Kotlin compilers and each variant’s lint analysis/report/entry tasks were forced and passed. Each lint retains **60 warnings, 8 hints, 0 errors**. Runtime admission reverified **90 exact artifacts**. The unchanged compiled-boundary script ran after compilation and passed its seven reported checks. All ten requested tasks actually executed. All **1,399 frozen inputs remained unchanged** during the run; 1,386 prior inputs outside the five scoped modifications remain byte-identical. Other module tests were not rerun in this slice; prior full thirteen-module regression remains bound to b0c5647. These are JVM/source checks, not device acceptance.

Evidence: `artifacts/ui/save-runtime-notice-20261013/`. Full App XMLs, both lint reports and build log are retained in COMPLETE_APP_EVIDENCE.zip.

## Complete migration and exact semantic grouping

The runtime still has 49 notice assignments: 44 emissions and five explicit clears, plus initial
null. Its 13 posted emission sites and 31 existing main-entry/callback emission sites are unchanged.
The reversible substitution evidence recovers the entire baseline runtime byte-for-byte. The
full original emission inventory and exact old/new message map are included in the evidence.

| Family | Emission sites | Safe payload | Display groups |
|---|---:|---|---:|
| Application fixed copy | 36 | `SaveRuntimeFixedNotice` enum | 34 |
| Network admission | 1 branching site | two additional fixed enum values | 2 |
| Task capacity | 1 | captured `limits.maxJournalEntries` Int | 1 |
| Detector outcome | 1 branching site | existing `PageSaveDetection.Reason` enum | 6 |
| Classified failure | 5 | display code normalized from `SaveError` | 35 |

All 45 domain errors retain their original 35 Chinese text-equivalence groups, including an
explicit shared INVALID_REQUEST/UNKNOWN_TECHNICAL_ERROR fallback. Equal old strings produce equal
typed values. Detailed failure copy remains distinct from the coarser task-card failure mapper.
The network admission WIFI_REQUIRED message and detailed error WIFI_REQUIRED message intentionally
remain separate because the old Chinese wording differs. The paired resource files add 75 strings
and one capacity plural; two existing network resources are reused, for 78 display groups total.

## Parameters, privacy, persistence and threading

- Fixed and failure values carry only display enums; detector notices retain only the existing
  reason enum, never a detector result, candidate or selection.
- The only variable parameter is `maxJournalEntries`, bounded by existing SaveLimits to 1..1000
  (default 100). It is configuration, not an active-task count or user content. The renderer carries
  it unchanged and uses current-configuration integer formatting. The token redacts it in toString.
- No URL, page title, filename, path, task/request ID, cookie, credential, raw exception, diagnostic
  text, Context, callback or runtime authority enters a notice. No raw-string fallback exists.
- `SaveErrors.classify` remains at the same immediate or posted boundary as before. Cleanup,
  lease revocation, generation guards, execution state and original action order are unchanged.
- `networkPolicy.initialized` is still read inside the original main.post at delivery time. The
  caught exception, showNotice check, admission call and false return are unchanged.
- The five existing clear locations remain unchanged; no clearing on render or locale change,
  new retry, automatic resume, login, page navigation, detection or permission action is added.
- No schema, codec, provider wire, stored record/error enum, Worker or notification contract is
  changed. Notice is only in-memory Compose state. SaveRecord.error serialization is untouched.

## Three real display consumers

1. SaveTaskScreen resolves the token with current Composition resources and retains the old error
   style and explicit dismiss callback.
2. MainActivity resolves the BrowserScreen saveNotice argument below the existing EsvLocaleHost;
   BrowserScreen keeps its String API and all browser permissions and notice ownership rules.
3. MainActivity resolves only RecordingScreen's Save fallback; recordedEditorNotice still wins.
   RecordingScreen keeps its String API and EsvDiagnosticNotice disclosure. Existing
   remember(original) collapses details when resolved text changes, including a language change.

Renderer uses stringResource/pluralStringResource and the existing LocalConfiguration-based
integer formatter. It stores no localized text, performs no commands and introduces no locale
provider. API31/32 override and API33+ platform language behavior remain owned by EsvLocaleHost.

## Explicit exclusions and pending device acceptance

Native RecordingForegroundService notifications and AndroidSaveRuntime's recording-service labels
remain byte-identical. Recording consent, segment recording, recorded-editor/region setup and
other owners' runtime notices are outside this channel. This is not a claim of full App localization.

All device, emulator, APK assembly/install, actual WebView/native media, Compose interaction,
TalkBack, 200% text, RTL and PPT/pixel validation remain NOT_RUN. Original MD recovery does not
supply PPT visual evidence. Previously recorded OPPO edit STORAGE_UNAVAILABLE, share
INITIALIZATION_FAILED, ANR/second-recording and Windows symlink setup/guard gaps remain open.

Pending focused checks, using owned fixtures only:
- Display a retained Save notice in tasks, browser and recording; change English/Chinese/system
  language on API31/32 and API33+; verify same task/state and no command replay.
- Check all long failure/recovery messages at 200% in compact/expanded windows, portrait/landscape;
  scrolling and explicit dismissal remain reachable with accessible controls.
- Verify recording details initially collapsed, explicit show/hide works and changed-language text
  follows existing collapse behavior without bypassing the disclosure.
- Verify editor-owned recording notice precedence and network admission's two distinct messages.
- Exercise repeated dismiss, a newer notice replacing an older one, and navigation/Activity
  recreation with active save/recording; do not infer these device behaviors from source tests.
