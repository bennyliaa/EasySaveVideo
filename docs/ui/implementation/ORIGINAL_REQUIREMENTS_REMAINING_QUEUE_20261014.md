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
| PR-10/11 save | Direct/HLS runtime, verified publication, output/recovery ports | PARTIAL SOURCE IMPLEMENTED for admitted MP4/HLS. Product section 7.1 basic free MOV/WebM-compatible MP4 normalization is PARTIAL: contracts/storage and narrow silent MOV host adapter are source implemented; see BASIC_NORMALIZATION_ADAPTER_20261014.md. Production Direct still sends output straight to MP4 verification; inactive persistent owned-file adapter is now source implemented (MOV_PERSISTENT_FILES_20261014.md), with real Android storage acceptance and blocking-IO activation gates open; typed final native verification and inactive adopted read owner are source implemented (MOV_TYPED_NATIVE_VERIFICATION_20261014.md; MOV_ADOPTED_READ_OWNER_20261014.md); complete controlled runtime pipeline is DEVELOPABLE NEXT. Real output/playback/interruption TO TEST; no all-site/all-format guarantee |
| PR-12/16 task flow | Save state mapping, cancellation, authentication/source reinspection, task detail/recovery; Save failure guidance report | PARTIAL. Supported explicit Direct→Stream reassessment/Coordinator confirmation is source implemented in `METHOD_ALTERNATIVE_20261014.md` for the documented suitable-error allowlist. Trusted webpage→Record handoff remains UNKNOWN/GATE; this is not automatic fallback or completion of all PR-12. Native closed loop TO TEST |
| PR-13/14 recording | Existing consent/basic/segment/region runtimes and UI | SOURCE IMPLEMENTED for existing capabilities; actual capture/audio/output/segment/native lifecycle TO TEST; no fabricated consent or audio |
| PR-15 editor | Existing crop/merge/compression engine, non-destructive publication, responsive preview and defaults | SOURCE IMPLEMENTED; encoder/output/playback/Tools-gated native acceptance TO TEST |
| PR-17 library | Stable IDs/folders/rename/move/delete/preview/share/gallery/batch Files; display/search controls report | SOURCE IMPLEMENTED for existing paths; actual SAF/MediaStore/receiver permissions and recovery TO TEST. No local import is added |
| PR-18 Vault | Exact crypto dependency admission only; Vault disabled | DECISION/GATE: security design and implementation admission, not a switch. Do not enable an unfinished Vault or claim encryption |
| PR-19 entitlements | FeatureGate/Store boundaries plus Debug-only fake; Release isolation checks | SOURCE IMPLEMENTED abstraction; read-only product/free-capability explanation page SOURCE IMPLEMENTED in `SETTINGS_ENTITLEMENTS_20261014.md` (PPT24); real billing DECISION/GATE (store/channel/package/product/validation); no new price or payment action |
| PR-20 discovery settings | Preferences already persisted and used by Discovery's filter panel; central Settings category absent in baseline | SOURCE IMPLEMENTED in this slice: central discovery category/detail with shared owner, four controls/defaults; see `SETTINGS_DISCOVERY_20261014.md` |
| PR-20 save settings | Wi-Fi preference real; destination control already in Browser settings | PARTIAL SOURCE IMPLEMENTED. Central destination entry reuses existing picker/admission; see `SETTINGS_DESTINATION_20261014.md`. Native SAF/storage acceptance TO TEST. Direct-only 1/2 concurrency source is implemented with real shared admission, two fixed transfers and serial native verification; see `DIRECT_CONCURRENCY_20261014.md`. Other methods remain exclusive; real concurrent-network/native acceptance TO TEST |
| PR-20 privacy settings | Existing browser cache/Cookie/session controls and separate browser/discovery history/progress controls | PARTIAL SOURCE IMPLEMENTED: central ordinary-history and webpage-cache actions with separate confirmation/status; `SETTINGS_PRIVACY_20261014.md`. Central Discovery Seen/progress actions are source implemented separately in `SETTINGS_DISCOVERY_PRIVACY_20261014.md`; Cookie/session controls remain in the existing browser panel. Native acceptance TO TEST |
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
5. Original save concurrency requirement (SOURCE IMPLEMENTED; native acceptance open):
   Direct-only bounded 1/2 execution, default 1, shared opaque claims, fixed transport
   ownership, conservative second-operation disk admission, per-task isolation and
   evidence-driven resource retirement. Other media and native verification remain
   exclusive. See `DIRECT_CONCURRENCY_20261014.md` and its reviewed design. This bound
   is an engineering choice, not a number stated in the original specification.
6. PR-12 supported Direct-to-Stream method contract (SOURCE IMPLEMENTED; native
   acceptance open): exact failed identity, fresh real HLS preparation, opaque
   single-use proposal, existing variant/destination confirmation, unknown-write
   outcome and original startup recovery. See `METHOD_ALTERNATIVE_20261014.md`.
   Stream-to-Record remains UNKNOWN until trusted current capture capability and
   explicit fresh consent/entitlement/destination are available; do not infer it
   from HTTP errors, DOM flags or presence of the platform capture service.
