# ESV milestone status — evidence checkpoint 2026-10-13

This current-status index supersedes the stale capability statements in the original
2026-10-07 M11-A inventory. It does not rewrite original requirements or historical
reports. Use Update1 M0–M12 ordering and the later Android 12/API31 minimum decision.
The original M11-A README, release-blocker sheet and fact JSON remain dated evidence,
not a live inventory of later work. In particular, “never assembled/installed”,
“bookmarks missing”, “destinations incomplete” and “OS adapter NOT_CONNECTED” must
not be reused as current project-wide conclusions.

## Evidence boundary

- Latest verified published source checkpoint for this document: `2986ada82ff4f0843eced900a90249d69f876671`,
  `development/cloud-followup-20261013`, default editor compression. This is a source
  publication, not a new APK, installed device version, main-branch merge or release.
- SOURCE/HOST means implementation plus the cited bounded test evidence. DEVICE_PARTIAL
  means only the specifically recorded older device flows; no whole milestone is accepted.
- Cloud source increments were not assembled/installed or device-tested. Older APK and
  emulator/OPPO work exists; those observations do not transfer to newer source by inheritance.
- Wi-Fi-only saving has completed final R6 source validation and a 77-file freeze,
  but is not yet published at this checkpoint. App 1,742 cases actually ran; its
  device/network/APK acceptance remains NOT_RUN. See the overview for cached-task
  and historical-gate limits. It is not part of the published baseline above.
- See [completion evidence and next steps](PROJECT_COMPLETION_EVIDENCE_2026-10-13.md)
  for user-visible features, exact test limits and actionable remaining code work.

