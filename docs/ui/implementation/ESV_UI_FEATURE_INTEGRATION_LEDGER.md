# UI capability integration ledger

Reconciled against main 7f8bd6b11c77e9b253570438dfccc411bbb01bc5 plus the bounded
Editor SavedState checkpoint / cold-recovery source increment. COMPLETE SOURCE
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
| PPT17 / C-08 | SOURCE IMPLEMENTED: wide preview / segments / inspector; Compact/low-height/font fallback; explicit verified-reference read-only inline preview | Native read/playback/release, Tools/new-output and actual render transitions NOT RUN; PPT pixel parity not accepted |

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

## Editor existing bindings and bounded control adaptation

Input selection already connects Library current item snapshots and verified
recorded-output exact references to AndroidEditRuntime.open. EditorScreen already
connects draft selection/ordering/time/crop/compression, submit, phase, cancel and
preview to real controllers. Only EditRunResult.Published supplies SUCCEEDED and
a completed MediaReadRef; verified preview uses the controlled Library reader.
No placeholder edit algorithm or fake completed output is added. Originals remain
untouched by the existing new-output workflow. The current feature mapper and
FeatureGate require the relevant VIDEO_CROP/MERGE/COMPRESS capabilities; source
admission is inherited, while actual entitlement and native execution are NOT RUN.
Rejecting Tools is reported through the existing runtime notice, never bypassed.

This group adapts four action/time-input pairs to actual width and font scale,
stacking without reducing text. It extracts the unchanged successful-publication
preview condition into a tested policy. Input controls and cancellation callbacks
retain their previous gates. Protected runtime/engine/workflow/storage/verifier,
recording bridge, MainActivity bindings and dependency files are unchanged against
766789ff93ea60bb2dd6164903f45a9a2f2cbe98. See BINDING-AND-ADMISSION-CHECK.json.

Validation: 48 scoped JVM passes, both Kotlin variants compiled and lint
passed ({'Warning': 58, 'Hint': 7}), strict graph90. Evidence:
artifacts/ui/editor-controls-20261012. Full PPT17 inline native preview / expanded
segments-and-inspector composition remains future source and rendering work, not
closed by this increment. No private media, device capture, native processing,
real published output/preview or Tools acceptance was performed. No APK/device
step; code4 remains frozen on d9bdf345.

## Editor inline preview and panel source increment

PPT17 text and framework section3/C-08 require preview, segments and an inspector;
new outputs preserve originals and execution retains Tools admission. Existing
System MediaPlayer / exact Library reader / resource exclusion and actual-release
behavior are reused, without a new dependency or codec/edit algorithm. The native
player body is byte-identical to the previous source. No PPT assets extracted.

Preview begins only from a user preview action on the selected input with verified
display geometry, or the successful published output reference. There is no
automatic first-item read or autoplay. Current catalog authority is still checked
by the existing reader. Background, processing, blocked or invalid selection
retires preview; close, draft changes, crop, submit, cancel and navigation also
retire it. Disposal/Surface destruction uses the existing close protocol: actual
native release, not timeout, relinquishes descriptors/ownership. Unconfirmed
release retains the sticky recovery fence. Reopening requires an explicit action.

Wide panes share one runtime draft, submit latch and selected stable segment ID.
The preview movable composition is called once in either layout; Compact keeps
its bounded preview outside lazy-list caching. Header/preview budgets account for
SkinKit spacing, with remaining space for scrolling controls. Low height and large
font conservatively collapse three columns. Width changes issue no edit task,
probe, save or draft command. Existing crop/quality/source controls and Tools gates
remain in place; preview shows originals or published output, never an unrendered
edit result.

Validation: 54 JVM passes, dual Kotlin compile/lint PASS
({'Warning': 58, 'Hint': 8}), paired6 resources, exact runtime graph90. Evidence:
artifacts/ui/editor-panels-20261012. The first compile receiver error and corrected
run are preserved. Source/model coverage does not prove actual native playback,
release, window/state transfer, pixel parity, font200/TalkBack or real Tools/output
acceptance: all NOT RUN. No device, private media, recording or APK step; code4
remains frozen on d9bdf345. Earlier future-layout notes are historical: this group
implements the panel/preview source binding, while execution/render acceptance
remains open.

Updated reconstruction status: bounded SavedState draft checkpoint/cold restore
is now SOURCE IMPLEMENTED by the following group. Correction: AndroidSaveRuntime
is a process singleton; same-process Activity rotation could already retain its
editor owner/draft. The previous note overstated this as an Activity-wide gap.
Actual Android recreation remains NOT RUN. Preview requests stay ephemeral.

