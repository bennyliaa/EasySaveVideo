# ESV milestone status — evidence checkpoint 2026-10-13

This current-status index supersedes the stale capability statements in the original
2026-10-07 M11-A inventory. It does not rewrite original requirements or historical
reports. Use Update1 M0–M12 ordering and the later Android 12/API31 minimum decision.
The original M11-A README, release-blocker sheet and fact JSON remain dated evidence,
not a live inventory of later work. In particular, “never assembled/installed”,
“bookmarks missing”, “destinations incomplete” and “OS adapter NOT_CONNECTED” must
not be reused as current project-wide conclusions.

## Evidence boundary

- Latest verified published source checkpoint: [b0c5647e6759dd212423647742e4923c8d0dce93](https://github.com/bennyliaa/EasySaveVideo/commit/b0c5647e6759dd212423647742e4923c8d0dce93),
  `development/cloud-followup-20261013`, candidate-label and typed denied-permission display:
  30 changed files / 30 exact blob readbacks. This follows `795b6bb` typed destination/
  filter roles (42 files) and `62b5f6d` authentication/fixed destination copy (33 files),
  all published. Prior Wi-Fi `172fa3c`, Browser copy `93a67ba`, contracts/Shell `82ccae9`,
  locale/tokens/original Markdown `8fa0c4a` and body/input-clear `3176bcc` remain in that
  chain. Main is unchanged at `ea87963dee631a498774f648c9a1dabd9f665b90`; source
  publication is not a new APK, installed device version, main merge or release.
- New full cloud regression: **13 module test tasks actually reran with `--rerun`**;
  **307 fresh JUnit suites**, **3,149 registered / 3,147 passed / 0 failures / 0 errors / 2 skipped**. App **1,886**
  and Theme **17** both actually ran. The two unavailable `ESV_OWNED_CORPUS` cases
  remain skipped, not passed. Debug/Release Kotlin and lint analysis/report tasks
  actually reran with `--rerun` and passed; each lint has **60 warnings/8 hints/0 errors**.
  Runtime admission verified **90 exact artifacts**; final compiled boundary PASS.
  Gradle completed 101 tasks: 30 executed/71 dependency tasks UP-TO-DATE, exit 0;
  all 1,391 captured code/test/script/Gradle/third-party inputs remained byte-identical.
- Current v8 host SQLite: **14 tests / 344 rollback fault boundaries PASS**. Synthetic
  DOM checks: video presence **22**, save detector **11**; detector output-path failure
  remains recorded separately. All 21 historical admission scripts reran unchanged:
  **2 scoped pass / 16 missing Git / 2 missing evidence / 1 historical-anchor fail**.
  All 14 historical SQLite scripts reran unchanged: **2 scoped pass / 12 historical-anchor
  fail**. The final boundary is already counted within the 21 admission scripts.
  Current-source success does not erase those failed/blocked historical gates.
- Report: `docs/m11a/FULL_CLOUD_UI_REGRESSION_20261013.md`;
  evidence: `artifacts/regression/full-ui-20261013/VERIFICATION.json`.
  Historical App slice counts 1,835 (input Clear), 1,857 (authentication/fixed copy),
  1,864 (typed destination), 1,870 (filter role) and 1,886 (candidate/permission) stay
  separately dated. Theme was not rerun in those intermediate slices; its actual full
  regression run is new evidence. Overlapping totals must not be summed.
- Authentication waiting returns only to the current Browser; full `OpenOriginForLogin`
  remains incomplete. Destination's earlier 26 fixed resource pairs now have 34 additional
  typed notice/kind/copy-state pairs published in `795b6bb`; that migration is no longer
  deferred. Other Save/Browser raw notices and full-app localization remain partial.
- SOURCE/HOST means implementation plus bounded evidence. DEVICE_PARTIAL means only
  specifically recorded older flows. Current APK/device/emulator/real-media/PPT/rendered
  acceptance is **NOT_RUN**; no whole milestone is accepted. Follow the unexecuted
  `docs/m11a/UI_DEVICE_ACCEPTANCE_CHECKLIST_20261013.md` for the remaining native checks.
  Older OPPO edit/share and Windows issues remain open and are not erased by cloud tests.
- All nine original UI Markdown documents are recovered and published; PPT bytes remain
  unavailable/unviewed. No full visual parity or UI-E acceptance. Latest receipt has no
  workflows/runs/checks/statuses; empty `pending` is not green CI.
- See [completion evidence and next steps](PROJECT_COMPLETION_EVIDENCE_2026-10-13.md)
  for exact receipt/report references, historical execution distinctions and remaining work.

| Update1 milestone | Current implementation / bounded evidence | Remaining acceptance or decision / minimum next step |
|---|---|---|
| M0 — admission and architecture | Reconstructed Core/Adapter boundary, original SystemWebView, admitted SkinKit Contract/Default 1.0.0 and exact 90-artifact Android runtime graph. `AGENTS.md`; `docs/m0/ADR_005_LICENSE_ADMISSION_GATE_RECONSTRUCTED.md`; `docs/ui/implementation/UI_SHELL_APPEARANCE_SLICE_20261011.md` | Missing old M0 history is not recovered. Candidate/service restrictions remain. Current dependencies do not authorize all originally proposed projects or public-source benchmarks; preserve exact-byte admission. |
| M1 — foundation/browser/settings | SOURCE/HOST: tab/session/navigation, bookmarks, JS dialogs, new-tab search-input transfer, bounded video hint and READY same-document explicit inspection; responsive SkinKit shell/settings with persistent visible root labels, offline notices, editor default compression, bilingual Browser copy, bounded semantic tokens, explicit Browser/new-tab Clear and localized typed permission-denial notices. `docs/browser-bookmarks/VERIFICATION.md`; `docs/browser-js-dialogs/VERIFICATION.md`; `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md` | Ordinary history is an unconnected foundation pending retention choice; incognito has no user-facing entry and no cookie/storage isolation; web camera/mic/location requests currently deny. Finish bounded runtime paths and separately verify native WebView, layout, accessibility and lifecycle. |
| M2 — Discovery MVP | SOURCE/HOST: provider framework, Commons, optional SearXNG/service/local validation boundaries, no-provider fallback, explicit next-page demand, distinct body-state feedback, query Clear and shared inline/floating filter roles (floating host remains Dialog). `docs/m2-commons/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/ui/implementation/DISCOVERY_PAGE_DEMAND_20261013.md`; `docs/searxng-discovery/REMAINING_TASKS.md` | Real Discovery thumbnail reader/decoder/UI are not connected. Public/source admission and actual search→validation quality remain bounded/unverified; two private corpus tests skipped. Implement owned-fixture thumbnail integration by reusing admitted media Reader/thumbnail and mature-loader capabilities under original UI08; no arbitrary URL reads or new generic image-download/decode/cache framework is authorized. |
| M3 — Direct / Stream save | SOURCE/HOST: HTTPS MP4 and restricted static AVC/AAC HLS, verified media publication, failure guidance/fresh explicit reinspection; Wi-Fi-only source enforcement is validated and published in `172fa3c`; localized Save display/formatting is published in `8fa0c4a`. `docs/m3a/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/m3b-eof/DEVICE_ACCEPTANCE_CHECKLIST.md`; `docs/ui/implementation/SAVE_FAILURE_GUIDANCE_20261012.md` | Not universal HLS/DRM extraction or automatic ①→②→③ fallback. Native download/remux/AV sync/recovery needs its own device evidence. Native network-transition/VPN acceptance remains NOT_RUN. Return to the current Browser from authentication waiting is published in `62b5f6d`; full `OpenOriginForLogin` remains incomplete. Candidate numbering is locale-rendered in `b0c5647`. Neither source verification nor publication implies device acceptance. |
| M4 — recording | SOURCE/HOST: system-consent capture, pause/resume, internal playback-audio option, verified publication and advanced modes. `docs/m4/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/m6c/IMPLEMENTATION_AND_ACCEPTANCE.md` | Older OPPO code8 includes bounded recording/replay acceptance as reported by the delivery owner, not cloud execution. Full projection/audio/codec/GPU/OEM/interruption matrix remains open; recording default compression is not supplied by editor defaults. |
| M5 — ordinary library/export | SOURCE/HOST: ordinary library/folders/playback, share/export, App Library/ASK/album destinations and remembered SAF folder route, batch selection/copy/move and one-folder Files export. `docs/m5a4a/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/m5a4b/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/ui/implementation/BATCH_DIRECTORY_COPY_20261012.md` | Older code8 two-item copy/move and dialog-scroll checks are partial acceptance. Share `INITIALIZATION_FAILED` remains open; newer diagnostics await a bounded device reproduction. Preserve originals and distinguish verified/unverified copies. `OrdinaryDestinationUi` fixed copy and typed dynamic notice/copy-state display are published in `62b5f6d`/`795b6bb`; unrelated Save runtime notices remain outside this migration. No broad provider/device pass. |
| M5 — Vault | DEFERRED / DISABLED / ISOLATED: dependency admission only. `docs/m5-vault-dependencies/`; `AGENTS.md` | Not implemented or security-accepted. Keep shelved; do not reopen blocked candidate reviews or claim ordinary plaintext media is encrypted Vault storage. |
| M6 — advanced video tools | SOURCE/HOST: guarded trim/merge/crop/compress, region/segments/frame preview; new-edit compression preference wired. `docs/m6a/`; `docs/m6b/`; `docs/m6c/`; `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md` | OPPO edit `STORAGE_UNAVAILABLE` unresolved; diagnostic implementation is not root-cause repair. Real editing/encoding/output visual/AV/OEM acceptance remains open. New local video import is deferred, not a newly discovered original-spec defect. |
| M7 — provider maintenance | SOURCE/HOST: bounded signed fixed Commons parameter/policy repository, rollback/health/runtime; optional isolated verification-service architecture. `docs/m7a/`; `docs/m7b/`; `docs/m7c/`; `docs/verification-service/IMPLEMENTATION_AND_ACCEPTANCE.md` | Production trust roots/endpoint/live service not established. Dynamic-rule candidate remains deferred/isolated. No general rule interpreter or production deployment acceptance. |
| M8 — history/quality/power | SOURCE/HOST: bounded Seen/origin/filter/media-priority policies and active lifecycle-owned OS signal adapter; explicit paging improvements. `docs/m8b4/CURRENT_MILESTONE_STATUS.md`; `docs/m8b4/VERIFICATION.md` | OS adapter is connected, but physical energy/calibration/OEM acceptance is not measured. Native SAVED/PLAYED identity stays UNKNOWN without trustworthy signals; a video hint or page visit does not grant identity. Browser history retention is a separate open decision. |
| M9 — reliability/compatibility | SOURCE/HOST: fault isolation/recovery, callback/cancellation/freshness guards and bounded diagnostics; new full 13-module regression recorded with 3,147 passes and 2 explicit corpus skips. Historical limited emulator/API31 and OPPO device evidence exists. `docs/m9a/`; `docs/ui/implementation/INITIALIZATION_DIAGNOSTICS_CLOUD_20261012.md` | Full Android12+ phone/tablet/rotation/process-kill/storage-fault/WebView/codec matrix incomplete. OPPO edit/share unresolved; historical Windows environment-limited tests are not erased by cloud tests. No whole-project/device acceptance. |
| M10 — real IAP | Preparation only: channel-neutral store/entitlement contracts and explicit distribution identities. Six intended Android markets documented. `docs/m10a/STORE_BOUNDARY.md`; `docs/m10b/CHANNEL_READINESS.md` | Payment model/products/SKUs/accounts/real SDK/receipt backend remain undecided or unknown; all channel payment adapters unconfigured. Channel identity is not purchase/restore/refund acceptance. |
| M11 — Android release | Development APK deliveries and source publication exist; release preparation remains partial. `docs/ui/implementation/CODE8_INITIALIZATION_INVESTIGATION_20261012.md`; `docs/m10b/CHANNEL_READINESS.md` | Final release package/signing/notices/privacy/current market review/qualification and scoped device acceptance still required. No configured CI workflows is not green CI. Nine original UI Markdown documents are recovered/published; the original PPT binary is still unavailable. No complete visual parity or UI-E claim. |
| M12 — iOS | Shared specification only | Native implementation and authorization remain outside current Android work. |

## Core metadata compatibility note (preserved)

M10-A changed `EntitlementSnapshot` from Kotlin `data class` to a final ordinary
class to snapshot aliases safely. Constructor defaults, equality/hashCode, copy,
components/destructuring and existing source-call behavior are preserved by explicit
methods. Reflection `isData` changes to false; binary-client/serialization compatibility
is **not** newly promised. ProductMapping also snapshots nested sets. This note is
part of the current release inventory, not a request to undo the fix.

## Historical base receipt remains historical

The original M11-A tested base recorded 1,822 JVM tests, dual compile/lint, exact74
and cold aggregate passes, as summarized in `docs/m11a/README.md`.
The original receipt referenced by that README is absent from this cloud snapshot;
this refresh has not revalidated its bytes. Historical reports are not replaced with
current totals or relabeled as a run on today's code. Current validation uses the new full
regression report, with older slice reports retained as history. Unit/host success,
compilation, source review, published bytes and device acceptance remain separate claims.
The regression evidence/documentation refresh awaits combined review/publication; already
published source does not become pending again. This document editor creates no separate
freeze, publishes nothing and runs no Gradle, APK or device task.
