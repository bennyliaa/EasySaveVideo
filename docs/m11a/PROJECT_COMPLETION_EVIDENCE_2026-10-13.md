# ESV completion evidence and minimum next steps

Checkpoint: 2026-10-13 UTC. Purpose: reconcile original Update1 milestones with
actual source, tests, publication and limited device observations. This is a
status document, not a release approval, new requirement, legal review or device run.
Original requirements and historical evidence are unchanged.

## 1. Source baseline and what “published” means

Latest verified source publication: [1f25214fcea79113b0278ac24ddc316e6910a9f0](https://github.com/bennyliaa/EasySaveVideo/commit/1f25214fcea79113b0278ac24ddc316e6910a9f0),
on `development/cloud-followup-20261013`, recording foreground notification typed display
and bounded existing-session language refresh. Its receipt verifies **41 changed blobs**,
including the complete final and first-attempt JUnit evidence ZIPs, with exact readback,
no deletions and no forced ref update. Parent is `4f22d43d023b7db6e3051613ed02c144bf3ad75b`
(live-original-page authentication navigation), following `55c51cd71763159b203e52ece44f09da8cab1507`
(Discovery reading anchor and small pre-anchor packaging reports). Main remains
`ea87963dee631a498774f648c9a1dabd9f665b90`; the repository remains private.
The scoped changed blobs are verified; whole remote-tree equality is not claimed.
Publication is not a main merge, installation or release acceptance.

The code8 Debug APK built from c794ef remains unchanged and **locally only**, with no
upload or install. It predates all three later published source slices and contains none
of those later changes. The editor destination-attempt acknowledgement repair is now
source/JVM tested: build2 passed 2,050 App tests, including 24 new tests, with no failures,
errors or skips. It awaits final evidence review and publication; native acceptance remains
NOT_RUN. This two-document refresh also awaits its own publication. Published
anchor, authentication-origin and notification work must not be relabeled as pending.

The following later publications supersede the former `2986ada` editor-default checkpoint:

| Published commit | Bounded scope | Publication receipt |
|---|---|---|
| [172fa3c](https://github.com/bennyliaa/EasySaveVideo/commit/172fa3c443431a302658a64e3f3dd35abae41dbb) | Wi-Fi-only source enforcement plus documentation; 79 changed files, 79 exact readbacks | `/workspace/shared/esv-wifi-publication-20261013/SUMMARY.json` |
| [93a67ba](https://github.com/bennyliaa/EasySaveVideo/commit/93a67ba5a4e07cb6c9b1b7d186c6145570db19e0) | BrowserScreen bilingual settings/origin/error copy; 29 changed files, 29 exact readbacks | `/workspace/shared/esv-browser-copy-publication-20261013/SUMMARY.json` |
| [82ccae9](https://github.com/bennyliaa/EasySaveVideo/commit/82ccae9dfb4d0c70ecca689a0a65aa259434603b) | Current storage/checkpoint/owner contracts and visible Shell navigation labels; 31 changed files, 31 exact readbacks | `/workspace/shared/esv-contract-shell-publication-20261013/SUMMARY.json` |
| [8fa0c4a](https://github.com/bennyliaa/EasySaveVideo/commit/8fa0c4a2b9720f2098e64ca2ff93a599d4856833) | Save display locale, semantic theme tokens and nine recovered original UI Markdown documents; 62 changed files, 62 exact readbacks | `/workspace/shared/esv-locale-tokens-originals-publication-20261013/SUMMARY.json` |
| [3176bcc](https://github.com/bennyliaa/EasySaveVideo/commit/3176bcc0be84325be5b278823995761f17049dd8) | Discovery body states and input clear; 48 changed files, 48 exact readbacks | `/workspace/shared/esv-body-clear-publication-20261013/SUMMARY.json` |
| [62b5f6d](https://github.com/bennyliaa/EasySaveVideo/commit/62b5f6d4076fe1ea4ec727ae4dcd87c9113697e3) | Authentication-waiting return to current Browser and 26-pair fixed destination copy; 33 changed files, 33 exact readbacks | `/workspace/shared/esv-auth-destination-copy-publication-20261013/SUMMARY.json` |
| [795b6bb](https://github.com/bennyliaa/EasySaveVideo/commit/795b6bb9dc0353be55682a53d402bf6773aed9e8) | Typed destination notices/copy status and Discovery inline/floating filter roles; 42 changed files, 42 exact readbacks | `/workspace/shared/esv-destination-filter-publication-20261013/SUMMARY.json` |
| [b0c5647](https://github.com/bennyliaa/EasySaveVideo/commit/b0c5647e6759dd212423647742e4923c8d0dce93) | Generated candidate display and typed denied-permission notices; 30 changed files, 30 exact readbacks | `/workspace/shared/esv-candidate-permission-publication-20261013/SUMMARY.json` |
| [ef5d6fc](https://github.com/bennyliaa/EasySaveVideo/commit/ef5d6fc35328e8881db26df3982c0e675c37dd4d) | Full cloud regression evidence only; 16 files/readbacks, no production change | `/workspace/shared/esv-full-ui-regression-publication-20261013/SUMMARY.json` |
| [c794ef8](https://github.com/bennyliaa/EasySaveVideo/commit/c794ef84344d638f20966a1cd05bb5b340ade7e0) | Complete Save runtime notice typed-display channel; 32 files/readbacks including evidence ZIP | `/workspace/shared/esv-save-notice-publication-20261013/SUMMARY.json` |
| [55c51cd](https://github.com/bennyliaa/EasySaveVideo/commit/55c51cd71763159b203e52ece44f09da8cab1507) | Discovery reading anchors, exact display-only restore provenance and small c794ef packaging reports; 47 files/readbacks, APK excluded | `/workspace/shared/esv-anchor-candidate-publication-20261013/SUMMARY.json` |
| [4f22d43](https://github.com/bennyliaa/EasySaveVideo/commit/4f22d43d023b7db6e3051613ed02c144bf3ad75b) | Explicit process-local, same-origin, still-live original-page selection; 31 files/readbacks | `/workspace/shared/esv-auth-origin-publication-20261013/SUMMARY.json` |
| [1f25214f](https://github.com/bennyliaa/EasySaveVideo/commit/1f25214fcea79113b0278ac24ddc316e6910a9f0) | Existing recording foreground-notification text channel and bounded language refresh; 41 files/readbacks | `/workspace/shared/esv-recording-notification-publication-20261013/SUMMARY.json` |

The three latest publication receipts and slice reports were read for this refresh;
earlier receipt-backed facts are retained as dated evidence. Receipt paths are local verification inputs,
not portable repository attachments; commit links and the in-repository slice reports
are the durable source references. Wi-Fi's original 77-file source/evidence freeze is
historical input to its 79-file publication group, not a still-pending publication.

Earlier increments include paging (`c83ebad`), native JS dialogs (`08ce295`), new-tab
draft transfer (`312007`), lightweight video hint (`63e929`), READY SPA/hash explicit
inspection (`dc2f421`) and editor-default compression (`2986ada`). Their reports remain
under `docs/ui/implementation/` and `docs/browser-js-dialogs/VERIFICATION.md`.
The shared source directory has no `.git` history and can contain later unshipped work;
do not infer that every current file belongs to the published checkpoint.

### Original UI material is recovered in part, not wholly missing

All nine original UI Markdown documents, `00_ESV_UI_DOCUMENT_INDEX.md` through
`08_ESV_UI_additional_technical_framework_policy_2026-10-11.md`, were recovered and
published under `docs/ui/` in `8fa0c4a`. The original Markdown handoff is locally
readable at `/workspace/shared/esv-ui-original-handoff-20261013/`; this shared source
snapshot does not itself contain all nine at their published repository paths.
The original PPT binary `EasySaveVideo_UI_Effect_Design_V1.0_2026-10-11.pptx` has not
been obtained or viewed. Its 34-page Markdown traceability matrix is available, but
is not the slide imagery and cannot establish pixel parity. Later API31, history-TTL,
payment and deferred-feature decisions still take precedence over old examples.

## 2. Evidence levels and exact current limits

Requirements describe desired behavior. Source-contract, JVM and host tests establish
only their checked conditions. Compilation/lint establish neither rendered pixels nor
native media correctness; publication readback establishes bytes, not installed behavior.

### Latest full cloud regression: b0c5647 only

Report: `docs/m11a/FULL_CLOUD_UI_REGRESSION_20261013.md`.
Evidence: `artifacts/regression/full-ui-20261013/VERIFICATION.json`.
This full run binds b0c5647 production bytes. It is not rerun evidence for the later
Save-notice, reading-anchor, authentication-origin or recording-notification source, nor
the separately source/JVM-tested editor destination acknowledgement repair. It is not a sum of overlapping slice totals:

- All **13 selected module test tasks actually reran with `--rerun`**. **307 fresh JUnit
  XML suites** registered **3,149 cases: 3,147 passed, 0 failures, 0 errors, 2 skipped**. App **1,886** and
  ui-theme **17** were both actually executed in this full run. The two verification-local
  cases need unavailable `ESV_OWNED_CORPUS` inputs; they are not passed acceptance.
- Debug/Release Kotlin compile and lint analysis/report tasks actually reran with
  `--rerun` and passed. Each variant's lint retains **60 warnings, 8 hints, 0 errors**.
  Runtime admission verified **90 exact artifacts**. These statements concern the
  selected tasks, not every dependency task in the Gradle graph: **101 actionable tasks,
  30 executed and 71 dependency tasks UP-TO-DATE**, terminal exit 0 in 3m16s.
  All 1,391 captured code/test/script/Gradle/third-party inputs stayed byte-identical;
  the independent host inventory also retained 1,839 non-build inputs unchanged
  during the host-gate run; evidence and ledger documents were intentionally updated afterwards.
  No production fix or weakened gate was used to obtain this regression result.
- Final compiled boundary check passed after the build outputs were stable. The current
  Save DB v8 host SQLite gate freshly passed **14 tests / 344 rollback fault boundaries**.
  It exercises extracted SQL plus a source-checked host transaction model; Android
  SQLiteOpenHelper, real published-media v1 backfill and native provider behavior are
  not established by this result.
- Production JavaScript checks passed **22 video-presence** and **11 save-detector**
  owned synthetic-DOM checks. The detector's missing-output-directory failure was
  retained; its successful rerun used an isolated output directory and byte-identical
  production/script inputs. This is not Android WebView, real-site or media acceptance.
- All **21 historical admission scripts** were rerun unchanged: **2 scoped passes,
  16 blocked by missing Git history, 2 blocked by missing historical evidence, and
  1 historical source-anchor failure**. The **14 older SQLite scripts** likewise
  reran unchanged: **2 scoped passes and 12 historical source-anchor failures**.
  The final boundary check is already one of the 21; do not count it again as a
  separate historical admission pass. Current-version success does not overwrite
  old gate failures or manufacture the missing Git/candidate evidence.
- The latest publication receipt has zero workflows/runs/checks/statuses. Empty combined
  `pending` is neither running nor passing CI. This local regression is not GitHub
  Actions green status, and the publisher itself ran no builds or device tests.
- APK assembly was NOT_RUN in that full regression; the later local-only c794ef Debug
  build/static result is recorded separately below. Device/emulator, original-PPT comparison,
  rendered IME/TalkBack/large-font behavior,
  real media/network/provider acceptance and physical energy measurements remain
  **NOT_RUN**. Original PPT bytes are still unavailable. Older OPPO and Windows failures
  remain separately open; there is no whole UI-B/UI-E/M9/M11 or release acceptance.
  The next bounded native checks are recorded, not executed, in
  `docs/m11a/UI_DEVICE_ACCEPTANCE_CHECKLIST_20261013.md`.

### UI slices and their dated verification numbers

These reports remain dated evidence; their totals overlap and must not be added to
one another or the full regression. Pre-publication wording in frozen slice reports
is superseded by the exact publication receipts above, not edited retroactively.

- `3176bcc`: input-clear build4 App **1,835** actually passed, 22 new tests, zero
  failures/errors/skips; 75 Gradle tasks, 19 executed/56 UP-TO-DATE. Debug/Release
  Kotlin and both lint tasks executed PASS, each 60 warnings/8 hints/0 errors;
  runtime 90. Evidence: `artifacts/ui/input-clear-20261013/VERIFICATION.json`.
  Its build2 SideEffect regression was fixed by restoring the original SideEffect;
  the existing test stayed unchanged and the failed log/XML are retained.
- `62b5f6d`: authentication/fixed-copy final build2 **208 App suites / 1,857 tests**,
  zero failures/errors/skips, 22 new tests (10 policy, 5 wiring, 7 destination).
  Dual Kotlin/lint executed PASS, each lint 60 warnings/8 hints/0 errors, runtime 90.
  Reports: `docs/m11a/AUTHENTICATION_BROWSER_NAVIGATION_20261013.md` and
  `docs/m11a/ORDINARY_DESTINATION_FIXED_COPY_20261013.md`;
  evidence: `artifacts/ui/auth-destination-copy-20261013/VERIFICATION.json`.
  It is published; the earlier pending-publication status is superseded. This earlier action
  returns only to the current Browser. The distinct bounded live-original-page selection was
  subsequently published in `4f22d43`; full login/recovery/resume remains unestablished.
- `795b6bb`: typed-destination App **1,864** and filter-role App **1,870** are separate
  slice runs, with 7 and 6 new tests respectively. The latter executed dual Kotlin/lint,
  retaining 60 warnings/8 hints/0 errors and runtime 90. Reports:
  `docs/m11a/DESTINATION_TYPED_DISPLAY_20261013.md` and
  `docs/ui/implementation/DISCOVERY_FILTER_PANEL_ROLE_20261013.md`.
  Destination dynamic notice/copy-status localization is now implemented and published:
  26 typed notice, 2 kind and 6 copy-state resource pairs supplement the earlier 26 fixed
  pairs. User-owned values and other Save runtime messages are outside that migration.
  Discovery's shared filter uses inline surface/card and floating elevated/sheet roles;
  its host remains Dialog, not a BottomSheet, with one shared movableContent owner.
- `b0c5647`: candidate/permission display App **1,886** actually passed, 16 new tests;
  dual Kotlin/lint executed PASS, each lint 60 warnings/8 hints/0 errors, runtime 90.
  See `docs/m11a/CANDIDATE_PERMISSION_DISPLAY_20261013.md`. Candidate numbering is
  locale-rendered; two website-denial notices are typed until current-locale display.
  Camera/microphone/location remain denied. Other Browser raw notices are not all migrated.
- Published `c794ef`: complete Save runtime notice display, App **1,905** actually passed,
  19 new tests, zero failure/error/skip. Both compilers/lints, runtime90 and seven compiled-boundary
  checks passed. Three consumers now resolve 78 groups in current Compose Locale; no service
  notification, storage/wire or recording/save behavior change. Other module tests were not rerun.
  See `docs/m11a/SAVE_RUNTIME_NOTICE_DISPLAY_20261013.md`.
- Published reading anchor `55c51cd`: App **1,953** actually passed, including **48 new
  tests**, zero failure/error/skip; dual compile/lint, each lint **60 warnings/8 hints/0
  errors**, runtime90 and seven boundary checks passed. Other module tests were not rerun.
  Stable result/offset and exact read-only restore provenance are source/JVM verified;
  actual Compose saved-state/navigation/drag and device restoration are NOT_RUN. No new
  APK contains this slice. See `docs/m11a/DISCOVERY_READING_ANCHOR_20261013.md` and
  `artifacts/ui/discovery-reading-anchor-20261013/VERIFICATION.json`.
- Published authentication-origin selection `4f22d43`: App **1,998** actually passed,
  including **45 new tests** (36 policy/concurrency and 9 wiring/resource), zero failures,
  errors or skips. Dual compile/lint actually executed, each lint **60 warnings/8 hints/0
  errors**; runtime90 and seven boundary checks passed. All 1,409 frozen inputs stayed
  unchanged. Other module tests were not rerun. Newly accepted Direct/Stream tasks can
  explicitly select only their process-local, same-origin, still-live original document;
  the ordinary Browser fallback remains separate. No login, URL loading/reload, View
  recreation, retry or task resume is performed. Recovered/cross-origin/expired/background-
  cleared associations fail closed. Native WebView/login/resume/Compose acceptance is
  NOT_RUN. The first run's new test-reader compilation failure and test-only repair are
  retained; the complete second forced run supplies the final result. See
  `docs/m11a/AUTHENTICATION_ORIGIN_NAVIGATION_20261013.md` and
  `artifacts/ui/authentication-origin-20261013/`.
- Published recording foreground notifications `1f25214f`: App **2,026** actually passed,
  including **28 new tests** (6 status/resource, 12 display/language and 10 source-wiring),
  zero failures/errors/skips. Dual compile/lint actually executed, each lint **60 warnings/
  8 hints/0 errors**; runtime90 and seven boundary checks passed. All 1,417 frozen inputs
  stayed unchanged. Other module tests were not rerun. Twelve typed status cases and the
  existing channel/title/action cover **15 actual display leaves**. The existing language
  owner drives display-only refresh of an already-promoted, matching, unfinished session;
  no capture/start/stop behavior or whole-App localization claim is added. The first run
  executed 2,026 tests with one newly authored assertion-scope failure; a test-only
  correction and complete rerun supplied the final pass. The failed log and all first-run
  JUnit XMLs remain preserved. Native first-frame language, active/paused continuity,
  channel/action/permission visibility and post-finish no-resurrection are NOT_RUN. See
  `docs/m11a/RECORDING_NOTIFICATION_LOCALIZATION_20261013.md` and
  `artifacts/ui/recording-notification-20261013/VERIFICATION.json`.
- Current editor destination-attempt acknowledgement repair: **source/JVM tested,
  awaiting final evidence review and publication**. Build2 terminated with exit 0 after
  **2,050 App tests / 24 new / 0 failures / 0 errors / 0 skips**. All ten requested tasks
  were forced and actually executed, including Debug/Release compile and lint; each lint
  retained **60 warnings/8 hints/0 errors**. Runtime admission reverified **90 artifacts**
  and the compiled-boundary checker passed **seven checks**. All **1,422 frozen inputs**
  remained unchanged. Other module tests were not rerun; this is not a full regression.
  Build1 terminated with exit 1 after 2,050 tests and one existing total-enum-occurrence
  assertion failure. Its assumption of 26 textual occurrences did not allow semantic
  reuse across the same 26 closed kinds. The independently reviewed test-only correction
  preserves 26 unique kinds, exhaustive enumeration/resource/localization coverage and
  exact reuse counts `LOCATION_UNAVAILABLE: 3` / `REMEMBERED_FOLDER_REQUIRED: 2`.
  All **226 first-attempt JUnit XMLs**, full failed log, census and input pin are preserved;
  the complete fresh build2 supplies the final result. No APK was built or installed.
  Actual Compose ASK dismissal, retry/editor interaction and lifecycle; native editing,
  media/provider behavior; emulator/device, accessibility, PPT/pixel and Windows fixture
  rerun remain **NOT_RUN**. Editor Back confirmation is outside the repair. Evidence:
  `artifacts/ui/editor-destination-ack-20261013/VERIFICATION.json`, `BUILD_ATTEMPTS.json`,
  `TEST_ASSERTION_CORRECTION.json` and `FIRST_ATTEMPT_JUNIT.zip` in that same directory.
  Report in preparation: `docs/m11a/EDITOR_DESTINATION_ACKNOWLEDGEMENT_20261013.md`.
- Separate local-only APK: code8 / 0.8.0-ui-dev, min31/target36, 45,988,354 bytes, source c794ef
  before anchor work. Existing signer matched; APK v2 and static integrity passed. No key,
  version or project configuration change; no upload/install/release claim. The unchanged global
  cleartext setting is disclosed. Local source hashes match before/after; one existing metadata
  POM differs from the remote tree only by CRLF/LF. See
  `docs/m11a/DEBUG_CANDIDATE_STATIC_ACCEPTANCE_20261013.md`.
- Theme **17** ran in the earlier semantic-token slice and again in the new full
  regression. It did **not** run in the intermediate input-clear, authentication,
  destination/filter, candidate/permission, Save-notice, reading-anchor, authentication-origin,
  recording-notification or editor destination acknowledgement slices; no historical run is relabeled.
  Earlier App totals remain Browser copy 1,747, current storage contracts 1,761,
  Shell labels 1,774, Save locale 1,791, semantic tokens 1,801 and Discovery body 1,813.
  Their executed/cached task details remain in their individual reports.
- `testReleaseUnitTest` does not exist in this build; the historical failed task-selection
  attempt ran no tests. The new run does not create a Release unit-test claim.

### Published Wi-Fi R6 evidence, retained as an earlier slice

`docs/ui/implementation/SAVE_WIFI_ONLY_20261013.md` and
`artifacts/ui/save-wifi-only-20261013/validation.json` record the source validation
published in `172fa3c`. R6 is no longer awaiting source publication.

- R6 completed 111 actionable tasks: 20 executed, 91 UP-TO-DATE. App 1,742 tests
  actually executed with zero failures/errors/skips.
- Core-model 6, core-domain 44, Discovery 307, Commons 247, SearXNG 25,
  verification-client 44, verification-local 34 and save 95 were UP-TO-DATE in R6;
  their earlier execution within that slice is separately recorded. Verification-local
  retains the same two unavailable private-corpus SKIP cases.
- Library 277, HLS 40, recording 127 and ui-theme 9 were UP-TO-DATE in R6 and
  not rerun earlier in that Wi-Fi slice. They are not fresh R6 passes; the later full
  regression has its own current-source execution evidence above.
- Debug/Release Kotlin and both lint tasks actually executed in R6; lint retained
  60 warnings/8 hints/0 errors. AndroidTest Kotlin compilation was UP-TO-DATE,
  not instrumented-test execution. Runtime admission verified 90 artifacts;
  authored/merged manifests retained the same eight permissions.
- Independent Release bytecode review covers 17 class hashes, same-request Android
  Network binding, preserved TLS/public-peer checks and no newly shared cross-task
  source/credential state. See
  `artifacts/ui/save-wifi-only-20261013/release-review/00-review-summary.txt`.
  This is compiled JVM class evidence, not packaged DEX, VPN or whole-app leakage proof.
- Boundary checks passed. Old stream admission still lacked historical candidate
  artifact-inspection JSON; old OS-power admission still lacked `.git` history.
  Neither unavailable gate becomes a pass because independent runtime/permission
  checks or newer source-contract checks passed.
- Device/network transitions, VPN routing, SharedPreferences restart and rendered
  accessibility remain NOT_RUN. Source publication does not close those gates.

## 3. User-facing feature map

| User-facing function | What exists | What still prevents full acceptance / smallest next step |
|---|---|---|
| Browser navigation, tabs, bookmarks | Real WebView integration and bounded bookmark persistence; new-tab input can populate an owned draft before Go; BrowserScreen settings/error copy is bilingual and explicit address/new-tab Clear is wired | Native keyboard/layout/rotation/lifecycle matrix incomplete. Do not mark bookmarks absent. See `docs/browser-bookmarks/VERIFICATION.md`. |
| Browser JS dialogs and video detection | Native guarded alert/confirm/prompt; bounded advisory video-element hint; full detection remains explicit; READY same-document revision invalidates stale candidates | Real WebView callback ordering needs device acceptance. Hint is not playback, media identity, rights, or automatic recording authority. Same-document credential forwarding is intentionally narrower than inspection. |
| Browser ordinary history | Policy, bounded codec, serial controller and private atomic store, 43 focused JVM cases | No runtime collection/list UI. Explicit retention decision needed: 200 recent distinct addresses with 30-day expiry, or without time expiry. No default choice is made. `docs/ui/implementation/BROWSER_HISTORY_FOUNDATION_20261012.md`. |
| Incognito and site permissions | Incognito guards exclude ordinary persisted session/history paths, but no user-facing incognito entry is implemented; current WebChromeClient explicitly denies camera/microphone and location, with typed localized denial notices | Shared CookieManager/storage is not isolated browsing. Permission selection/Android result correlation and granted-capture lifecycle are absent; do not call deny-only a completed permissions manager. `app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt`. |
| Discovery paging and results | Explicit one-shot next-page demand, bounded provider/local validation framework; truthful missing metadata; distinct empty/loading/waiting/error body presentation, explicit query Clear, shared inline/floating filter roles and published stable-result reading anchors with exact restore provenance (`55c51cd`) | Actual Compose saved-state/navigation/drag restoration remains NOT_RUN. Real image capability/source admission, Android decode/owner and viewport/UI binding missing. Current Discovery placeholder remains honest. `docs/ui/implementation/DISCOVERY_THUMBNAIL_FOUNDATION_20261012.md`. |
| Direct / Stream / Record | Real bounded save/capture pipelines, explicit user triggers, verified media publication and safe failure guidance; Wi-Fi-only save enforcement published in `172fa3c`, bounded live-original-page selection in `4f22d43`, and the existing recording foreground-notification text channel in `1f25214f` | Full native stream/codec/AV/background/recovery, live-WebView login/resume and notification continuity coverage pending. Published original-page selection performs no login, reload or task resume. A failed network save offers fresh explicit reinspection only; it does not auto-retry, broaden sources or silently start recording. |
| Save location, library and batch Files | App Library/ASK/album plus remembered ordinary SAF folder; batch export uses one selected folder and per-item verified results; fixed and typed dynamic destination display localized; originals retained for copies | Native provider/revocation/uncertain cleanup matrix remains partial. Old “choose batch Files behavior” note was resolved by later one-folder implementation. `docs/m5a4b/REQUIREMENTS_DECISION.md`; `docs/ui/implementation/BATCH_DIRECTORY_COPY_20261012.md`. |
| Editing and default compression | Trim/merge/crop/compress with existing entitlement gates; default applies once to new edits, never overwrites active/restored drafts | The editor destination-attempt acknowledgement repair passed 2,050 App tests (24 new) and awaits final evidence review/publication; native dismissal/retry acceptance is NOT_RUN and Editor Back confirmation is excluded. OPPO edit initialization failure remains unresolved. Default editor compression is not recording compression, successful encoding, or a paid unlock. `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md`. |
| Power/settings/notices | Real lifecycle OS battery/thermal/network observation and fail-closed policy; localized status; scrollable offline notices and responsive settings panes | Physical energy benefit, platform callback timing, full accessibility/large-font/phone/tablet acceptance absent. The old OS NOT_CONNECTED label is obsolete. `docs/m8b4/VERIFICATION.md`; `docs/ui/implementation/CLOUD_NOTICES_POWER_20261012.md`. |
| Diagnostics and sharing | Bounded typed initialization diagnostics and log-export implementation; no sensitive media/path/URL logging allowed | New diagnostics must be run on the failing device build before determining edit/share root cause. Existing failure enums alone do not prove codec failure or a repair. `docs/ui/implementation/INITIALIZATION_DIAGNOSTICS_CLOUD_20261012.md`; `docs/diagnostics-export/IMPLEMENTATION_AND_VALIDATION.md`. |
| Vault / dynamic rules / new local video import | Explicitly deferred; Vault/rules disabled or isolated | Do not reopen blocked reviews, substitute integrations or count these as implemented. Import is a deferred extension, not automatically an original requirement failure. `docs/ui/implementation/ESV_UI_FEATURE_INTEGRATION_LEDGER.md`; `AGENTS.md`. |
| Real payment and release | Store-neutral preparation and six channel identities | Payment choice, products, actual accounts/SDK/backend and channel acceptance unknown/unconfigured. Fresh release policy and actual release artifact/device gates still needed. `docs/m10b/CHANNEL_READINESS.md`. |
| Full visual design | Nine original UI Markdown documents recovered/published; visible Shell labels, locale-aware Save/candidate/destination display, semantic theme tokens, Discovery body/filter roles and input Clear implemented in bounded source slices | Original PPT binary still unavailable. Semantic tokens and localization remain partial; no complete slide/pixel parity, UI-E or accessibility acceptance. `docs/ui/implementation/ESV_UI_TOKEN_AND_COMPONENT_MAP.md`; the five dated slice reports listed below. |

Current original-UI repair reports (all read for this checkpoint):
`docs/ui/implementation/SHELL_VISIBLE_LABELS_20261013.md`,
`SAVE_DISPLAY_LOCALE_20261013.md`, `SEMANTIC_THEME_TOKENS_20261013.md`,
`DISCOVERY_BODY_STATE_20261013.md` and `INPUT_CLEAR_20261013.md`.
Their earlier “next work”/publication-pending statements are slice-time history where
superseded by the publication chain above, not instructions to repeat completed work.
Authentication-waiting return to the current Browser and 26-pair fixed destination
copy are published in `62b5f6d`. Typed destination notices/copy-state display and Discovery
filter roles are published in `795b6bb`; candidate labels and typed denied-permission
notices are published in `b0c5647`; the complete Save runtime notice channel is published
in `c794ef`. Reading anchors are published in `55c51cd`, explicit process-local same-origin
still-live original-page selection in `4f22d43`, and the current recording foreground-service
notification text channel in `1f25214f`. Full login/recovery/resume, other recording/runtime
owners' raw notices and whole-App localization remain incomplete/unverified; native acceptance
remains NOT_RUN. The editor destination acknowledgement slice is now source/JVM tested;
its final evidence review/publication and this ledger refresh's publication remain pending.

## 4. Older device evidence: useful, bounded, not overwritten

The delivery owner reports signed OPPO code8 / `0.8.0-ui-dev`, with bounded two-item
copy/move, recording/replay and nested-scroll/dialog correction checks accepted.
The same device still reported editor `STORAGE_UNAVAILABLE` and share
`INITIALIZATION_FAILED`. Known ANR/second-recording and Windows symlink/setup-guard
issues also remain open as retained by the later slice reports. None is cleared by cloud
tests or this documentation refresh. These are open failures, not a complete device pass.

Reported delivery reference: `delivery/ui-code8-debug-signed-c09c5db`, commit
`16fb2923b329c81ac01b6010d0e7c9861e605849`.
Reported signed APK SHA-256:
`1e12d0823b46f59ea893b6c7169ad6964ab07cc98ed0b954d4a550f1ce28f17d`.
These identities were supplied by the delivery owner; this documentation author did
not re-read or run that APK. Final signed-device report and diagnostics are retained
on the original Windows delivery environment and were not found in this cloud source
snapshot. No unavailable Windows file is presented as a verified local reference.

Locally readable corroboration is narrower:
`docs/ui/implementation/SETTINGS_OFFLINE_NOTICES_20261012.md` preserves intermediate
OPPO UI observations of two verified external items/move and the edit/share failures,
and explicitly says the final device result was absent at that checkpoint.
`docs/ui/implementation/CODE8_INITIALIZATION_INVESTIGATION_20261012.md` records an
unsigned/incompatible-signature code8 build input and pre-device investigation, not
acceptance of the later signed delivery. Do not confuse those two APK hashes.

Smallest next device step, when that environment is available and within its current
authorization: use the newer bounded diagnostic build with approved QA inputs, try
one editor entry and one share preparation, record fixed stage/category/numeric errno
only, stop at failure and preserve originals. Do not operate the handed-back OPPO
or infer that emulator authorization permits it. This refresh starts no device work.

## 5. Current repair and remaining functional source work

The current repair has bounded source/JVM evidence; the later items remain implementation
candidates, not passing results. Keep one build owner and review/freeze each slice before
combining it with other work.

Wi-Fi-only enforcement, BrowserScreen's bounded settings/origin/error localization,
Shell labels, Save display locale, the bounded semantic-token slice, Discovery body
states, explicit input Clear, current-Browser authentication return, fixed/typed
destination display, Discovery filter roles, candidate/denial display, Save runtime
notices, reading anchors, bounded live-original-page selection and recording foreground
notification localization have been published. Do not relist those completed source
scopes as new work; native and full-original-UI acceptance remains open. Prioritize the
concrete functional gaps below before additional copy-only migrations.

1. **Finish evidence review and publication of the editor destination-attempt acknowledgement repair.** Pre-repair
   read-only source review found `EditorScreen.kt:239` latching submission before `onSubmit(draft)`,
   while pre-repair `AndroidEditRuntime.kt:160` advanced `submissionRevision` only inside
   accepted `submitSelected`. The old destination call supplied only an accepted callback;
   cancel/reject/failure gave the editor no acknowledgement, leaving its attempt latched.
   The bounded repair is now **source/JVM tested: 2,050 App passes, 24 new tests**, plus
   dual compile/lint, runtime90 and seven boundary checks. Final evidence review and
   publication remain pending. The final review must retain cancellation/rejection/failure
   retry coverage and stale/duplicate acknowledgement fences, with admission, freshness,
   foreground and media ownership guards preserved. Actual Compose/native retry behavior
   remains NOT_RUN. Editor Back confirmation is expressly excluded. No root-cause claim
   is made about the separate OPPO `STORAGE_UNAVAILABLE` failure. Report in preparation:
   `docs/m11a/EDITOR_DESTINATION_ACKNOWLEDGEMENT_20261013.md`.
2. **Integrate the real owned-fixture Discovery thumbnail path under the original reuse policy.**
   Original UI `08_ESV_UI_additional_technical_framework_policy_2026-10-11.md` §§2/3/7
   requires existing media Reader/thumbnail capabilities and already-admitted mature
   image loaders first. Any new loader candidate must pass the existing exact-version,
   byte/license/dependency admission review before integration. This candidate is not
   authority to build a general-purpose image downloader/decoder/multilevel-cache
   framework or to bypass admission because an adapter would be smaller.
   Start from
   `discovery/src/main/kotlin/com/easysavevideo/discovery/thumbnail/DiscoveryThumbnailSession.kt`
   and
   `discovery/src/main/kotlin/com/easysavevideo/discovery/thumbnail/DiscoveryThumbnailCapability.kt`.
   Identify reusable admitted decoding/ownership and delivery capabilities, then use a
   bounded product adapter to connect page and visible-item events in
   `app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt`
   and `app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt` with owned
   synthetic fixtures. Add stale-page/background/late-delivery/unconfirmed-close/
   oversized-image tests. If no admitted capability fits, report the concrete gap and
   obtain the required reuse/admission decision before extending a generic framework.
   Do not invent image-read grants from metadata or activate arbitrary remote URLs.
   Production source admission remains a separate gate before real fetching.
3. **Implement bounded per-request webpage audio consent.** Current `SystemBrowserHost`
   deny-only callback is a concrete gap. A native SkinKit confirmation may grant only
   AUDIO_CAPTURE to the exact current same-origin HTTPS request, after independent Android
   RECORD_AUDIO authorization/result correlation. Keep camera/location denied; no remembered
   site grants. Wire actual host/UI/Activity lifecycle cancellation and isolated request codes;
   test old callbacks, duplicate results, navigation, background, cancellation and origin ABA.
   Never reuse recording consent's automatic MediaProjection path. Pending-request completion
   does not prove already-granted capture stops; resolve the active WebView teardown/reload
   behavior and review it before claiming a full permission lifecycle. No new permission or
   personal-data transmission is authorized merely by listing this task.
4. **Review only the remaining authentication-recovery cases.** The `4f22d43` publication
   already supplies explicit selection of a same-origin, exact still-live original document
   for a newly accepted task; ordinary Browser fallback remains available. Recovered tasks,
   background/process loss and cross-origin/CDN cases intentionally remain unavailable.
   Full login and task resume need separate source/credential, freshness, ownership and
   lifecycle review and native evidence. Do not repeat the completed action, infer a safe
   origin from media URLs, expand authority from persisted metadata, or promise resume success.
5. **Review remaining original UI coverage after the functional repairs.** Use the recovered
   nine Markdown originals and `ESV_UI_TOKEN_AND_COMPONENT_MAP.md` to select actual remaining
   component/state gaps. Published Browser/destination/candidate/denial/filter/Save-notice,
   reading-anchor, original-page-selection and recording foreground-notification scopes
   are not pending implementation targets. Other owners' or recording runtime notices,
   success/warning/selected/loading/scrim/system-bar tokens and all-component localization
   remain partial. Copy-only expansion is lower priority than a confirmed functional gap.
   Source coverage does not substitute for the missing PPT or rendered-device evidence.

Item 1 has a bounded source/JVM pass and awaits final evidence review/publication; native
acceptance remains NOT_RUN.
Items 2–5 remain bounded candidates requiring normal scope review. All preserve existing
source-admission, privacy, entitlement and lifecycle gates. They can produce source/test
progress without selecting history retention, activating billing/Vault, or installing
an APK. They do not cure the unresolved OPPO root cause by assumption.

## 6. Decisions and external evidence that really do gate completion

- **Ordinary browsing history retention:** obtain the existing narrow TTL choice before
  enabling collection. Then integrate accepted page-finish events, one process-owned serial
  writer, localized list/open/remove/confirmed-clear UI and any selected expiry timer. Add
  rotation/clear-versus-late-write/current-row replay tests; avoid only adding another codec.
- **Incognito entry and isolation:** the user-facing entry is not implemented and current
  shared CookieManager is insufficient. Specify and review
  a supported profile/process lifetime and clearing model before promising independent
  cookie/storage isolation; ordinary/incognito persistence tests alone cannot prove it.
- **OPPO edit/share failures:** new stage/category diagnostics from a bounded failing attempt
  are needed. A Windows filesystem fixture setup failure and host-safe rerun do not prove
  an Android root cause. Preserve each environment's failed/not-run status.
- **Thumbnail production source access:** require a real admitted read capability and
  bounded supported decoder before replacing production placeholders with fetched images.
- **Vault/rules/import:** deferred; do not silently prioritize or reopen them.
- **Commercial/release:** payment and actual channel/account readiness unresolved. Recheck
  then-current official requirements at release time; the 2026-10-07 market checklist is
  preparation, not a new legal verification performed by this document.
- **UI/device:** use the nine recovered original Markdown documents; obtain the original
  PPT binary/imagery, then perform the finite phone/tablet, large-font/accessibility/
  lifecycle/native-media matrix before full UI/M9/M11 acceptance. Markdown recovery
  does not supply screenshot comparison or close UI-E.

## 7. Documentation refresh and publication boundary

This refresh changes only this overview and MILESTONE_STATUS, reconciling the published
chain through `1f25214f`: reading anchors `55c51cd`, bounded live-original-page selection
`4f22d43` and recording foreground notifications `1f25214f` follow Save notices `c794ef`.
Each source/test/build stage retains its own exact evidence; totals are not added. The
last full 13-module regression remains bound only to b0c5647, with the original two
corpus skips, historical gate failures and OPPO/Windows issues preserved.

This two-ledger refresh and the new editor destination-attempt acknowledgement repair
await final evidence review/publication. The repair's build2 passed 2,050 App tests with
24 new tests, zero failures/errors/skips, dual compile/lint, runtime90 and seven boundary
checks; 1,422 frozen inputs remained unchanged. Its failed first attempt and reviewed
test-only assertion correction are retained. This is source/JVM evidence only, with no
other module rerun or native acceptance. Editor Back confirmation remains outside it.
The older small packaging reports were already published with `55c51cd`; the unchanged local 46 MB
c794ef APK is excluded, uninstalled and unuploaded. Original Markdown recovery remains
distinct from still-unavailable PPT imagery and the separate local handoff directory.
This documentation refresh creates no additional build/test/device result and does not
retroactively change old evidence, original requirements, package/version, device state
or published commits.