## Bounded editor draft checkpoint and cold recovery

MainActivity saves a versioned pure-data checkpoint, capped at8KiB decoded /
12000 encoded characters and the existing segment limit. It includes exact
MediaReadRef IDs/revision/generation, stable segment IDs/order/selection, time,
crop and quality parameters plus prior-busy feedback; no names, paths, output,
player, Context, descriptor, permission or task owner is serialized. Pending
metadata recovery is also retained across saving before it finishes.

The application singleton/current owner always wins over old SavedState. Cold
restore waits for existing journal recovery, resolves exact READY/current catalog
metadata and source bounds, then evaluates fresh Tools FeatureGate/capabilities.
It creates only an unverified draft: no geometry, completed reference, execution
ranges, restored task phase or success. Native profile review waits for a new
user segment/draft/submit action. Restore never opens a reader, probes, plays,
requests a destination/permission or starts a task. Old missing/deleted/changed
refs fail closed, with no fallback item. Invalid/unavailable/capability/interrupted
results have paired explicit feedback. Original files remain untouched.

A mutation token fences asynchronous restore against every newer user selection,
change, submit or cancel/back, even if the user subsequently clears the draft.
Busy work stays with its existing owner and foreground/publication fences; old
checkpoint state cannot duplicate or resume it. This is not an automatically
resumed editing task or output recovery guarantee.

Validation: 61 scoped JVM passes, dual Kotlin compile/lint PASS
({'Warning': 58, 'Hint': 8}), paired9 resources, strict graph90. Evidence:
artifacts/ui/editor-restoration-20261012. Pure tests cover parameters/order/selection,
malformed/bounded payload, stale/deleted/missing refs, metadata-only resolution,
new-choice/cleared-draft precedence, single admission, interrupted/capability
feedback and unexecutable partial input. Android Bundle/Activity/process recreation,
actual Tools/native media/output/render acceptance remain NOT RUN. No device,
private-media read, new library/DB/SDK, recording or APK step; code4 remains frozen.


## Final source closure: existing media-library thumbnails

Baseline: 6279da19f6dc361f81cbd75140556a06332ab0c8. Existing library index,
thumbnail decoder/cache and exact published-reader/native-release gates are reused.
The UI now defaults to placeholders and requires an explicit Load visible thumbnails
action. Only actual grid viewport media keys admit requests; lazy prefetched or
disposed items cancel queued work and flag active work for cancellation. Active
ownership and watchdog remain until real completion; completed frames stay only in
the existing bounded page LRU. Background resets the ephemeral opt-in. Modal,
move, loading/busy and native-release gates suspend admission. No network image
loader, scan, permission, dependency, private-media access or replacement engine.
Nine paired English/Chinese resource keys cover controls, accessibility and feedback.
Preview/export/edit callbacks keep the existing exact current-reference and Tools
gates; no failure produces fabricated media or success.

Validation: 55 scoped JVM passes, Debug/Release Kotlin compile PASS and dual
lint {'debug': {'Warning': 58, 'Hint': 8}, 'release': {'Warning': 58, 'Hint': 8}}. Evidence: artifacts/ui/final-library-closure-20261012. No full-suite,
real media, Android rendering, device or APK acceptance is inferred from these tests.

### Completed source wiring in this review round

- Discovery adaptive grid/filter panel, Tasks stable-selection list/detail and
  Browser shared wide candidate inspector/compact dialog reuse existing policies.
- Editor adaptive controls/panels, explicit existing native preview and bounded
  pure-data cold draft checkpoint are connected; current singleton owner wins.
- Library existing reader/thumbnail module is connected with explicit viewport
  admission, offscreen cancellation and bounded cache; preview/export/edit remain
  real capability/current-reference actions.

### Remaining source gaps and latest-package acceptance

- Updated by the next local source group: Wide Library list/detail panes are
  SOURCE IMPLEMENTED. Header/grid typography and full docs/ui pixel parity
  remain unaccepted; the earlier modal-only source gap is now closed.
- Discovery thumbnails remain theme placeholders until an admitted source/reader
  contract exists; no network loader or permission is invented to fill them.
- A future authorized package must have versionCode greater than4, compatible
  signing, minSdk31 and preserve data. Installed code4/source d9bdf345 stays frozen;
  the source changes in this review round have not been installed or device tested.
