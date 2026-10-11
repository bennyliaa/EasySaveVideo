# UI capability integration ledger

Reconciled against main c0920b78d2ec16246b28e1fdb89370f77a5ec277. COMPLETE SOURCE
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
