# ESV completion evidence and minimum next steps

Checkpoint: 2026-10-13 UTC. Purpose: reconcile original Update1 milestones with
actual source, tests, publication and limited device observations. This is a
status document, not a release approval, new requirement, legal review or device run.
Original requirements and historical evidence are unchanged.

## 1. Source baseline and what “published” means

Latest verified source publication: [3176bcc0be84325be5b278823995761f17049dd8](https://github.com/bennyliaa/EasySaveVideo/commit/3176bcc0be84325be5b278823995761f17049dd8),
on `development/cloud-followup-20261013`, combining Discovery body-state presentation
and explicit Browser/new-tab/Discovery input-clear actions. The publication receipt
records 48 changed files, all 48 blobs read back with exact byte equality, no deletions
and no forced ref update. Parent: `8fa0c4a2b9720f2098e64ca2ff93a599d4856833`.
Main remained `ea87963dee631a498774f648c9a1dabd9f665b90`; the repository remained private.
This is source publication, not a main merge, new APK, installation or release acceptance.

The following later publications supersede the former `2986ada` editor-default checkpoint:

| Published commit | Bounded scope | Publication receipt |
|---|---|---|
| [172fa3c](https://github.com/bennyliaa/EasySaveVideo/commit/172fa3c443431a302658a64e3f3dd35abae41dbb) | Wi-Fi-only source enforcement plus documentation; 79 changed files, 79 exact readbacks | `/workspace/shared/esv-wifi-publication-20261013/SUMMARY.json` |
| [93a67ba](https://github.com/bennyliaa/EasySaveVideo/commit/93a67ba5a4e07cb6c9b1b7d186c6145570db19e0) | BrowserScreen bilingual settings/origin/error copy; 29 changed files, 29 exact readbacks | `/workspace/shared/esv-browser-copy-publication-20261013/SUMMARY.json` |
| [82ccae9](https://github.com/bennyliaa/EasySaveVideo/commit/82ccae9dfb4d0c70ecca689a0a65aa259434603b) | Current storage/checkpoint/owner contracts and visible Shell navigation labels; 31 changed files, 31 exact readbacks | `/workspace/shared/esv-contract-shell-publication-20261013/SUMMARY.json` |
| [8fa0c4a](https://github.com/bennyliaa/EasySaveVideo/commit/8fa0c4a2b9720f2098e64ca2ff93a599d4856833) | Save display locale, semantic theme tokens and nine recovered original UI Markdown documents; 62 changed files, 62 exact readbacks | `/workspace/shared/esv-locale-tokens-originals-publication-20261013/SUMMARY.json` |
| [3176bcc](https://github.com/bennyliaa/EasySaveVideo/commit/3176bcc0be84325be5b278823995761f17049dd8) | Discovery body states and input clear; 48 changed files, 48 exact readbacks | `/workspace/shared/esv-body-clear-publication-20261013/SUMMARY.json` |

All five receipts were read for this refresh. Receipt paths are local verification inputs,
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

- Requirements describe desired behavior. Source-contract and pure JVM tests establish
  their checked conditions; compilation/lint establish neither pixels nor native media
  correctness. Publication readback establishes bytes, not installed behavior.
- The final validation included in published `3176bcc` is the input-clear slice:
  `artifacts/ui/input-clear-20261013/VERIFICATION.json`, with census
  `artifacts/ui/input-clear-20261013/JVM_CENSUS.json` and final `build4.log`.
  App **1,835 tests actually executed PASS**, including 22 new tests, with zero
  failures/errors/skips. Final build4 completed 75 tasks: 19 executed, 56 UP-TO-DATE.
  Debug/Release production Kotlin and both lint tasks actually executed PASS;
  each lint reports **0 errors, 60 warnings and 8 hints**. Runtime admission passed
  for **90 exact reviewed artifacts**. This is not a fresh execution of every module.
- **ui-theme 17 tests actually ran in the earlier semantic-token slice only**:
  `artifacts/ui/semantic-theme-tokens-20261013/VERIFICATION.json` and `JVM_CENSUS.json`.
  That slice ran App 1,801 and theme 17. Do not add theme 17 to App 1,835 at
  publication or the later App 1,857 source run. Theme was not rerun in either later
  slice, and no all-module final-run total is claimed.
- Earlier App-only slice totals identify their own inputs: Browser copy 1,747,
  current storage contracts 1,761, Shell labels 1,774, Save locale 1,791,
  semantic tokens 1,801, Discovery body 1,813. These overlap; they are not additive.
  Their individual compile/UP-TO-DATE distinctions remain in the original reports.
- Current storage contracts also recorded 14 host SQLite tests and 344 host-model
  rollback locations. See `docs/verification/CURRENT_STORAGE_CONTRACTS_20261013.md`.
  This new gate does not make old version/source-anchor gates pass and does not prove
  Android migration, native descriptor or provider behavior. Historical missing Git/
  snapshot evidence remains unavailable; old failures and failure logs are retained.
- Input-clear build2 caught an unintended SideEffect replacement in the existing
  `BrowserVideoPresenceContractTest`. The original SideEffect was restored and the
  original test stayed unchanged; the failed log/XML remain in slice evidence.
  The final build4 pass supersedes that failed attempt only for its checked source.
- Historical editor-default and Wi-Fi evidence remain separately dated. Verification-local
  recorded 34 cases including 2 SKIP for unavailable frozen-private/authored-mutation
  corpus inputs. Those skips are not newly executed acceptance. `testReleaseUnitTest`
  does not exist in this build; the old failed task-selection attempt ran no tests.
- The latest publication receipt records zero workflow files/runs/checks/statuses.
  Empty combined status `pending` is neither running nor passing CI. Recorded cloud
  validation is not GitHub Actions green status; publication itself ran no builds/tests.
- APK assembly/install, emulator/phone, rendered Compose/IME/TalkBack/large-font
  behavior, PPT/screenshots and native media acceptance for the latest slices are
  **NOT_RUN**. Historical package/device evidence must stay separately attributed.
  No physical battery saving or full UI-B/UI-E/M9/M11 completion is established.

### Newer source verification, not yet published

Status: **SOURCE_VERIFIED_PENDING_PUBLICATION** for the authentication-waiting task
return-to-current-Browser action and fixed destination display copy. This source group
follows `3176bcc`; no newer remote commit is asserted. Its final build2 actually ran
**208 App suites / 1,857 tests**, zero failures/errors/skips, including 22 new tests:
10 authentication-policy, 5 wiring and 7 destination-copy tests. Debug/Release Kotlin
and both lint tasks actually executed PASS; each lint retains 60 warnings/8 hints/
0 errors. Runtime admission verified 90 exact artifacts. Theme was not rerun by this
group. These totals
supersede older App execution only for the new verified source; they do not replace
`3176bcc`'s historical 1,835-test publication evidence or imply a new publication.

Reports: `docs/m11a/AUTHENTICATION_BROWSER_NAVIGATION_20261013.md` and
`docs/m11a/ORDINARY_DESTINATION_FIXED_COPY_20261013.md`.
Evidence: `artifacts/ui/auth-destination-copy-20261013/VERIFICATION.json`.
Authentication waiting offers only a return to the current Browser; this is not the
complete original `OpenOriginForLogin` flow. Destination localization covers only
26 paired fixed English/Simplified Chinese strings; runtime-generated dynamic notices
remain deferred. APK/device/emulator/native-flow/PPT comparison remains NOT_RUN;
the original PPT binary is still unavailable. OPPO edit/share failures remain open.

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
  not rerun earlier in that Wi-Fi slice. They are not fresh R6 or latest-source passes.
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
| Incognito and site permissions | Incognito guards exclude ordinary persisted session/history paths, but no user-facing incognito entry is implemented; current WebChromeClient explicitly denies camera/microphone and location | Shared CookieManager/storage is not isolated browsing. Permission selection/Android result correlation and granted-capture lifecycle are absent; do not call deny-only a completed permissions manager. `app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt`. |
| Discovery paging and results | Explicit one-shot next-page demand, bounded provider/local validation framework; truthful missing metadata; distinct empty/loading/waiting/error body presentation and explicit query Clear | Real image capability/source admission, Android decode/owner and viewport/UI binding missing. Current Discovery placeholder remains honest. `docs/ui/implementation/DISCOVERY_THUMBNAIL_FOUNDATION_20261012.md`. |
| Direct / Stream / Record | Real bounded save/capture pipelines, explicit user triggers, verified media publication and safe failure guidance; Wi-Fi-only save enforcement passed R6 source verification and was published in `172fa3c` | Full native stream/codec/AV/background/recovery coverage pending. A failed network save offers fresh explicit reinspection only; it does not auto-retry, broaden sources or silently start recording. |
| Save location, library and batch Files | App Library/ASK/album plus remembered ordinary SAF folder; batch export uses one selected folder and per-item verified results; originals retained for copies | Native provider/revocation/uncertain cleanup matrix remains partial. Old “choose batch Files behavior” note was resolved by later one-folder implementation. `docs/m5a4b/REQUIREMENTS_DECISION.md`; `docs/ui/implementation/BATCH_DIRECTORY_COPY_20261012.md`. |
| Editing and default compression | Trim/merge/crop/compress with existing entitlement gates; default applies once to new edits, never overwrites active/restored drafts | OPPO edit initialization failure unresolved. Default editor compression is not recording compression, successful encoding, or a paid unlock. `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md`. |
| Power/settings/notices | Real lifecycle OS battery/thermal/network observation and fail-closed policy; localized status; scrollable offline notices and responsive settings panes | Physical energy benefit, platform callback timing, full accessibility/large-font/phone/tablet acceptance absent. The old OS NOT_CONNECTED label is obsolete. `docs/m8b4/VERIFICATION.md`; `docs/ui/implementation/CLOUD_NOTICES_POWER_20261012.md`. |
| Diagnostics and sharing | Bounded typed initialization diagnostics and log-export implementation; no sensitive media/path/URL logging allowed | New diagnostics must be run on the failing device build before determining edit/share root cause. Existing failure enums alone do not prove codec failure or a repair. `docs/ui/implementation/INITIALIZATION_DIAGNOSTICS_CLOUD_20261012.md`; `docs/diagnostics-export/IMPLEMENTATION_AND_VALIDATION.md`. |
| Vault / dynamic rules / new local video import | Explicitly deferred; Vault/rules disabled or isolated | Do not reopen blocked reviews, substitute integrations or count these as implemented. Import is a deferred extension, not automatically an original requirement failure. `docs/ui/implementation/ESV_UI_FEATURE_INTEGRATION_LEDGER.md`; `AGENTS.md`. |
| Real payment and release | Store-neutral preparation and six channel identities | Payment choice, products, actual accounts/SDK/backend and channel acceptance unknown/unconfigured. Fresh release policy and actual release artifact/device gates still needed. `docs/m10b/CHANNEL_READINESS.md`. |
| Full visual design | Nine original UI Markdown documents recovered/published; visible Shell labels, locale-aware Save display, semantic theme tokens, Discovery body states and input Clear implemented in bounded source slices | Original PPT binary still unavailable. Semantic tokens and localization remain partial; no complete slide/pixel parity, UI-E or accessibility acceptance. `docs/ui/implementation/ESV_UI_TOKEN_AND_COMPONENT_MAP.md`; the five dated slice reports listed below. |

Current original-UI repair reports (all read for this checkpoint):
`docs/ui/implementation/SHELL_VISIBLE_LABELS_20261013.md`,
`SAVE_DISPLAY_LOCALE_20261013.md`, `SEMANTIC_THEME_TOKENS_20261013.md`,
`DISCOVERY_BODY_STATE_20261013.md` and `INPUT_CLEAR_20261013.md`.
Their earlier “next work”/publication-pending statements are slice-time history where
superseded by the publication chain above, not instructions to repeat completed work.
Authentication-waiting task return to the current Browser and `OrdinaryDestinationUi`
26-pair fixed-copy localization are SOURCE_VERIFIED_PENDING_PUBLICATION, with exact
reports and final build2 evidence above. Full `OpenOriginForLogin`, dynamic runtime
notice localization and native acceptance remain outside this verified group.

## 4. Older device evidence: useful, bounded, not overwritten

The delivery owner reports signed OPPO code8 / `0.8.0-ui-dev`, with bounded two-item
copy/move, recording/replay and nested-scroll/dialog correction checks accepted.
The same device still reported editor `STORAGE_UNAVAILABLE` and share
`INITIALIZATION_FAILED`. These are open failures, not a complete device pass.

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

## 5. Concrete remaining source work that need not wait for a product decision or installation

These are bounded implementation candidates, not claims that they already passed.
Keep one build owner and review/freeze each slice before combining it with other work.

Wi-Fi-only enforcement, BrowserScreen's bounded settings/origin/error localization,
Shell labels, Save display locale, the bounded semantic-token slice, Discovery body
states and explicit input Clear have been published. Do not relist those completed
source scopes as new work; their device and full-original-UI acceptance remains open.

1. **Integrate the real owned-fixture Discovery thumbnail path under the original reuse policy.**
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
2. **Implement bounded per-request webpage audio consent.** Current `SystemBrowserHost`
   deny-only callback is a concrete gap. A native SkinKit confirmation may grant only
   AUDIO_CAPTURE to the exact current same-origin HTTPS request, after independent Android
   RECORD_AUDIO authorization/result correlation. Keep camera/location denied; no remembered
   site grants. Wire actual host/UI/Activity lifecycle cancellation and isolated request codes;
   test old callbacks, duplicate results, navigation, background, cancellation and origin ABA.
   Never reuse recording consent's automatic MediaProjection path. Pending-request completion
   does not prove already-granted capture stops; resolve the active WebView teardown/reload
   behavior and review it before claiming a full permission lifecycle. No new permission or
   personal-data transmission is authorized merely by listing this task.
3. **Publish the verified authentication-waiting and fixed destination-copy group.**
   Both bounded source repairs are SOURCE_VERIFIED_PENDING_PUBLICATION, with App
   1,857 actual tests and final build2 evidence above. Do not repeat their implementation
   or label the group shipped before exact remote publication/readback is confirmed.
   The new action returns to the current Browser only, not full `OpenOriginForLogin`;
   the 26 paired fixed destination strings do not translate dynamic runtime notices.
   Keep those remaining functional/localization and device limits explicit.
4. **Review remaining original UI coverage after those bounded repairs.** Use the recovered
   nine Markdown originals and `ESV_UI_TOKEN_AND_COMPONENT_MAP.md` to select remaining
   semantic-state/component coverage or other app-owned locale gaps. BrowserScreen's
   already-published copy is not a pending target. Full app localization, success/warning/
   selected/loading/scrim/system-bar tokens and all-component acceptance remain partial.
   Source coverage does not substitute for the missing PPT or rendered-device evidence.

Items 1–4 require normal scope review and must preserve existing source-admission,
privacy, entitlement and lifecycle gates. They can produce source/test progress without
selecting history retention, activating billing/Vault, or installing an APK. They do
not cure the unresolved OPPO root cause by assumption.

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

## 7. Documentation-only validation and publication boundary

Only this overview and `docs/m11a/MILESTONE_STATUS.md` are changed by this refresh.
No production source, Gradle configuration, old evidence, original requirements,
package/version, device state or Git remote is changed. The five publication receipts,
five requested UI-slice reports and final input-clear evidence were read; the new
authentication/destination reports and final build2 evidence were also read and
checked. Original Markdown handoff availability was checked separately from its
published repository paths.
Unavailable device/PPT artifacts are explicitly marked unavailable, not linked as local proof.

These two edited documents are pending the coordinating owner's combined review and
verification. This refresh creates no separate freeze and performs no publication,
Gradle, APK or device task. Any later source slice must supply its own exact validation
and publication evidence before this checkpoint can be advanced; shared working-tree
changes alone do not establish that it passed or shipped.