- On that package: Compact/Medium/Expanded and low-height/font200/TalkBack,
  viewport cancellation/cache/render behavior, background/navigation/Activity and
  cold-process recovery, actual native release, stale references, preview,
  export destination/cancellation, Tools output and recording consent need explicit
  device acceptance. CPU/RAM/power/network/total time are NOT MEASURED.

### Test-material and configuration dependencies

- Current tests use pure models/fake frames/catalog references, not private media.
  Future native acceptance requires explicitly approved local synthetic media and
  compatible Tools/native capabilities; export requires a user-selected destination.
- Public-source benchmarks require per-source permission/robots/rate-limit checks;
  local fixtures do not prove public-site parsing or the100-labelled A/B corpus.
- Store billing remains unconfigured; Vault and dynamic interpreter work remain
  shelved. No test result or UI completion enables these features.

This is a recoverable source checkpoint with finite validation, not final phone,
performance, public-web or complete design acceptance. No device/APK/private-media
operation was performed in this group.


## Local follow-up: wide Library list/detail panes

Baseline08ec37b5fbf759a78f4e2198cad6f54b33469d42. Actual available width/height,
SkinKit spacing and font scale decide whether the existing folder/media list and
shared metadata/action detail body appear side by side. Large fonts or low height
fall back to Compact's existing modal path. One runtime selection and one lazy-grid
state are shared; no first/neighbor item is selected, and resize issues no catalog,
reader, preview, edit or output command. Detail is metadata only and independently
scrolls. Back clears selection before folder/route navigation.

Explicit Wide refresh of the same first-page query retains the chosen stable ID.
The accepted current snapshot updates revision/generation/name/counts. Missing or
non-READY selection keeps that identity as missing, displays paired feedback and
offers only explicit close; no adjacent-row fallback. Failed/loading snapshots
offer no media actions. Current-reference consumption is unchanged and single-use;
rename/move/delete admission also requires the exact current snapshot. Dismissal,
background or changed query clears identity; stale async results cannot select it
again. Compact default refresh still clears selection. Deletion/name consent dialogs
stay modal and are never restored or rebound by refresh.

The previously admitted explicit thumbnail load/visible-item/cancel/cache/native
release policy is reused unchanged. A wide metadata selection is not a modal;
actual consent dialogs/move/busy/loading still suspend thumbnails. No second
thumbnail renderer, private-media read, network loader, scan, permission or new
dependency. Discovery stays a truthful placeholder pending admitted Provider URL/
reader/network contracts.

Validation: 38 scoped JVM passes, dual Kotlin compile PASS, dual lint {'debug': {'Warning': 58, 'Hint': 8}, 'release': {'Warning': 58, 'Hint': 8}};
four paired resources and strict runtime graph90. Source proof records unchanged
native modules, reference consumption, durable mutations and shared detail actions.
Evidence: artifacts/ui/library-panels-20261012. This is model/source acceptance only.
Actual render/resize/font200/TalkBack, native media release, Tools/export/recording,
latest-package acceptance and100-sample public A/B validation remain NOT RUN;
phone CPU/RAM/power/network/total time remain NOT MEASURED. Future native tests
need explicitly authorized synthetic local media, existing compatible Tools and
user-selected export destinations. Code4 stays frozen, minSdk31 unchanged, no APK
or device operation. This checkpoint is local only: prior main push rejection was
respected; no push or alternate upload was attempted.


## Post-code5 local increment: ordinary video multi-selection

Base:c18cb37a22a51f3af962ebaf0fd9eefcb33e4534. This source increment is NOT in the
installed/signed code5 and is not repackaged, installed or uploaded. Original UI
framework lines36/57/93 require Back to exit multi-selection, responsive toolbar,
and existing media operations; PPT traceability line34 explicitly requires stableID
multi-selection. This confirms original scope rather than adding video import.

Explicit Select videos enters an ephemeral current-page mode; checked items store
stable IDs plus exact revision/generation and query snapshot, capped100. Current
metadata reconciliation removes missing/deleted/ambiguous/changed refs, without
rebasing consent or selecting neighbors. Query/folder/sort/page changes and
background exit mode; command admission independently fences the old query even
before Compose effects run. Back exits mode before folder/route navigation. Clear
and cancel only change selection, never mutate files or cancel unrelated jobs.

