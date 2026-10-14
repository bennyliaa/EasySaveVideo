# Original-requirement continuation queue

Updated 2026-10-14 UTC. This is a source/evidence reconciliation and executable
queue, not a claim of full product acceptance. A completed slice, APK freeze or
private development-branch backup does not close the product. Continue the next
unblocked row after recording the preceding row's actual result.

## Baseline and interpretation

- Product: `docs/requirement and design/01_Product_Requirements.md`, v0.3_CN;
  later explicit API31 decision and milestone update1 supersede the old API30
  and milestone ordering. No scope reduction is inferred.
- UI: original 2026-10-11 framework, technical/tester requirements, contract and
  34-slide traceability documents; see existing UI implementation baseline.
- Prior frozen integrated candidate: development branch history checkpoint
  `99146ffc86df94e951e8cff48b0e7601d0bac025`; 3,680 unit PASS, two existing corpus
  SKIP. Its report is `integrated-candidate-20261014/FINAL_REPORT.md` in the
  private-repository implementation evidence. That candidate has no device
  acceptance. This queue and later source changes do not change its APK.
- SOURCE IMPLEMENTED means code plus stated tests, never native/device acceptance.
  TO TEST is a remaining acceptance gap, not a reason to redo the same source.
  DEVELOPABLE is missing original scope without a new external gate.
  DECISION/GATE means a named prerequisite; it does not block unrelated rows.

## Product coverage and remaining work

