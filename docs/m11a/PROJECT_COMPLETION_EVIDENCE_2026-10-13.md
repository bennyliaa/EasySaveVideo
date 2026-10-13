# ESV completion evidence and minimum next steps

Checkpoint: 2026-10-13 UTC. Purpose: reconcile original Update1 milestones with
actual source, tests, publication and limited device observations. This is a
status document, not a release approval, new requirement, legal review or device run.
Original requirements and historical evidence are unchanged.

## 1. Source baseline and what “published” means

Latest verified source publication: [2986ada82ff4f0843eced900a90249d69f876671](https://github.com/bennyliaa/EasySaveVideo/commit/2986ada82ff4f0843eced900a90249d69f876671),
on `development/cloud-followup-20261013`, default compression for new editor drafts.
The local publication receipt reports exactly 26 changed files, exact diff, complete
blob readback and passing privacy scan. Its parent is `dc2f421891c558ad2921eaa0882e89d4249cff79`.
The receipt's main branch remained `ea87963dee631a498774f648c9a1dabd9f665b90`.
This does not mean main was merged, an APK rebuilt, or installed code updated.

Read-only publisher evidence exists outside the source snapshot at
`/workspace/shared/esv-compression-default-publication-20261013/SUMMARY.json`.
The corresponding frozen manifest is
`/workspace/shared/esv-compression-default-frozen-20261013/PUBLICATION_MANIFEST.json`.
These are local verification inputs, not portable repository attachments; the commit
link and in-repository reports are the durable source references.

Earlier reported published increments, in order, include paging (`c83ebad`), native
JS dialogs (`08ce295`), new-tab draft transfer (`312007`), lightweight video hint
(`63e929`) and READY SPA/hash explicit inspection (`dc2f421`). Their scope reports:

- `docs/ui/implementation/DISCOVERY_PAGE_DEMAND_20261013.md`
- `docs/browser-js-dialogs/VERIFICATION.md`
- `docs/ui/implementation/NEW_TAB_SEARCH_INPUT_20261013.md`
- `docs/ui/implementation/VIDEO_PRESENCE_HINT_20261013.md`
- `docs/ui/implementation/SAME_DOCUMENT_INSPECTION_20261013.md`

The shared source directory has no `.git` history and can contain later unshipped
work. Do not infer a commit identity from all current files or call it a full clone.
Wi-Fi-only saving has completed final R6 source verification and freeze, documented
in `docs/ui/implementation/SAVE_WIFI_ONLY_20261013.md`. Its 77-file whitelist comprises
38 source files, one report and 38 evidence files. It is ready for publisher review,
but not included in the already-published baseline at this checkpoint.
The actual handoff is `/workspace/shared/esv-wifi-only-frozen-20261013/HANDOFF.json`;
manifest `/workspace/shared/esv-wifi-only-frozen-20261013/PUBLICATION_MANIFEST.json`
SHA-256 is `db99a324a12c2d2de86ff99d7af735e593661601eda8c8b3fec7aa8f961e48fc`.
These local freeze inputs were read and the manifest hash checked. Freeze success
is not remote publication or APK/device acceptance.

## 2. Evidence levels and exact current limits

- Requirements describe desired behavior. An implementation file establishes source,
  not device operation. Synthetic/source-contract tests establish only their checked
  conditions. Compilation/lint establish neither pixels nor native media correctness.
- The already-published editor-default validation is
  `artifacts/ui/editor-default-compression-20261013/validation.json`.
  Final App: 1,679 tests actually executed, zero failures/errors/skips. Core-domain 44,
  Discovery 307, Commons 247, SearXNG 25, verification-client 44, save 86, recording 127
  and ui-theme 9 also actually executed in the recorded commands. Core-model 5,
  library 277 and HLS 40 were UP-TO-DATE in those final commands, not rerun there.
- Verification-local reports 34 cases, including 2 SKIP: frozen private corpus and
  authored-mutation corpus inputs were unavailable. Those two were not executed
  acceptance. Do not sum overlapping historical/slice runs into a new project total.
- Debug/Release compile and lint passed; each lint contains 0 errors, 60 warnings,
  8 hints. `testReleaseUnitTest` does not exist in this build; the failed task-selection
  attempt ran no tests. There is no Release unit pass to invent.
- `scripts/check-editor-admission.py` could not run its historical Git diff in this
  source-only snapshot (exit 129). No `.git` was manufactured. The seven available
  boundary checks passed as recorded in
  `artifacts/ui/editor-default-compression-20261013/boundaries.json`; they do not
  substitute for that unavailable legacy check.
- Current publisher receipt: zero workflow files/runs/checks/statuses; aggregate
  status `pending` is empty, not successful CI. Cloud validation is local recorded
  execution, not GitHub Actions green status.
- The cloud source groups did not assemble/install APKs, operate an emulator/phone,
  fetch real media or measure physical battery savings. Historical package/device
  evidence must be identified separately and must not be generalized to this source.

### Later unshipped Wi-Fi R6 validation, kept separate from the published base

Authoritative frozen report:
`/workspace/shared/esv-wifi-only-frozen-20261013/source/artifacts/ui/save-wifi-only-20261013/validation.json`.
Repository report/evidence for the intended publication are
`docs/ui/implementation/SAVE_WIFI_ONLY_20261013.md` and
`artifacts/ui/save-wifi-only-20261013/validation.json`.
The final task log is `artifacts/ui/save-wifi-only-20261013/final-validation-r6.tasks.log`.

- R6 completed successfully: 111 actionable tasks, 20 executed and 91 UP-TO-DATE.
  App 1,742 tests actually executed with zero failures/errors/skips.
- R6 core-model 6, core-domain 44, Discovery 307, Commons 247, SearXNG 25,
  verification-client 44, verification-local 34 and save 95 were UP-TO-DATE;
  the report explicitly records their execution in an earlier round of this slice.
  Verification-local includes the same two unavailable private-corpus SKIP cases.
- Library 277, HLS 40, recording 127 and ui-theme 9 were UP-TO-DATE in R6 and
  were not rerun earlier in this Wi-Fi slice. Do not label all modules fresh R6 passes.
- Debug/Release Kotlin compile and both lint tasks actually executed in R6;
  each lint reports 60 warnings/8 hints and no errors. AndroidTest Kotlin compilation
  was UP-TO-DATE in R6; a successful compilation check is not instrumented-test execution.
- Runtime admission passed for exact 90 reviewed artifacts in both variants.
  Authored manifest hash stayed unchanged; both merged manifests retain the same
  eight permissions. No dependency or permission was added.
- Independent review closed the listed P2 settings/transport/cleanup findings.
  Final Release bytecode evidence covers 17 class hashes, same-request Android
  Network binding for DNS/socket/connection/watchdog, preserved TLS/public-peer
  checks, and no newly shared cross-task source/credential state. See
  `artifacts/ui/save-wifi-only-20261013/release-review/00-review-summary.txt`.
  This is compiled JVM class evidence, not a packaged DEX, actual VPN test or
  whole-application proof against all possible data leakage.
- `check-boundaries.py` passed. Old stream admission remains BLOCKED because
  the historical candidate artifact-inspection JSON is absent; old OS-power admission
  remains BLOCKED because this source snapshot has no `.git` history. Independent
  strict runtime-byte and merged-permission checks passed, but do not turn either
  unavailable historical gate into a pass.
- APK assembly/install, real device/emulator/network switching, VPN routing,
  SharedPreferences restart and rendered Compose/accessibility remain NOT_RUN.
  Intermediate R3/R4/R5 counts are superseded for final-source App validation by R6;
  no older failure log was rewritten. Remote publication is still pending.

## 3. User-facing feature map

| User-facing function | What exists | What still prevents full acceptance / smallest next step |
|---|---|---|
| Browser navigation, tabs, bookmarks | Real WebView integration and bounded bookmark persistence; new-tab input can populate an owned draft before Go | Native keyboard/layout/rotation/lifecycle matrix incomplete. Do not mark bookmarks absent. See `docs/browser-bookmarks/VERIFICATION.md`. |
| Browser JS dialogs and video detection | Native guarded alert/confirm/prompt; bounded advisory video-element hint; full detection remains explicit; READY same-document revision invalidates stale candidates | Real WebView callback ordering needs device acceptance. Hint is not playback, media identity, rights, or automatic recording authority. Same-document credential forwarding is intentionally narrower than inspection. |
| Browser ordinary history | Policy, bounded codec, serial controller and private atomic store, 43 focused JVM cases | No runtime collection/list UI. Explicit retention decision needed: 200 recent distinct addresses with 30-day expiry, or without time expiry. No default choice is made. `docs/ui/implementation/BROWSER_HISTORY_FOUNDATION_20261012.md`. |
| Incognito and site permissions | Incognito guards exclude ordinary persisted session/history paths; current WebChromeClient explicitly denies camera/microphone and location | Shared CookieManager/storage is not isolated browsing. Permission selection/Android result correlation and granted-capture lifecycle are absent; do not call deny-only a completed permissions manager. `app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt`. |
| Discovery paging and results | Explicit one-shot next-page demand, bounded provider/local validation framework; truthful missing metadata | Real image capability/source admission, Android decode/owner and viewport/UI binding missing. Current Discovery placeholder remains honest. `docs/ui/implementation/DISCOVERY_THUMBNAIL_FOUNDATION_20261012.md`. |
| Direct / Stream / Record | Real bounded save/capture pipelines, explicit user triggers, verified media publication and safe failure guidance; Wi-Fi-only save enforcement passed R6 source verification and is frozen, not yet published | Full native stream/codec/AV/background/recovery coverage pending. A failed network save offers fresh explicit reinspection only; it does not auto-retry, broaden sources or silently start recording. |
| Save location, library and batch Files | App Library/ASK/album plus remembered ordinary SAF folder; batch export uses one selected folder and per-item verified results; originals retained for copies | Native provider/revocation/uncertain cleanup matrix remains partial. Old “choose batch Files behavior” note was resolved by later one-folder implementation. `docs/m5a4b/REQUIREMENTS_DECISION.md`; `docs/ui/implementation/BATCH_DIRECTORY_COPY_20261012.md`. |
| Editing and default compression | Trim/merge/crop/compress with existing entitlement gates; default applies once to new edits, never overwrites active/restored drafts | OPPO edit initialization failure unresolved. Default editor compression is not recording compression, successful encoding, or a paid unlock. `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md`. |
| Power/settings/notices | Real lifecycle OS battery/thermal/network observation and fail-closed policy; localized status; scrollable offline notices and responsive settings panes | Physical energy benefit, platform callback timing, full accessibility/large-font/phone/tablet acceptance absent. The old OS NOT_CONNECTED label is obsolete. `docs/m8b4/VERIFICATION.md`; `docs/ui/implementation/CLOUD_NOTICES_POWER_20261012.md`. |
| Diagnostics and sharing | Bounded typed initialization diagnostics and log-export implementation; no sensitive media/path/URL logging allowed | New diagnostics must be run on the failing device build before determining edit/share root cause. Existing failure enums alone do not prove codec failure or a repair. `docs/ui/implementation/INITIALIZATION_DIAGNOSTICS_CLOUD_20261012.md`; `docs/diagnostics-export/IMPLEMENTATION_AND_VALIDATION.md`. |
| Vault / dynamic rules / new local video import | Explicitly deferred; Vault/rules disabled or isolated | Do not reopen blocked reviews, substitute integrations or count these as implemented. Import is a deferred extension, not automatically an original requirement failure. `docs/ui/implementation/ESV_UI_FEATURE_INTEGRATION_LEDGER.md`; `AGENTS.md`. |
| Real payment and release | Store-neutral preparation and six channel identities | Payment choice, products, actual accounts/SDK/backend and channel acceptance unknown/unconfigured. Fresh release policy and actual release artifact/device gates still needed. `docs/m10b/CHANNEL_READINESS.md`. |
| Full visual design | Admitted SkinKit components and documented responsive/localized slices | External UI originals are unavailable in this snapshot; no complete slide/pixel parity or full accessibility assertion. `docs/ui/implementation/ESV_UI_IMPLEMENTATION_BASELINE.md`. |

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

Wi-Fi-only source implementation and final validation are now complete. Its remaining
step is publication of the exact reviewed freeze, followed separately by authorized
native network-transition acceptance. It is no longer an unfinished code task in
this list and is not claimed published before the publisher verifies the remote.

1. **Implement the real owned-fixture Discovery thumbnail path.** Start from
   `discovery/src/main/kotlin/com/easysavevideo/discovery/thumbnail/DiscoveryThumbnailSession.kt`
   and
   `discovery/src/main/kotlin/com/easysavevideo/discovery/thumbnail/DiscoveryThumbnailCapability.kt`. Build a bounded Android
   decoder/closeable bitmap owner and reliable main-thread delivery, then connect page and
   visible-item events in `app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt`
   and `app/src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt` using owned synthetic
   fixtures. Add stale-page/background/late-delivery/unconfirmed-close/oversized-image tests.
   Do not invent image-read grants from metadata or activate arbitrary remote image URLs.
   Outcome: executable decoder/rendering integration under test, rather than another policy
   abstraction; production source admission remains a separate gate before real fetching.
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
3. **Close finite source-level UI gaps rather than wait for all visual originals.** Audit
   hardcoded legacy browser permission/error text and replace identified app-owned strings
   with paired EN/ZH resources using existing SkinKit semantics; register each source/resource
   contract input and test compact/large-font layout-policy branches. Preserve page titles,
   URLs and technical enums verbatim; do not translate user data. The source location above
   currently contains hardcoded deny notices, making this a concrete code target. Stop short
   of declaring real rendered accessibility or original-PPT parity without actual evidence.

Items 1–3 still require normal scope review and must preserve existing source-admission,
privacy, entitlement and lifecycle gates. They can produce real source/test progress
without selecting history retention, activating billing/Vault, or installing an APK.
They do not cure the unresolved OPPO root cause by assumption.

## 6. Decisions and external evidence that really do gate completion

- **Ordinary browsing history retention:** obtain the existing narrow TTL choice before
  enabling collection. Then integrate accepted page-finish events, one process-owned serial
  writer, localized list/open/remove/confirmed-clear UI and any selected expiry timer. Add
  rotation/clear-versus-late-write/current-row replay tests; avoid only adding another codec.
- **Incognito isolation:** current shared CookieManager is insufficient. Specify and review
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
- **UI/device:** recover external approved visual originals and run the finite phone/tablet,
  large-font/accessibility/lifecycle/native-media matrix before full UI/M9/M11 acceptance.

## 7. Documentation-only validation and publication boundary

Only this overview and `docs/m11a/MILESTONE_STATUS.md` are changed by this refresh.
No production source, Gradle configuration, old evidence, original requirements,
package/version, device state or Git remote is changed. All explicitly referenced
local files/directories were checked for existence at freeze; unavailable device
artifacts are identified as unavailable instead of linked as local evidence.
The two document bytes are frozen separately for the publisher's next reviewed group.
Publication must use that exact whitelist, and a later source commit or Wi-Fi publication
must update the checkpoint rather than being inferred from a shared working tree.