Compact and Wide share one selection owner and a bounded scrolling/wrapping toolbar
outside the lazy media list. Checkable rows use actual checkbox semantics and48dp
minimum target. Current-page select/clear/count/exit work at zero or many selections.
Only exactly one current foreground selection admits existing detail/rename/move/
export ports. Export requires its real callback; all current busy/loading/consent/
move/ref fences remain. Opening details exposes original preview/edit/delete gates,
including explicit deletion consent; no bulk delete. Multiple-item batch operations
are visibly unavailable, with paired explanation; no UI loop fabricates atomic or
successful batching. Folder multi-selection and domain batch coordination remain
unimplemented; ordinary folder operations are unchanged.

Validation: 46 targeted JVM passes, dual Kotlin compile/lint PASS {'debug': {'Warning': 59, 'Hint': 8}, 'release': {'Warning': 59, 'Hint': 8}},
strict graph90 and11 paired resources. Evidence: artifacts/ui/library-multi-selection-20261012.
Seven source proofs confirm unchanged MainActivity, runtime ports, native thumbnail
modules, build version and shared detail/mutation body. The first run passed; a
separate final run covers the added query-fence regression. Test results are model/
source evidence, not post-code5 Android, native media or full-project acceptance.

Code5 bounded device observations remain separate in local delivery evidence:
fresh Settings root-Discover dispatch did not leave Settings even before resize,
whereas system/visible Back returned Discover. Cause remains unconfirmed; current
root save/restore behavior is a review candidate. No navigation repair is claimed
here. Original Direct/Stream/Recorder/Editor publication remains the intended
media chain. New local import is deferred, not classified as original-spec defect.
Vault/rules remain shelved. Independent review is PENDING before batch source sync.


Pause checkpoint: current English count copy changed to Selected:count after the
completed46-test/dual-compile/dual-lint run. The last copy/resource lint run was
INTERRUPTED by the latest pause instruction; full final current-copy validation
is not claimed. See artifacts/ui/library-multi-selection-20261012/CHECKPOINT-STATUS.md.
No source sync/package/install; review and writer-state confirmation are pending.


## Multi-selection final validation after WIP pause

Exact tested source:fb87af58431f189776cd3724972a8c5a0ec2d449. Old Java processes
were absent and the worktree clean before one serial run. The final English count
copy and all current multi-selection code now pass46 actually executed scoped JVM
tests, Debug/Release Kotlin checks and dual lint {'debug': {'Warning': 58, 'Hint': 8}, 'release': {'Warning': 58, 'Hint': 8}}. Compile task cache outcomes
are preserved verbatim; a requested UP-TO-DATE check is not fresh compilation.
Strict graph90 verified; no new source/business/dependency changes were needed.
Evidence: artifacts/ui/library-multi-selection-20261012/completion. Earlier WIP/
interrupted logs are immutable historical evidence, superseded for final-source
validation by this successful run. Post-code5 device UI/native/real batch operation
acceptance is still NOT_RUN. The source increment does not enter installed code5;
no APK/install/device change, navigation implementation change or upload occurred.
Independent review remains PENDING before batch source sync. Import/Vault/rules
remain deferred; original publication paths remain the media acceptance route.


## Independent review P2: shared Library Back priority

Review found visible page Back bypassed active multi-selection while system Back
exited it first. Both now call the same current-state handleBack dispatcher, with
consent dismissal before multi-selection exit, followed by detail/move/folder/route
actions. Exiting selection clears only ephemeral consent and issues no business
command. Three new regressions cover two selected items through both ingress paths,
multi-selection precedence and existing ordinary Back actions. Source/model49 JVM
passes, dual compile/lint PASS; task/cache evidence and unchanged runtime/MainActivity
proof are in artifacts/ui/library-back-p2-20261012. Actual post-code5 Android Back
is NOT_RUN. Multi-item move/export/bulk delete remain UNIMPLEMENTED and unavailable;
this is not complete multi-selection business acceptance. Code5 has no P2 fix.
No production navigation, APK/install or upload change. Separate Nav QA scaffolding
is not included in this P2 commit and its default opt-in remains off.


## Original specification batch move checkpoint (2026-10-12)

Original UI PPT slide18 explicitly requires move/share after selection; UI framework
line93 and traceability matrix PPT18 require stable identities and existing ports.
This increment implements bounded multi-item MOVE through existing LibraryRepository
CAS operations on the existing worker. Exact READY/revision/representation snapshots
are admitted on the current foreground page and rechecked before each write. Each
item has a separate durable operation ID; duplicate confirmation is blocked. This
is sequential per-item mutation, not an atomic group or a rollback promise. Cancellation,
background and disposal stop unstarted items, allowing a started write to reconcile.
Completed, already-at-target, stale, unavailable, unconfirmed and cancelled-before-start
outcomes remain distinct, with snapshot names and per-item results shown locally.
Exceptions after a committed write remain unconfirmed; no automatic retry/reselection.