| Original reference | Source/evidence now | Remaining state and next requirement |
|---|---|---|
| PR-01/02, UI-R01/02 | API31/Kotlin/Compose, platform-neutral core, selected SkinKit bindings; boundary/runtime checks | SOURCE IMPLEMENTED; iOS explicitly later. Phone/tablet installation/render acceptance TO TEST |
| PR-03/25 browser basics | SystemBrowserHost: URL, navigation, tabs, WebView fullscreen, session controls, site-permission denials | PARTIAL: ordinary browser implemented; native callback/fullscreen/session behavior TO TEST. Current camera/microphone/location permission handling is fail-closed, not a complete site-permission manager. Original incognito and per-site permission management remain to reconcile/design; do not invent permission grants |
| PR-25 history/bookmarks | Ordinary history bounded 200 entries/30 days; bookmark repository/read feedback/shared reader; integrated candidate report | SOURCE IMPLEMENTED; real AtomicFile/WebView/restart/privacy acceptance TO TEST |
| PR-04 discovery cards | Real coordinator, finite results, provider framework; current cards still render an icon thumbnail placeholder | Thumbnail reader production integration DECISION/GATE (dependency review below); real-source endpoint/contact DECISION. Card interaction native acceptance TO TEST |
| PR-05/06 discovery policy | Scheduler, lifecycle cancellation, progress/history, four policies, finite pagination, recovery exits and stable reading | SOURCE IMPLEMENTED; sources/network/power/native lifecycle TO TEST; no further generic race-hardening slice assumed |
| PR-07/08 source management | Admission/runtime interfaces, local enablement, maintenance/repository/rollback groundwork and UI | PARTIAL. Production signed declarative interpreter remains disabled pending its gate; configuration/update/rollback native acceptance TO TEST |
| PR-09 detection | Existing user-triggered detector, candidate/revision/quality selector | SOURCE IMPLEMENTED; owned actual media and native WebView inspection TO TEST |
| PR-10/11 save | Direct/HLS runtime, verified publication, output/recovery ports | SOURCE IMPLEMENTED for admitted supported formats; real output/MP4/playback/device interruption TO TEST. No all-site/all-format guarantee |
| PR-12/16 task flow | Save state mapping, cancellation, authentication/source reinspection, task detail/recovery; Save failure guidance report | PARTIAL. Full supported Direct→Stream fallback and trusted webpage→Record handoff must be reconciled independently; current guidance is not automatic fallback completion. Native closed loop TO TEST |
| PR-13/14 recording | Existing consent/basic/segment/region runtimes and UI | SOURCE IMPLEMENTED for existing capabilities; actual capture/audio/output/segment/native lifecycle TO TEST; no fabricated consent or audio |
| PR-15 editor | Existing crop/merge/compression engine, non-destructive publication, responsive preview and defaults | SOURCE IMPLEMENTED; encoder/output/playback/Tools-gated native acceptance TO TEST |
| PR-17 library | Stable IDs/folders/rename/move/delete/preview/share/gallery/batch Files; display/search controls report | SOURCE IMPLEMENTED for existing paths; actual SAF/MediaStore/receiver permissions and recovery TO TEST. No local import is added |
| PR-18 Vault | Exact crypto dependency admission only; Vault disabled | DECISION/GATE: security design and implementation admission, not a switch. Do not enable an unfinished Vault or claim encryption |
| PR-19 entitlements | FeatureGate/Store boundaries plus Debug-only fake; Release isolation checks | SOURCE IMPLEMENTED abstraction; read-only product/free-capability explanation page SOURCE IMPLEMENTED in `SETTINGS_ENTITLEMENTS_20261014.md` (PPT24); real billing DECISION/GATE (store/channel/package/product/validation); no new price or payment action |
| PR-20 discovery settings | Preferences already persisted and used by Discovery's filter panel; central Settings category absent in baseline | SOURCE IMPLEMENTED in this slice: central discovery category/detail with shared owner, four controls/defaults; see `SETTINGS_DISCOVERY_20261014.md` |
| PR-20 save settings | Wi-Fi preference real; destination control already in Browser settings | PARTIAL SOURCE IMPLEMENTED. Central destination entry reuses existing picker/admission; see `SETTINGS_DESTINATION_20261014.md`. Native SAF/storage acceptance TO TEST. Concurrent-task control is missing original scope but requires independent bounded execution scheduling, not exposing a number over the existing single lane |
| PR-20 privacy settings | Existing browser cache/Cookie/session controls and separate browser/discovery history/progress controls | PARTIAL SOURCE IMPLEMENTED: central ordinary-history and webpage-cache actions with separate confirmation/status; `SETTINGS_PRIVACY_20261014.md`. Cookie/session and Discovery cleanup remain separate existing controls; native acceptance TO TEST |
| PR-20 browser settings | Existing Browser SettingsPanel has JavaScript/cookies/restore-tabs/active-view limit | SOURCE IMPLEMENTED: central Browser category and old panel share four real preferences and field-level live owner; `SETTINGS_BROWSER_20261014.md`. Native acceptance TO TEST; no new permission grants, incognito or UA switch |
| PR-21 diagnostic privacy | Bounded enum-only error storage, explicit export UI | SOURCE IMPLEMENTED; native destination/limits and Release leak acceptance TO TEST; no automatic uploads |
| PR-22 reliability | Extensive bounded failure/recovery implementation and JVM checks | PARTIAL acceptance; fault matrix on admitted real media/device TO TEST. Do not claim every production ANR resolved from unit tests |
| PR-23 source/license gates | Exact approved runtime inventory, license bytes and core boundaries | Ongoing gate; new dependency never implicitly admitted by this queue |
| PR-24 automation / iOS | Reserved domain interfaces and explicitly later scope | LATER per original product; not a reason to expand current Android slice |

## Ordered unblocked development queue

1. Central discovery preferences (SOURCE COMPLETE; native acceptance remains): existing four preferences, live owner,
   persistence, legal bounds, explicit restore-defaults, matching EN/ZH labels,
   no new search/source/history side effect. Both entry paths and inactive/background coordinator behavior are model-tested;
   3,698 aggregate PASS, two existing corpus SKIP. See the slice report.
2. Central privacy access (bounded history/cache SOURCE IMPLEMENTED): explicit
   confirmed ordinary-history clear uses the existing async controller; cache
   reports REQUESTED/FAILED without pretending verified completion. Cancel,
   duplicate callback, UI retirement, late delivery and storage failure have
   production-controller tests. Cookie/session and Discovery history/progress
   remain separate existing controls; they are not invoked by these two actions.
   Actual Android/native acceptance remains open.