| Update1 milestone | Current implementation / bounded evidence | Remaining acceptance or decision / minimum next step |
|---|---|---|
| M0 — admission and architecture | Reconstructed Core/Adapter boundary, original SystemWebView, admitted SkinKit Contract/Default 1.0.0 and exact 90-artifact Android runtime graph. `AGENTS.md`; `docs/m0/ADR_005_LICENSE_ADMISSION_GATE_RECONSTRUCTED.md`; `docs/ui/implementation/UI_SHELL_APPEARANCE_SLICE_20261011.md` | Missing old M0 history is not recovered. Candidate/service restrictions remain. Current dependencies do not authorize all originally proposed projects or public-source benchmarks; preserve exact-byte admission. |
| M1 — foundation/browser/settings | SOURCE/HOST: tab/session/navigation, bookmarks, JS dialogs, new-tab search-input transfer, bounded video hint and READY same-document explicit inspection; responsive SkinKit shell/settings, offline notices, editor default compression. `docs/browser-bookmarks/VERIFICATION.md`; `docs/browser-js-dialogs/VERIFICATION.md`; `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md` | Ordinary history is an unconnected foundation pending retention choice; incognito does not establish cookie/storage isolation; web camera/mic/location requests currently deny. Finish bounded runtime paths and separately verify native WebView, layout, accessibility and lifecycle. |
| M2 — Discovery MVP | SOURCE/HOST: provider framework, Commons, optional SearXNG/service/local validation boundaries, no-provider fallback, explicit next-page demand. `docs/m2-commons/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/ui/implementation/DISCOVERY_PAGE_DEMAND_20261013.md`; `docs/searxng-discovery/REMAINING_TASKS.md` | Real Discovery thumbnail reader/decoder/UI are not connected. Public/source admission and actual search→validation quality remain bounded/unverified; two private corpus tests skipped. Implement owned-fixture thumbnail runtime pieces without granting arbitrary URL reads. |
| M3 — Direct / Stream save | SOURCE/HOST: HTTPS MP4 and restricted static AVC/AAC HLS, verified media publication, failure guidance/fresh explicit reinspection; Wi-Fi-only source enforcement is validated/frozen, pending source publication. `docs/m3a/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/m3b-eof/DEVICE_ACCEPTANCE_CHECKLIST.md`; `docs/ui/implementation/SAVE_FAILURE_GUIDANCE_20261012.md` | Not universal HLS/DRM extraction or automatic ①→②→③ fallback. Native download/remux/AV sync/recovery needs its own device evidence. Publish the exact frozen Wi-Fi-only group separately; native network-transition/VPN acceptance remains NOT_RUN. Do not count the freeze as remote publication. |
| M4 — recording | SOURCE/HOST: system-consent capture, pause/resume, internal playback-audio option, verified publication and advanced modes. `docs/m4/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/m6c/IMPLEMENTATION_AND_ACCEPTANCE.md` | Older OPPO code8 includes bounded recording/replay acceptance as reported by the delivery owner, not cloud execution. Full projection/audio/codec/GPU/OEM/interruption matrix remains open; recording default compression is not supplied by editor defaults. |
| M5 — ordinary library/export | SOURCE/HOST: ordinary library/folders/playback, share/export, App Library/ASK/album destinations and remembered SAF folder route, batch selection/copy/move and one-folder Files export. `docs/m5a4a/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/m5a4b/IMPLEMENTATION_AND_ACCEPTANCE.md`; `docs/ui/implementation/BATCH_DIRECTORY_COPY_20261012.md` | Older code8 two-item copy/move and dialog-scroll checks are partial acceptance. Share `INITIALIZATION_FAILED` remains open; newer diagnostics await a bounded device reproduction. Preserve originals and distinguish verified/unverified copies. No broad provider/device pass. |
| M5 — Vault | DEFERRED / DISABLED / ISOLATED: dependency admission only. `docs/m5-vault-dependencies/`; `AGENTS.md` | Not implemented or security-accepted. Keep shelved; do not reopen blocked candidate reviews or claim ordinary plaintext media is encrypted Vault storage. |
| M6 — advanced video tools | SOURCE/HOST: guarded trim/merge/crop/compress, region/segments/frame preview; new-edit compression preference wired. `docs/m6a/`; `docs/m6b/`; `docs/m6c/`; `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md` | OPPO edit `STORAGE_UNAVAILABLE` unresolved; diagnostic implementation is not root-cause repair. Real editing/encoding/output visual/AV/OEM acceptance remains open. New local video import is deferred, not a newly discovered original-spec defect. |
| M7 — provider maintenance | SOURCE/HOST: bounded signed fixed Commons parameter/policy repository, rollback/health/runtime; optional isolated verification-service architecture. `docs/m7a/`; `docs/m7b/`; `docs/m7c/`; `docs/verification-service/IMPLEMENTATION_AND_ACCEPTANCE.md` | Production trust roots/endpoint/live service not established. Dynamic-rule candidate remains deferred/isolated. No general rule interpreter or production deployment acceptance. |
| M8 — history/quality/power | SOURCE/HOST: bounded Seen/origin/filter/media-priority policies and active lifecycle-owned OS signal adapter; explicit paging improvements. `docs/m8b4/CURRENT_MILESTONE_STATUS.md`; `docs/m8b4/VERIFICATION.md` | OS adapter is connected, but physical energy/calibration/OEM acceptance is not measured. Native SAVED/PLAYED identity stays UNKNOWN without trustworthy signals; a video hint or page visit does not grant identity. Browser history retention is a separate open decision. |
| M9 — reliability/compatibility | SOURCE/HOST: fault isolation/recovery, callback/cancellation/freshness guards and bounded diagnostics. Historical limited emulator/API31 and OPPO device evidence exists. `docs/m9a/`; `docs/ui/implementation/INITIALIZATION_DIAGNOSTICS_CLOUD_20261012.md` | Full Android12+ phone/tablet/rotation/process-kill/storage-fault/WebView/codec matrix incomplete. OPPO edit/share unresolved; historical Windows environment-limited tests are not erased by cloud tests. No whole-project/device acceptance. |
| M10 — real IAP | Preparation only: channel-neutral store/entitlement contracts and explicit distribution identities. Six intended Android markets documented. `docs/m10a/STORE_BOUNDARY.md`; `docs/m10b/CHANNEL_READINESS.md` | Payment model/products/SKUs/accounts/real SDK/receipt backend remain undecided or unknown; all channel payment adapters unconfigured. Channel identity is not purchase/restore/refund acceptance. |
| M11 — Android release | Development APK deliveries and source publication exist; release preparation remains partial. `docs/ui/implementation/CODE8_INITIALIZATION_INVESTIGATION_20261012.md`; `docs/m10b/CHANNEL_READINESS.md` | Final release package/signing/notices/privacy/current market review/qualification and scoped device acceptance still required. No configured CI workflows is not green CI. External visual source material unavailable; no complete visual parity claim. |
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
current totals or relabeled as a run on today's code. Current validation uses the exact dated slice report;
unit success, compilation, source review, published bytes and device acceptance are
separate claims. This documentation refresh runs no Gradle, APK or device task.
