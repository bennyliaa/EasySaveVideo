# UI capability integration ledger

Reconciled against main 46f59796e770c3974d93cffa5aeae05eac587003 plus the bounded
Browser wide inspector source increment. COMPLETE SOURCE
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
| PPT8 / C-03,C-04 | SOURCE IMPLEMENTED: collapsible Expanded filter pane and Compact modal share movable content, existing draft/controller and scroll state | Actual rendered transitions and focus acceptance NOT RUN |
| PPT12–14 / C-06 | SOURCE IMPLEMENTED: selected stable task ID resolves current snapshot; Expanded list/detail, Compact original full action cards | Missing/duplicate ID never guesses; actual task/output acceptance NOT RUN |
| PPT11 / C-05 | SOURCE IMPLEMENTED: Wide webpage/candidate inspector; Compact modal shares movable composition and runtime selection | Actual page/media inspection and render transitions NOT RUN; HLS retains existing modal; settings/bookmarks force single-modal fallback |
| PPT6–8 / UI-R04 | SOURCE IMPLEMENTED: bounded scrolling header/pager, minimum content space and conservative low-height/large-font fallback | Keyboard/font200/compact-height render matrix NOT RUN |
| C-03 | Discovery thumbnail area currently renders a theme icon placeholder; approved loading/unknown/failed thumbnail presentation and reader binding remain | Owned local fixture/reader contracts can be developed without public fetching. Arbitrary real thumbnail URLs need source/rights/admission review; reference PPT photos remain unlicensed runtime assets |
| PPT17 / C-08 | Complete expanded editor preview/segments/inspector composition is not accepted | Layout source can use existing typed UI state; real decoding/encoding needs verified owned media and permitted Tools capability |

Standard control styles remain existing Material3 adapters driven by one SkinKit
snapshot. No parallel palette, typography, component theme, dependency, reference
image extraction, fake progress, new entitlement or permission was introduced.
Contrast/TalkBack/focus/touch-target and full phone/tablet/font200 render matrices
remain concrete acceptance work; their lack does not block the source layout
items above or justify marking them complete.

## Responsive filter and selected-task source increment

Discovery and Tasks now use remaining width, height, text scale and SkinKit gaps
for conservative split-pane selection. Layout changes issue no query, save or
source-enable command. Discovery moves the same filter composition between its
sidebar and dialog with movableContentOf; the existing controller owns filter
state. Tasks saves only a selected task ID and resolves the latest record snapshot.
Missing or duplicate IDs display no guessed record. Compact preserves existing
action paths. Original filter and task-detail control bodies match the baseline
after indentation normalization (CONTROL-BODY-COMPARISON.json).

Scoped validation:75 JVM passes, both Kotlin variants compiled, both final lint
variants passed without Error/Fatal, strict runtime graph90. The first lint run
failed on SuspiciousIndentation; corrected source and failed evidence are retained
in artifacts/ui/responsive-panes-20261012. Source/model tests do not prove actual
window transition state, focus, TalkBack, font200 or phone/tablet rendering. Those
are NOT RUN. No APK rebuild or device operation; installed code4 remains based on
d9bdf345 and excludes this group. Wide browser inspector, thumbnail reader and
expanded editor remain pending; real media/save/output acceptance remains open.

## Browser candidate inspector source increment

Wide Browser now co-presents the webpage and the original detected candidate
controls. Compact/low-height/large-font windows retain a modal; settings or
bookmarks also use a modal fallback to avoid three panes. One movable composition
uses the existing runtime selection and callback, with no copied domain state.
MainActivity owns the selection dialog only outside the Browser route; HLS keeps
its existing modal path. Back dismisses the inspector before page navigation.
Inspection is disabled during scanning, candidate selection or HLS preparation;
layout changes never inspect, navigate or save. Original candidate body matches
the previous source after indentation normalization. Runtime page/tab/session/
epoch/view/revision/expiry, identity, destination and immediate acceptance-clear
fences are unchanged. Zero-provider address navigation and Cookie/incognito code
are unchanged. No dependencies, network thumbnail loading or editor expansion.

Validation: 36 scoped JVM passes, double Kotlin compile and lint PASS
({'Warning': 58, 'Hint': 7}); exact runtime graph90. See artifacts/ui/browser-inspector-20261012.
Actual rendering, window/state transitions, font200/TalkBack and real save/output
acceptance NOT RUN. No device/APK step; code4 remains frozen on d9bdf345.
