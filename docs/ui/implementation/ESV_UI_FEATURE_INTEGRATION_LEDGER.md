# UI capability integration ledger

Reconciled against main fbbb0b2aacf5e1967584bdaa858250e73f6df605 plus the bounded
discovery/task layout source increment. COMPLETE SOURCE
means implementation plus scoped source validation. PARTIAL means callable code
or bounded checks exist; it does not close full PPT/media/device acceptance.
NOT ACCEPTED means the specified acceptance is unperformed. DISABLED is intentional.

| Flow / PPT | Existing binding | UI work / boundary |
|---|---|---|
| Discovery6–8,25 | PARTIAL: real controller/coordinator/filter/source/history/visibility bindings | code4 natural-refresh P1 PASS: two owned queries, three repeat Discover clicks and one title click, Search enabled, actual NO_SOURCES, zero source attempts. Earlier SUSPENDED/budget0 cause remains unrecorded; OS gate precedes NO_SOURCES. No provider or gate change |
| Browser9,11 | PARTIAL: real SystemWebView/address/tab/bookmark/history/incognito bindings | code4 zero-source reserved address draft and Go availability passed; no public navigation. Current source additionally binds existing selectedTabId to selected semantics on the tab button; no state owner or navigation change. TalkBack NOT RUN |
| Detect/select/save10–14 | PARTIAL: actual AndroidSaveRuntime, revision-fenced selection, task and destination commands wired | Callable real chain, not fresh candidate→save→verified output→library/preview acceptance. Requires admitted owned media/source, actual entitlement/capability and permitted destination/consent/save/preview execution; accepted commands never imply successful output |
| Tasks | PARTIAL: persisted state/phase/output-disposition/retry/unknown-progress adapters | code4 route presence only; no new task, content census, retry/background-save/resource-release acceptance |
| Recording15–16 | PARTIAL: actual consent/session/segment/region/frame-check bindings | code4 route only; no capture consent, microphone, recording, encoder, recreation or output test. Requires permitted owned visible content, capture flow and actual capability; no automatic permission grants |
| Editor17 | PARTIAL: existing Tools gate/runtime/exact MediaReadRef and output fences | No editor input/read/encode acceptance. Requires authorized verified owned input and permitted Tools access; no gate weakening or encoder rewrite |
| Library18,20 | PARTIAL: existing stable-ID/revision/list/preview/export/edit/destination commands | code4 route only; no private item/preview/file or before-after census, delete/move/export/verified-save-output acceptance |
| Vault19 | DISABLED/unreviewed | No unlock, private reader, simulated security success or retry of shelved scope |
| Settings21–23 | PARTIAL: actual settings owner/appearance/language/provider configuration | code4 Dark/English/Chinese persisted after process restarts; landscape selected values after scrolling and Back→actual Discover passed. Locale switching required reopening Settings. Real source config/update/rollback NOT ACCEPTED |
| Entitlements24 | PARTIAL real FeatureGate; Store/payment DISABLED/unconfigured | No prices/SKUs/account/purchase/release unlock; billing decision/admission needed before implementation |
| A/B source verification22 | PARTIAL source; optional service client and finite local B default UNCONFIGURED | No new service/site activation. Same tagged100 device samples, public-source admission and physical CPU/RAM/network/time/energy NOT RUN/NOT MEASURED; emulator cannot prove phone power |

## Milestone status and package separation

- UI-A COMPLETE SOURCE: selected SkinKit Contract/Default1.0.0 and exact reviewed
  official graph; strict runtime equality90 artifacts. No dependency addition here.
- UI-B appearance/tokens COMPLETE SOURCE; localization PARTIAL: code4 has217
  paired resources; later source groups add38/183/276 pairs and the English
  region-limit correction. Legacy helper/runtime copy remains; original query,
  metadata, URL and diagnostic arguments are preserved, not translated.
- UI-C finite NavController/official WindowSizeClass COMPLETE SOURCE; device
  acceptance PARTIAL, only emulator-5556/API31. No tablet/multi-window/foldable,
  full phone/compact-height matrix or complete cancellation/recreation/resource
  release acceptance. Selected-tab semantic wiring closes one interface omission.
- UI-D business adapters PARTIAL as above. Existing implementations are wired;
  fresh real saving/recording/editing acceptance is not established.
- UI-E NOT COMPLETE: no full34-slide visual parity/pixel golden, licensed runtime
  reference ocean imagery, 200% font, TalkBack, focus traversal, touch-target or
  contrast matrix. These gaps require actual authorized device/layout/content
  acceptance, not invented success or unrelated new features.

Installed signed code4 is source d9bdf34567bf2d138e796a50560419d359385cf4,
return ref d44be6f9cfd01a35529d1fb47e19bf0dabd6bb73. Official Windows signature/
alignment and one compatible install-r passed. Android12/API31 minimum. It
excludes all later copy changes, P2 correction and tab semantic wiring. No new
APK assembled/installed for this ledger/source increment; OPPO stays handed back.