52 actual JVM tests PASS after one recorded resource-key-count failure was corrected.
Debug/Release Kotlin checks and Debug lint PASS; exact task/cache outcomes and lint
counts are in artifacts/ui/library-batch-move-20261012.
Lint has59 warnings/8 hints/0 errors (one new PluralsCandidate for the English
selected-video count; the paired format test passes). These are not a zero-warning gate.
Batch sharing/export/delete remain UNIMPLEMENTED; this is not complete PPT18 or Android media acceptance.
No import, Vault, rules, real files, device operation, production APK or upload.
Independent review/sync PENDING. This checkpoint is sealed before the navigation
repair requested after independent v2 runtime evidence. Production ESV ANR is a
separate UNKNOWN defect; no relationship to v1 missing classes is claimed.


## Minimal auxiliary-to-root repair and isolated carrier v3 (2026-10-12)

Independent v2 real NavController evidence reproduced all6 auxiliary-to-current-root
failures with the original options while marker17 remained, and same-root, Back/pop
and Activity restoration worked. See the reviewer nav-v2/MATRIX_RESULT.md and
matrix-complete.jsonl; this is narrower than production acceptance. Batch move was
sealed as073d80d with52 passing fake/model tests before this separate repair.

Production root navigation now discards known auxiliary entries with ordinary pop
before reusing the existing root save/restore options. If popping reaches the requested
root, it returns by pop alone. Roots retain their saved state. A restored old auxiliary
tail is discarded without clearing the restored root; a graph recreated with no live
root cannot save its auxiliary as a root. Pop count is bounded32; blocked/oversized
chains return false and never claim the requested route. Same-route admission,
foreground source gating and ordinary Back are unchanged. No universal restoreState
disable, global clearBackStack or graph clearing of live roots was introduced.

Shared platform-neutral RootNavigation source is compiled by production and diagnostic
v3 via the official AGP AndroidSourceSet.kotlin configuration. The v2 original function
is byte-identical; original and fixed carrier modes/matrices remain separately callable.
Fixed matrix contains baseline6 and12 auxiliary-to-other-root cases with actual
destination callbacks/current/previous/presence and synthetic marker snapshots, then
same-root and Back. Manual PREPARE_FIXED_RECREATE_AUX/RECREATE_ACTIVITY and rotation
check the owned marker after restore. A separate nav-observations-v3.jsonl with
carrier_version3 preserves the earlier carrier's log. No automatic runtime PASS is
fabricated by source completion. Current/previous/presence is not an ordered stack dump.

17 actual scoped JVM tests PASS; Debug/Release compile checks, production Debug lint,
diagnostic Debug APK build and lint PASS. First two source-set configuration failures
are retained; final logs include exact executed/cache outcomes. App lint59W/8H/0E;
carrier lint and static manifest/signature/SDK/hash/DEX proof are in the result/receipt.
Carrier package/version3/min31/target36 and preexisting compatible diagnostic signer
are verified; production version5/signing configuration/permissions are unchanged.
All64 carrier runtime artifacts remain an exact subset of prior90 admitted artifacts.
No new dependency, production APK, install, device operation or upload. Review and
original/fixed v3 runtime acceptance PENDING. Production ESV ANR remains UNKNOWN,
separate from both the navigation repair and diagnostic v1 Future VerifyError.


## Independent v3 review and ordinary batch share/copy (2026-10-12)

Independent review of073d80d/bb83a53 found no new definite P1/P2. Real isolated v3
original6/6 failed; fixed18/18 target routes and18/18 original root marker17 passed,
with same-root, ordinary Back, Activity recreation and rotation. Report preserved in
artifacts/ui/library-batch-share-copy-20261012/NAV_V3_INDEPENDENT_REVIEW.md; raw fixed
JSONL remains at reviewer nav-v3/matrix-complete.jsonl. Production code5 was not
updated by that test, and ANR is UNKNOWN. User now chose OPPO PDYM20/API31 for future
production acceptance, installed code3; emulator5556 is closed and must remain closed.
No device operation by the source author. minSdk remains31.