7. Central Discovery privacy controls (SOURCE IMPLEMENTED; native acceptance open):
   current keyword/filter/language profile Seen events, all-profile Seen events,
   and one source's continuation progress in the captured profile are separate
   confirmed controls. Shared controller operation ownership survives view exit;
   both old and central entry points use the same prompt adapter. These are not
   a stored keyword-input list. See `SETTINGS_DISCOVERY_PRIVACY_20261014.md`.
8. PR-25 Incognito and per-site permissions (DESIGN RECONCILED / DEPENDENCY EVALUATION PASSED / INTEGRATION APPROVED): see
   `PR25_BROWSER_PRIVACY_DESIGN_20261014.md`. Reconcile existing
   context/session/history/authentication boundaries against real WebView storage
   isolation and platform permission APIs. Do not equate omitted history with
   isolated Incognito, or current fail-closed permission prompts with a complete
   site-permission manager. Identify exact dependency/permission/native gates
   before implementing this larger original requirement.

9. Free basic output normalization (LAYERS 1–4 SOURCE IMPLEMENTED / ADOPTED OWNER SOURCE IMPLEMENTED / CONTROLLED PIPELINE NEXT): see
   `BASIC_NORMALIZATION_DESIGN_20261014.md` for the initial bounded free MOV remux
   profile, separate source/output evidence and exact-owned recovery protocol.
   Contracts/storage layer passed 4,073 tests plus two existing skips; see
   `BASIC_NORMALIZATION_20261014.md`. Narrow silent host adapter now passes 25 real
   pinned-bytecode tests, including 138 rate/layout cases, with 20 clock/canonical
   and 16 raw-profile JVM tests; see `BASIC_NORMALIZATION_ADAPTER_20261014.md`.
   Inactive persistent owned-file/adoption adapter is implemented; see
   `MOV_PERSISTENT_FILES_20261014.md` for final validation status and the blocking
   temporary-IO/close limitation. Production activation requires memory-only
   cancellation/release responsiveness, real retirement and UI nonblocking tests.
   Typed final Android verification source passed 4,261 tests plus two skips;
   its exact tail/helper layer passed 32 official-wrapper tests and app-focused
   policy/RAF/native-observation tests passed 141. See
   MOV_TYPED_NATIVE_VERIFICATION_20261014.md. The inactive adopted canonical owner
   and exact local permit passed focused tests (33 gate and 121 app), independent
   review and forced 13-module regression (4,354 PASS plus two existing skips). See MOV_ADOPTED_READ_OWNER_20261014.md. The next slice
   connects actual claim/accepted Runtime/batch, normalization/adoption, typed
   commit/atomic publication/terminal cleanup and recovery, including mandatory
   off-lock temporary-IO changes. Runtime MOV admission and native acceptance
   remain open. Original product section
   7.1 and technical sections 5.1/5.3 require feasible MOV/WebM to playable MP4 as
   basic output, independent of paid advanced editing. Current Direct does not
   normalize containers/codecs. Preserve the reviewed narrow admitted-format/evidence,
   device codec capability, independent raw/output staging, cancellation, budgets,
   native exclusion and final verification contract. Reuse only admitted components;
   unknown formats/protection never become a conversion bypass. First adapter
   admission is silent-only with exact clocks; audio rejects the whole file.
   Complete AAC AU/PCM-frame/timestamp verification is the next original-scope
   extension, not silently dropped audio or a claim of general MOV support.

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
- True Incognito: the user authorized a narrow official AndroidX WebKit candidate
  evaluation on 2026-10-14 at approximately 11:02 UTC. The independent 1.17.1
  evaluation completed: both isolated compile variants passed with the unchanged
  SDK/toolchain, candidate graph 90→91 added only WebKit and preserved the original
  90 artifact hashes; Apache and Chromium BSD notice obligations were retained.
  See the separate ESV WebKit evaluation report. The user approved exact WebKit 1.17.1 integration and isolated Incognito
  development at approximately 12:17 UTC, retaining camera/microphone/location
  denials. Implementation design and exact dependency/notice patches are being
  prepared separately while MOV finishes its current checkpoint. The
  production runtime inventory is still 90; site camera/microphone/location grants
  remain separate. Without
  new dependencies, a dedicated platform WebView process/data suffix is possible
  but has a larger cross-process Save and cleanup design. See the PR-25 design.
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
implementations. Bounded Direct concurrency now has its source implementation.
Supported PR-12 Direct-to-Stream now also has its source implementation. After
its final test/review/private backup, central Discovery privacy controls were
implemented through existing owners. After that checkpoint, continue PR-25
architecture reconciliation while prioritizing the original free basic-output
normalization design. No arbitrary extra feature replaces a blocked gate. Report a concrete blocker only for the blocked action and continue
another unblocked original requirement.
If every remaining row really needs a decision or native acceptance, list those
specific gates and request the smallest choice. Do not report the entire ESV
project finished merely because one slice is packaged or synchronized.