Device evidence: artifacts/ui/code4-bounded-device-acceptance-20261012/RESULT.json,
13 PASS,0 final-script FAIL,3 NOT_RUN (P1 status isolation, public browsing,
private-media census/content preservation). Earlier locator/input/viewport
failures retained locally; separate End/Delete verified exact owned drafts.
No app edit defect established. No images/raw UI uploaded; browser image with
an existing tab header excluded. Subsequent single normal refresh reported recent
observations, no pending policy update and budget2 concurrent/6 attempts. The
separate artifacts/ui/code4-natural-refresh-p1-20261012 evidence has P1 PASS under
confirmed zero providers, two explicit owned queries and zero source attempts.
It does not retrospectively alter the old bounded report. Individual signal
values are not exposed by code4 UI; the all-recent aggregate is observed, not
invented per-signal readings. No simulation/system change. Device testing stops here.

Source evidence: source-provider-dialog-copy-20261012 has82 selected JVM passes;
region-notice-p2-20261012 has5. Actual debug/release compilation succeeded after
the nullable-format fix; final validation reused UP-TO-DATE compilation. Lint58
warnings plus7 informational/hint entries per variant,0 error/fatal. Current tab
increment passed24 existing tab-state/persistence tests and executed both variant
Kotlin compilers; device semantic
acceptance NOT RUN. Historical115 Windows full-suite failures remain unresolved;
scoped passes are not a global pass.

Further acceptance depends on the specific fixtures, source/capability decisions
and execution permissions listed above. Vault/rules remain disabled, payment
undecided, and no private media/recording/microphone or physical-power acceptance
is implied. Do not loosen gates to manufacture a PASS.

## Concrete visual work, separate from execution permission

The current bounded source group addresses UI-R04/framework section3 and C-03/C-04:
Discovery now caps effective columns by remaining width, text scale and existing
SkinKit gaps, preserves the configured preference, and explains a reduced grid
with paired resources. It no longer forces extra columns on a wide single-column
preference. Search field/action stack when their width budget cannot fit; pending
list controls, pager controls and the task title/back group wrap. Text is not
shrunk. This is source/model coverage, not a screenshot or device visual pass;
code4 does not contain it. Stable result/task keys, materialized page order,
callbacks, unknown progress, rights/verification and success gates are unchanged.

Validation: artifacts/ui/discovery-layout-20261012/RESULT.json records44 unique
JVM passes (layout7/filter26/save-policy11). Both Kotlin variants compiled after
the layout changes; the final English count-label-only correction reused their
UP-TO-DATE results. Both final lint variants have58 warnings/7 hints,0 error/fatal;
the newly introduced plural warning was corrected without suppression. Strict
runtime admission remains90 exact artifacts. The initial nonexistent save-test
filter did not execute and is not counted; the correct11-test class passed in a
separate run. No APK, device, media, public-site or physical-power step was run.

Specific remaining implementation gaps that do NOT require external-site/media
permission to develop or model-test:

| Reference | Concrete missing layout/interface work | Dependency distinction |
|---|---|---|
| PPT8 / C-03,C-04 | Expanded discovery still uses a modal options dialog rather than the collapsible filter side panel | Can reuse existing filter state/intents; no source activation is required for layout/model work |
| PPT12–14 / C-06 | Tasks still shows all detail inline in one list; separate selected-task detail and expanded list/detail composition are absent | Existing stable task IDs/records suffice for source binding and owned metadata model tests; a real completed task/output is needed only for actual save/output acceptance |
| PPT11 / C-05 | Wide browser inspection/selection still uses modal selection rather than a co-present inspector | Source composition can reuse the same session/revision-fenced selection state; real inspection requires an admitted owned page/media fixture and execution permission |
| PPT6–8 / UI-R04 | Compact-height discovery header/status/IME and pager still lack a complete vertical-space budget | Can implement bounded scrolling/collapsing using the same controls and snapshot; actual keyboard/200%/compact-height screenshots are separate device acceptance |
| C-03 | Discovery thumbnail area currently renders a theme icon placeholder; approved loading/unknown/failed thumbnail presentation and reader binding remain | Owned local fixture/reader contracts can be developed without public fetching. Arbitrary real thumbnail URLs need source/rights/admission review; reference PPT photos remain unlicensed runtime assets |
| PPT17 / C-08 | Complete expanded editor preview/segments/inspector composition is not accepted | Layout source can use existing typed UI state; real decoding/encoding needs verified owned media and permitted Tools capability |

Standard control styles remain existing Material3 adapters driven by one SkinKit
snapshot. No parallel palette, typography, component theme, dependency, reference
image extraction, fake progress, new entitlement or permission was introduced.
Contrast/TalkBack/focus/touch-target and full phone/tablet/font200 render matrices
remain concrete acceptance work; their lack does not block the source layout
items above or justify marking them complete.