Original PPT slide18 requires sharing after selection. Product Requirements section
9.1/PR17 distinguishes explicit gallery/Files export and system share, prohibits silent
publication/upload, and preserves internal media after external failures. UI interface
contract section10 describes separate Commands/Tasks and says share revocation cannot
recall recipient copies. This increment implements two distinct explicit choices:

- Gallery copies use the existing ExportCoordinator/MediaStore port and separate durable
  per-item operation/target IDs. Only matched SUCCEEDED/verified records count as copies.
  Stale/unavailable sources, cancelled unstarted work, cleanup-pending and uncertain
  outcomes remain per-item. Exceptions do not imply rollback. Already published copies
  survive cancellation; no atomic group success, overwrite or automatic batch replay.
- System sharing keeps the exact prior4-copy/256MiB each/512MiB total/10-minute TTL,
  8-opens/2-active-transfer/proxy-issuance bounds. All selected copies must prepare or
  no chooser opens; there is no silent success-subset grant. Group revocation addresses
  only that group's operation ID. Every group's logical reads stop before any framework
  revocation/cleanup, so a failed first item cannot leave later reads live. Actual release
  and cleanup uncertainty remain distinct. Read-only SEND/SEND_MULTIPLE plus matching
  ClipData contain only issued ordinary-copy URIs, never original/private file descriptors.
  No write, persistable or prefix grants. Chooser-open never means recipient delivery.

Selection freezes exact READY IDs/revision/generation and query plus original names;
page/source/modal/lifecycle changes invalidate admission without rebasing consent. Batch
preparation and queued copies cancel on background, disposal or leaving Library; current
accepted work may reconcile. Chooser launch rechecks foreground/exact snapshot and a
monotonic expiry value, with no main-thread owner lock/filesystem work. Capabilities and
Intents exist only in worker/launch closures, not Compose state or saved state. Repeated
launch/preparation is blocked. An opened chooser retains bounded authority until TTL or
explicit revocation; recipient-owned bytes cannot be recalled and chooser dismissal is
not a delivery receipt.

Batch Files remains pending the explicit target-selection decision (one directory grant
versus per-item CREATE_DOCUMENT); no target/grant is guessed or reconstructed. Existing
single-item Files and other single-item export routes remain. No import/Vault/rules,
new dependency/permission/security setting or real media read was added or performed.

121 actual directed JVM/model/fake-port tests PASS, including existing verifier/proxy/FD
release/export recovery tests; Debug/Release compile and Debug lint PASS (59W/8H/0E).
All intermediate/final task/cache results are preserved. Real Android URI grants,
recipient reads, gallery writes, lifecycle/permission revocation and performance remain
NOT_RUN, for future scoped OPPO acceptance. Source-independent review/sync PENDING.
Production code6 unsigned Release preparation follows only after this local checkpoint;
the preexisting compatible signer is on the original cloud route, not a new local key.


## Production test package code6 local preparation (2026-10-12)

Batch share/copy was independently sealed as a125681d5ecad3cfd6c98f62531247dd613d15ba
before packaging. afca2ab8d2d7748550c009d50eb3e4d0e6cb6a70 changes only versionCode5→6
and versionName0.5.0→0.6.0-ui-dev. Unsigned Release assemble/lint PASS (59W/8H/0E),
strict prior90 runtime gate unchanged. Official SDK manifest/alignment/DEX checks
confirm app package/min31/target36/version6, production RootNavigation/ShareBatch/
BatchGalleryCopy/Future definitions and no diagnostic package. Notices match source;
no credentials, Python/Scrapling runtime or media fixtures were packaged. Fixed APK
35399390 bytes, SHA2564447879b3785211c02cc1884165fe92250562e263a55728f4fafe5af8d4f9079.

This APK is UNSIGNED and must not be installed. It is staged for the existing cloud
signing route, expected compatible signer18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5.
No replacement local signer/key or credential was created or exported. Matching the
fresh installed OPPO code3 certificate, signed artifact hash/signature/alignment and
increasing version must be verified before a separately scoped data-preserving install.
The author performs no OPPO or emulator operations; emulator remains closed. APK
preparation is not production UI/native URI/grant/battery acceptance, and ANR is UNKNOWN.
Independent batch review and source sync PENDING; original/fixed isolated v3 results
remain historical evidence, not substituted for production OPPO navigation validation.
Receipt/build evidence: artifacts/ui/production-code6-unsigned-20261012. Fixed binary
and public SDK checks remain in delivery-dc69-20261010/unsigned-ui-code6-afca2ab-20261012.