3. Read-only entitlement explanation (SOURCE IMPLEMENTED; native acceptance open): original UI framework section4.7 /
   PPT24 requires free capabilities and the three planned modules. Read the current
   entitlement snapshot without issuing purchase/restore/refresh or changing grants.
   Preserve UNKNOWN and unavailable-store states; Debug simulated grants must be
   visibly distinguished from purchases. Use planned product labels only, with no
   price, final SKU, purchase promise or claim that disabled Vault is operational.
4. Central saving destination access (SOURCE IMPLEMENTED; native acceptance open):
   shared four-preference runtime/picker, live guarded adapter, actual storage
   acknowledgement and existing result dialog; see `SETTINGS_DESTINATION_20261014.md`.
   Central browser preferences (SOURCE IMPLEMENTED; native acceptance open) reuse
   one detail and field-level owner, preserve startup-loaded browser values and
   existing impact notices; see `SETTINGS_BROWSER_20261014.md`.
   Do not enable site permissions or select a real user directory during development.
5. Original save concurrency requirement: first design a bounded queue/lane policy
   compatible with cancellation, recording/native-resource exclusion and recovery;
   then implement and test the real coordinator/runtime. Current single execution
   lane is intentional protection, not a configurable concurrency implementation.
   Selected next slice: Direct-only bounded 1/2 execution, default1; other media
   and native verification stay exclusive. This is a conservative implementation
   bound, not a numeric limit stated in the original specification. Independent
   design review precedes changes to coordinator/runtime/transport ownership.
6. Reconcile the remaining PR-12 fallback contract against current capabilities;
   implement only supported, entitlement-checked transitions, with fresh resource
   admission and explicit Record confirmation. No fake success or untrusted reuse.

For each row: record file/test evidence, independent findings, actual regression
result, private development-branch commit and next row. A failed build is repaired
or explicitly reported. An unchanged pending test is not completion. Keep prior
frozen APKs/evidence immutable and never advance main as part of this queue.

## Decisions with smallest useful resolution

- Thumbnail loading: existing evaluation rejected Coil 3.6.3 for SDK37/Kotlin2.4
  incompatibility. Coil 3.3 core appeared technically compatible but is not admitted;
  its dependency graph/partial-image policy still needs review. Recommended next
  decision: authorize exact-version dependency/license/security admission review
  while retaining current SDK36/Kotlin2.2.10/API31. Review does not authorize public
  thumbnail fetching or runtime use before acceptance. No bespoke network image
  stack is substituted to evade the gate.
- Real Discovery source: choose an already supported source and explicit production
  configuration/contact or owned verification fixture. Recommended least change:
  use existing Commons path with approved contact and appropriate usage/rights
  review; no account, endpoint, provider enablement or personal contact is invented.
- Vault/dynamic interpreter/billing: retain disabled status. These require their own
  scoped security/product gates and are not prerequisites for rows 1–6 above.
- Device acceptance: installing/operating devices is deferred in the current task.
  When resumed, only the authorized emulator with preserved data, compatible signer
  and increasing versionCode; the handed-back phone is excluded. Native results,
  screenshots, TalkBack, font200, rendering, performance and physical energy claims
  cannot be supplied by JVM tests.

## Immediate continuation rule

Discovery, bounded history/cache privacy, read-only entitlement explanation and
central ordinary saving destination and browser preferences now have source
implementations. After the
browser slice's final test/review/private backup, continue original bounded save
concurrency design/implementation or supported PR-12 fallback reconciliation. Report a concrete blocker only for the blocked action and continue
another unblocked original requirement.
If every remaining row really needs a decision or native acceptance, list those
specific gates and request the smallest choice. Do not report the entire ESV
project finished merely because one slice is packaged or synchronized.
