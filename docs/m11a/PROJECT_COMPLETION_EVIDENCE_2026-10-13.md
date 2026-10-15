# ESV completion evidence and minimum next steps

Checkpoint: 2026-10-13 UTC. Purpose: reconcile original Update1 milestones with
actual source, tests, publication and limited device observations. This is a
status document, not a release approval, new requirement, legal review or device run.
Original requirements and historical evidence are unchanged.

## 1. Source baseline and what “published” means

Latest verified source publication: [dbff3f4e5452fa3470de77ae1eb974f43a221d90](https://github.com/bennyliaa/EasySaveVideo/commit/dbff3f4e5452fa3470de77ae1eb974f43a221d90),
on `development/cloud-followup-20261013`, batch confirmation/Library-exit recovery
(A/B34). Its direct parent is published Editor Back confirmation
[c26df3f338ed6b183438960ea1beb7b5d6dad300](https://github.com/bennyliaa/EasySaveVideo/commit/c26df3f338ed6b183438960ea1beb7b5d6dad300),
whose parent is `24bc104a51e1b87dc2752c01bd616be29b44dc08`.
The two publication receipts verify **34 and 40 exact changed-blob readbacks**; the
A/B receipt additionally verifies the preceding 40 blobs. The 74 scoped changes are
not a whole-tree equality claim or test count. Both receipts retain no deletion or
forced ref update. Main remains `ea87963dee631a498774f648c9a1dabd9f665b90`; the repository
remains private. Publication is not a main merge, installation or release acceptance.

The code8 Debug APK built from c794ef remains unchanged and **local-only**, with no
upload or install. It predates every later published or pending slice. Editor
destination-attempt acknowledgement is published in `24bc104a` (App2,050/+24); its failed
first attempt and reviewed semantic-test correction remain preserved. Editor Back is
published in `c26df3f3` (App2,077/+27), and A/B recovery in `dbff3f4e` (App2,100/+23).
Native acceptance remains NOT_RUN. Already published source scopes must not be relabeled
as pending, even when an immutable slice report retains its pre-publication wording.

Current integrated **13-module JVM regression completed: 3,551 registered / 3,549
passed / 2 private-corpus skipped / 0 failures / 0 errors**. A separate new local Debug
APK was built and statically checked, with matching existing signer and passing zipalign.
Both bind source input manifest SHA-256
`592edc3376718657105082bbab834b586b0e418a6fcd2b0ecef8cdb32e9219aa` and the full pending
production chain below. This source directory has no Git history; neither Git HEAD nor
remote dbff3f4e identifies all later local source. Detailed executed/associated/not-run
boundaries and exact APK identity are in §2. The independent static report/evidence
package is being finalized; no final freeze manifest is claimed before it exists.
The candidate is local-only, not uploaded/installed or release/device accepted. The old
c794ef artifact is unchanged. New evidence remains outside the source tree; this refresh
changes only the two current ledgers and itself creates no test result.

The following later publications supersede the former `2986ada` editor-default checkpoint:

| Published commit | Bounded scope | Publication receipt |
|---|---|---|
| [172fa3c](https://github.com/bennyliaa/EasySaveVideo/commit/172fa3c443431a302658a64e3f3dd35abae41dbb) | Wi-Fi-only source enforcement plus documentation; 79 changed files, 79 exact readbacks | `/workspace/shared/esv-wifi-publication-20261013/SUMMARY.json` |
| [93a67ba](https://github.com/bennyliaa/EasySaveVideo/commit/93a67ba5a4e07cb6c9b1b7d186c6145570db19e0) | BrowserScreen bilingual settings/origin/error copy; 29 changed files, 29 exact readbacks | `/workspace/shared/esv-browser-copy-publication-20261013/SUMMARY.json` |
| [82ccae9](https://github.com/bennyliaa/EasySaveVideo/commit/82ccae9dfb4d0c70ecca689a0a65aa259434603b) | Current storage/checkpoint/owner contracts and visible Shell navigation labels; 31 changed files, 31 exact readbacks | `/workspace/shared/esv-contract-shell-publication-20261013/SUMMARY.json` |
| [8fa0c4a](https://github.com/bennyliaa/EasySaveVideo/commit/8fa0c4a2b9720f2098e64ca2ff93a599d4856833) | Save display locale, semantic theme tokens and nine recovered original UI Markdown documents; 62 changed files, 62 exact readbacks | `/workspace/shared/esv-locale-tokens-originals-publication-20261013/SUMMARY.json` |
| [3176bcc](https://github.com/bennyliaa/EasySaveVideo/commit/3176bcc0be84325be5b278823995761f17049dd8) | Discovery body states and input clear; 48 changed files, 48 exact readbacks | `/workspace/shared/esv-body-clear-publication-20261013/SUMMARY.json` |
| [62b5f6d](https://github.com/bennyliaa/EasySaveVideo/commit/62b5f6d4076fe1ea4ec727ae4dcd87c9113697e3) | Authentication-waiting return to current Browser and 26-pair fixed destination copy; 33 changed files, 33 exact readbacks | `/workspace/shared/esv-auth-destination-copy-publication-20261013/SUMMARY.json` |
| [795b6bb](https://github.com/bennyliaa/EasySaveVideo/commit/795b6bb9dc0353be55682a53d402bf6773aed9e8) | Typed destination notices/copy status and Discovery inline/floating filter roles; 42 changed files, 42 exact readbacks | `/workspace/shared/esv-destination-filter-publication-20261013/SUMMARY.json` |
| [b0c5647](https://github.com/bennyliaa/EasySaveVideo/commit/b0c5647e6759dd212423647742e4923c8d0dce93) | Generated candidate display and typed denied-permission notices; 30 changed files, 30 exact readbacks | `/workspace/shared/esv-candidate-permission-publication-20261013/SUMMARY.json` |
| [ef5d6fc](https://github.com/bennyliaa/EasySaveVideo/commit/ef5d6fc35328e8881db26df3982c0e675c37dd4d) | Full cloud regression evidence only; 16 files/readbacks, no production change | `/workspace/shared/esv-full-ui-regression-publication-20261013/SUMMARY.json` |
| [c794ef8](https://github.com/bennyliaa/EasySaveVideo/commit/c794ef84344d638f20966a1cd05bb5b340ade7e0) | Complete Save runtime notice typed-display channel; 32 files/readbacks including evidence ZIP | `/workspace/shared/esv-save-notice-publication-20261013/SUMMARY.json` |
| [55c51cd](https://github.com/bennyliaa/EasySaveVideo/commit/55c51cd71763159b203e52ece44f09da8cab1507) | Discovery reading anchors, exact display-only restore provenance and small c794ef packaging reports; 47 files/readbacks, APK excluded | `/workspace/shared/esv-anchor-candidate-publication-20261013/SUMMARY.json` |
| [4f22d43](https://github.com/bennyliaa/EasySaveVideo/commit/4f22d43d023b7db6e3051613ed02c144bf3ad75b) | Explicit process-local, same-origin, still-live original-page selection; 31 files/readbacks | `/workspace/shared/esv-auth-origin-publication-20261013/SUMMARY.json` |
| [1f25214f](https://github.com/bennyliaa/EasySaveVideo/commit/1f25214fcea79113b0278ac24ddc316e6910a9f0) | Existing recording foreground-notification text channel and bounded language refresh; 41 files/readbacks | `/workspace/shared/esv-recording-notification-publication-20261013/SUMMARY.json` |
| [24bc104a](https://github.com/bennyliaa/EasySaveVideo/commit/24bc104a51e1b87dc2752c01bd616be29b44dc08) | Editor destination-attempt acknowledgement; 42 files/readbacks, including preserved first-failure and final evidence ZIPs | `/workspace/shared/esv-editor-destination-ack-publication-20261013/SUMMARY.json` |
| [c26df3f3](https://github.com/bennyliaa/EasySaveVideo/commit/c26df3f338ed6b183438960ea1beb7b5d6dad300) | Editor Back confirmation/publication feedback; 40 files/readbacks; parent 24bc104a | `/workspace/shared/esv-editor-back-publication-20261013/SUMMARY.json` |
| [dbff3f4e](https://github.com/bennyliaa/EasySaveVideo/commit/dbff3f4e5452fa3470de77ae1eb974f43a221d90) | Batch confirmation/actual Library-exit recovery; 34 files/readbacks and predecessor 40-blob verification; parent c26df3f3 | `/workspace/shared/esv-batch-recovery-publication-20261013/SUMMARY.json` |

The Editor Back and A/B publication receipts, current slice reports and frozen manifests
were read for this refresh; earlier receipt-backed facts are retained as dated evidence. Receipt paths are local verification inputs,
not portable repository attachments; commit links and the in-repository slice reports
are the durable source references. Wi-Fi's original 77-file source/evidence freeze is
historical input to its 79-file publication group, not a still-pending publication.

Earlier increments include paging (`c83ebad`), native JS dialogs (`08ce295`), new-tab
draft transfer (`312007`), lightweight video hint (`63e929`), READY SPA/hash explicit
inspection (`dc2f421`) and editor-default compression (`2986ada`). Their reports remain
under `docs/ui/implementation/` and `docs/browser-js-dialogs/VERIFICATION.md`.
The shared source directory has no `.git` history and can contain later unshipped work;
do not infer that every current file belongs to the published checkpoint.

### Pending source chain and independent evidence bundle

Publish the following reviewed frozen production groups in order after dbff3f4e, or as
an explicitly reviewed union that preserves all their inputs. A group's direct commit
parent is unknown until its predecessor is actually published; the known ancestor must
not be substituted for that parent. File counts below are frozen delivery counts.
Manifest values are SHA-256 file hashes, not Git commit IDs.

| Order / group | Frozen manifest path | Manifest SHA-256 | Bounded executed tests |
|---|---|---|---|
| 1. Picker29 | `/workspace/shared/esv-picker-page-frozen-20261013/MANIFEST.json` | `461ef7ecfae37b7267feb06fb1069431b4837578f483b9871a27b22468eaad0a` | App2,120 / +20 |
| 2. Resume29 | `/workspace/shared/esv-save-resume-frozen-20261013/MANIFEST.json` | `f3f87a7bdacaf3df43d406707f219c20e8b297117d4b25ec611fdacf1c61a955` | App2,156 / +36 |
| 3. ExportRecovery37 | `/workspace/shared/esv-export-recovery-frozen-20261013/MANIFEST.json` | `47333eaabe848697ea5f22b94295167a7de442b953f2b23a73e2fc83c8a79524` | App2,201 / +45 |
| 4. UnadmittedSave48 | `/workspace/shared/esv-unadmitted-save-frozen-20261013/MANIFEST.json` | `9d1ce09cff0423445ac142c7f927ebaafbfe95bf7d66ad39ed44989a7f22fcf0` | App2,250 / +49 and Save120 / +25 |
| 5. ReadingReturn25 | `/workspace/shared/esv-library-reading-return-frozen-20261013/MANIFEST.json` | `35890a52b345b823cef86120061e1af87c68436a0ac6269a7e0733a5046cc12c` | App2,263 / +13; Save not rerun |

The independent **18-file** `/workspace/shared/esv-three-slice-regression-frozen-20261013/MANIFEST.json`
(SHA-256 `3da07302652117b70abe3362b4ce74cbf6881a382e996ae0ab65e40216df53a7`)
contains regression evidence/checklists only, **no production change**. It binds the older
EditorBack40 → A/B34 → Picker29 input chain and remains separately unpublished; it is
not a sixth production slice and cannot validate Resume29 or later inputs. This current
two-ledger refresh likewise awaits publication. Old freeze lineage/publication-hold text
is historical; neither frozen files nor old reports are rewritten by this status refresh.

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

Requirements describe desired behavior. Source-contract, JVM and host tests establish
only their checked conditions. Compilation/lint establish neither rendered pixels nor
native media correctness; publication readback establishes bytes, not installed behavior.

### Latest completed integrated regression and local Debug candidate

This run binds **1,453 production/test/script/build/dependency inputs** in
`/workspace/shared/esv-integrated-candidate-scratch-20261013/SOURCE_INPUTS.json`, SHA-256
`592edc3376718657105082bbab834b586b0e418a6fcd2b0ecef8cdb32e9219aa`.
It includes the complete Picker29 → Resume29 → ExportRecovery37 → UnadmittedSave48 →
ReadingReturn25 chain after the verified dbff3f4e ancestor. This is a tested local
manifest chain, not a claim that those pending groups are already on the remote or that
this APK was built from Git HEAD. No production correction was made for this run.

Actual JVM/runtime execution:
- All **thirteen module unit-test tasks and verifyRuntimeAdmission** were forced and
  actually executed. **337 fresh XML suites / 3,551 registered / 3,549 passed / 0 failures /
  0 errors / 2 skipped**. App **2,263**, Save **120** and ui-theme **17** freshly ran here;
  these are not borrowed from earlier slices. The two unavailable ESV_OWNED_CORPUS cases
  remain skipped, not passed private-corpus acceptance.
- Gradle exited 0 in **38 seconds**, 77 actionable tasks: **14 executed / 63 dependency
  tasks UP-TO-DATE**. Dual Debug/Release compile and lint were **not rerun**. They are
  associated from ReadingReturn25 using exact equality of all 1,453 inputs to its input
  manifest `3a07037079eeedb6abb592836280cf2b5b1c6926671b00fea09daf2575ea0135`.
  Associated lint: Debug **54 warnings/8 hints**, Release **55 warnings/8 hints**, zero
  errors. The missing six/five earlier GradleDependency notices are not claimed fixed.
- Exact runtime90 passed; the final unchanged compiled/source/ABI boundary checker
  passed **seven checks** after build completion. This boundary includes original
  SkinKit JAR hash/ABI checks, not an invented comprehensive new admission review.

Current host evidence and unchanged boundaries:
- Current SQL passed **14 tests / 344 rollback fault boundaries**; synthetic DOM
  **22 video-presence / 11 save-detector** checks passed. Host SQL uses extracted SQL
  and a checked transaction/helper model, not Android SQLiteOpenHelper or real legacy
  user-media backfill. DOM tests do not execute Android WebView or real sites.
- Historical admission: 20 scripts excluding the separately counted final boundary,
  **1 scoped pass / 16 missing Git / 2 missing evidence / 1 stale-anchor failure**.
  Including the boundary gives the historical 21-script convention of 2 scoped passes.
  Historical SQLite: **2 scoped passes / 12 stale-anchor failures** across 14 scripts.
  Thus **16 missing-Git, 2 missing-evidence and 13 stale-anchor FAIL** results remain;
  do not double-count the final boundary or call the repository all-green.
- The first save-detector invocation failed with the retained missing-output-directory
  ENOENT. An unchanged-source isolated-output rerun passed. No historical script or
  assertion was repaired/relaxed to erase the failures.
- Host inventory **1,918 files** and final compiled-output inventory **2,875 files**
  were unchanged through their checks. The host's final source hashes were captured
  before this results-only two-ledger update; the 1,453 build inputs remain unchanged.

New local APK identity and static checks:
- Path: `/workspace/shared/esv-integrated-candidate-scratch-20261013/apk/EasySaveVideo-debug-code8-20261013-local-592edc337671.apk`.
- Size: **47,803,784 bytes**. SHA-256:
  `fd8af644ae532f8241ea830a869995360139732089aee9c918a1da5e6c05480e`.
- Package `com.easysavevideo.app`; minimum API31, target36; versionCode8,
  versionName `0.8.0-ui-dev`. The existing test signer actually matches certificate
  SHA-256 `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5`.
  APK v2 signature verification and 4-byte/16-KiB native-page zipalign passed.
- It remains a debuggable test package with the **unchanged global cleartext=true**
  setting. Static checks and the preliminary independent review establish bounded
  package facts, not release readiness, runtime network safety or native behavior.
  Final independent static report/evidence packaging is being completed.
- **Not uploaded, not installed, no device/emulator run, no release acceptance.**
  The older local c794ef 45,988,354-byte APK remains separate and unchanged; none of
  its pre-anchor claims are relabeled as verification of this new package.

Current checked evidence is under `/workspace/shared/esv-integrated-candidate-scratch-20261013/`:
`evidence/JVM_CENSUS.json`, `evidence/REGRESSION_TASK_STATUS.json`,
`evidence/SOURCE_AND_COMPILE_BINDING.json`, `evidence/FULL_REGRESSION_JUNIT.zip`,
`host-gates/SUMMARY.json`, `host-gates/REPORT.md`, `APK_IDENTITY.json`, `apksigner.txt`,
`zipalign.txt` and `independent-review/summary.json`. The intended final evidence
location is `/workspace/shared/esv-integrated-candidate-frozen-20261013/`; final freeze
manifest/package verification remains separate from these already executed results.
No new source-tree artifacts or historical freeze edits are part of this step.

### Earlier completed full cloud regression: Picker input chain only

Report: `docs/m11a/THREE_UI_SLICES_CLOUD_REGRESSION_20261013.md`.
Evidence: `artifacts/regression/three-ui-slices-20261013/VERIFICATION.json`.
The separate 18-file evidence bundle binds 1,437 pinned inputs after EditorBack40 →
A/B34 → Picker29. It does not cover the later Resume29 → ExportRecovery37 →
UnadmittedSave48 → ReadingReturn25 production changes.

- Exactly thirteen module unit-test tasks and verifyRuntimeAdmission actually reran
  with task-scoped `--rerun`: **328 fresh suites / 3,383 registered / 3,381 passed /
  0 failures / 0 errors / 2 skipped**. App **2,120** and ui-theme **17** actually ran.
  The two verification-local private-corpus cases remain unavailable/skipped; synthetic
  fixtures do not establish the missing 100/80-corpus acceptance.
- Gradle exited 0 in 44s: 77 actionable tasks, 14 executed and 63 dependency tasks
  UP-TO-DATE. Runtime admission freshly verified 90 exact artifacts; seven final
  compiled/source/ABI boundaries passed. Debug/Release compile and lint were associated
  from the preceding Picker run on the **same 1,437 inputs**, not rerun here. Both
  associated lint reports contain 60 warnings/8 hints/0 errors.
- Host SQL passed **14 tests / 344 rollback fault boundaries**. Synthetic DOM checks
  passed **22 video-presence / 11 save-detector** cases. The detector missing-directory
  ENOENT remains separately preserved. These are host/model/synthetic proofs, not real
  Android SQLiteOpenHelper, WebView or legacy-media backfill acceptance.
- All 21 historical admission scripts remain **2 scoped passes / 16 missing Git /
  2 missing evidence / 1 stale-source-anchor failure**; all 14 older SQL scripts remain
  **2 scoped passes / 12 stale-anchor failures**. The final boundary is already counted
  within the 21. No historical failure is waived by newer current-source checks.
- The 1,896-input host inventory and 2,827 compiled-output inventory stayed unchanged;
  exact source pins were rechecked. Redacted XML and sanitized-host hash provenance are
  explicit. An initial archive hash-reference ambiguity was corrected and preserved as
  a packaging review finding, not described as a test failure or new test execution.
- No APK was assembled, signed, uploaded or installed in this run. All device checklist
  items, PPT imagery, native media/network/SAF/OEM and rendered accessibility remain
  NOT_RUN. The later integrated run above has its own current-input results. Source publication is separate;
  the latest receipt has no workflows/runs/checks/status entries, and empty `pending`
  is not green CI.

### Earlier completed full cloud regression: b0c5647 only

Report: `docs/m11a/FULL_CLOUD_UI_REGRESSION_20261013.md`.
Evidence: `artifacts/regression/full-ui-20261013/VERIFICATION.json`.
This earlier full run binds b0c5647 production bytes. It is not rerun evidence for the later
Save-notice, reading-anchor, authentication-origin, recording-notification or editor
destination-acknowledgement source, published Editor Back/A/B changes, or any pending later slice. It is not a sum of overlapping slice totals:

- All **13 selected module test tasks actually reran with `--rerun`**. **307 fresh JUnit
  XML suites** registered **3,149 cases: 3,147 passed, 0 failures, 0 errors, 2 skipped**. App **1,886** and
  ui-theme **17** were both actually executed in this full run. The two verification-local
  cases need unavailable `ESV_OWNED_CORPUS` inputs; they are not passed acceptance.
- Debug/Release Kotlin compile and lint analysis/report tasks actually reran with
  `--rerun` and passed. Each variant's lint retains **60 warnings, 8 hints, 0 errors**.
  Runtime admission verified **90 exact artifacts**. These statements concern the
  selected tasks, not every dependency task in the Gradle graph: **101 actionable tasks,
  30 executed and 71 dependency tasks UP-TO-DATE**, terminal exit 0 in 3m16s.
  All 1,391 captured code/test/script/Gradle/third-party inputs stayed byte-identical;
  the independent host inventory also retained 1,839 non-build inputs unchanged
  during the host-gate run; evidence and ledger documents were intentionally updated afterwards.
  No production fix or weakened gate was used to obtain this regression result.
- Final compiled boundary check passed after the build outputs were stable. The current
  Save DB v8 host SQLite gate freshly passed **14 tests / 344 rollback fault boundaries**.
  It exercises extracted SQL plus a source-checked host transaction model; Android
  SQLiteOpenHelper, real published-media v1 backfill and native provider behavior are
  not established by this result.
- Production JavaScript checks passed **22 video-presence** and **11 save-detector**
  owned synthetic-DOM checks. The detector's missing-output-directory failure was
  retained; its successful rerun used an isolated output directory and byte-identical
  production/script inputs. This is not Android WebView, real-site or media acceptance.
- All **21 historical admission scripts** were rerun unchanged: **2 scoped passes,
  16 blocked by missing Git history, 2 blocked by missing historical evidence, and
  1 historical source-anchor failure**. The **14 older SQLite scripts** likewise
  reran unchanged: **2 scoped passes and 12 historical source-anchor failures**.
  The final boundary check is already one of the 21; do not count it again as a
  separate historical admission pass. Current-version success does not overwrite
  old gate failures or manufacture the missing Git/candidate evidence.
- The latest publication receipt has zero workflows/runs/checks/statuses. Empty combined
  `pending` is neither running nor passing CI. This local regression is not GitHub
  Actions green status, and the publisher itself ran no builds or device tests.
- APK assembly was NOT_RUN in that full regression; the later local-only c794ef Debug
  build/static result is recorded separately below. Device/emulator, original-PPT comparison,
  rendered IME/TalkBack/large-font behavior,
  real media/network/provider acceptance and physical energy measurements remain
  **NOT_RUN**. Original PPT bytes are still unavailable. Older OPPO and Windows failures
  remain separately open; there is no whole UI-B/UI-E/M9/M11 or release acceptance.
  The next bounded native checks are recorded, not executed, in
  `docs/m11a/UI_DEVICE_ACCEPTANCE_CHECKLIST_20261013.md`.

### UI slices and their dated verification numbers

These reports remain dated evidence; their totals overlap and must not be added to
one another or the full regression. Pre-publication wording in frozen slice reports
is superseded by the exact publication receipts above, not edited retroactively.

- `3176bcc`: input-clear build4 App **1,835** actually passed, 22 new tests, zero
  failures/errors/skips; 75 Gradle tasks, 19 executed/56 UP-TO-DATE. Debug/Release
  Kotlin and both lint tasks executed PASS, each 60 warnings/8 hints/0 errors;
  runtime 90. Evidence: `artifacts/ui/input-clear-20261013/VERIFICATION.json`.
  Its build2 SideEffect regression was fixed by restoring the original SideEffect;
  the existing test stayed unchanged and the failed log/XML are retained.
- `62b5f6d`: authentication/fixed-copy final build2 **208 App suites / 1,857 tests**,
  zero failures/errors/skips, 22 new tests (10 policy, 5 wiring, 7 destination).
  Dual Kotlin/lint executed PASS, each lint 60 warnings/8 hints/0 errors, runtime 90.
  Reports: `docs/m11a/AUTHENTICATION_BROWSER_NAVIGATION_20261013.md` and
  `docs/m11a/ORDINARY_DESTINATION_FIXED_COPY_20261013.md`;
  evidence: `artifacts/ui/auth-destination-copy-20261013/VERIFICATION.json`.
  It is published; the earlier pending-publication status is superseded. This earlier action
  returns only to the current Browser. The distinct bounded live-original-page selection was
  subsequently published in `4f22d43`; full login/recovery/resume remains unestablished.
- `795b6bb`: typed-destination App **1,864** and filter-role App **1,870** are separate
  slice runs, with 7 and 6 new tests respectively. The latter executed dual Kotlin/lint,
  retaining 60 warnings/8 hints/0 errors and runtime 90. Reports:
  `docs/m11a/DESTINATION_TYPED_DISPLAY_20261013.md` and
  `docs/ui/implementation/DISCOVERY_FILTER_PANEL_ROLE_20261013.md`.
  Destination dynamic notice/copy-status localization is now implemented and published:
  26 typed notice, 2 kind and 6 copy-state resource pairs supplement the earlier 26 fixed
  pairs. User-owned values and other Save runtime messages are outside that migration.
  Discovery's shared filter uses inline surface/card and floating elevated/sheet roles;
  its host remains Dialog, not a BottomSheet, with one shared movableContent owner.
- `b0c5647`: candidate/permission display App **1,886** actually passed, 16 new tests;
  dual Kotlin/lint executed PASS, each lint 60 warnings/8 hints/0 errors, runtime 90.
  See `docs/m11a/CANDIDATE_PERMISSION_DISPLAY_20261013.md`. Candidate numbering is
  locale-rendered; two website-denial notices are typed until current-locale display.
  Camera/microphone/location remain denied. Other Browser raw notices are not all migrated.
- Published `c794ef`: complete Save runtime notice display, App **1,905** actually passed,
  19 new tests, zero failure/error/skip. Both compilers/lints, runtime90 and seven compiled-boundary
  checks passed. Three consumers now resolve 78 groups in current Compose Locale; no service
  notification, storage/wire or recording/save behavior change. Other module tests were not rerun.
  See `docs/m11a/SAVE_RUNTIME_NOTICE_DISPLAY_20261013.md`.
- Published reading anchor `55c51cd`: App **1,953** actually passed, including **48 new
  tests**, zero failure/error/skip; dual compile/lint, each lint **60 warnings/8 hints/0
  errors**, runtime90 and seven boundary checks passed. Other module tests were not rerun.
  Stable result/offset and exact read-only restore provenance are source/JVM verified;
  actual Compose saved-state/navigation/drag and device restoration are NOT_RUN. No new
  APK contains this slice. See `docs/m11a/DISCOVERY_READING_ANCHOR_20261013.md` and
  `artifacts/ui/discovery-reading-anchor-20261013/VERIFICATION.json`.
- Published authentication-origin selection `4f22d43`: App **1,998** actually passed,
  including **45 new tests** (36 policy/concurrency and 9 wiring/resource), zero failures,
  errors or skips. Dual compile/lint actually executed, each lint **60 warnings/8 hints/0
  errors**; runtime90 and seven boundary checks passed. All 1,409 frozen inputs stayed
  unchanged. Other module tests were not rerun. Newly accepted Direct/Stream tasks can
  explicitly select only their process-local, same-origin, still-live original document;
  the ordinary Browser fallback remains separate. No login, URL loading/reload, View
  recreation, retry or task resume is performed. Recovered/cross-origin/expired/background-
  cleared associations fail closed. Native WebView/login/resume/Compose acceptance is
  NOT_RUN. The first run's new test-reader compilation failure and test-only repair are
  retained; the complete second forced run supplies the final result. See
  `docs/m11a/AUTHENTICATION_ORIGIN_NAVIGATION_20261013.md` and
  `artifacts/ui/authentication-origin-20261013/`.
- Published recording foreground notifications `1f25214f`: App **2,026** actually passed,
  including **28 new tests** (6 status/resource, 12 display/language and 10 source-wiring),
  zero failures/errors/skips. Dual compile/lint actually executed, each lint **60 warnings/
  8 hints/0 errors**; runtime90 and seven boundary checks passed. All 1,417 frozen inputs
  stayed unchanged. Other module tests were not rerun. Twelve typed status cases and the
  existing channel/title/action cover **15 actual display leaves**. The existing language
  owner drives display-only refresh of an already-promoted, matching, unfinished session;
  no capture/start/stop behavior or whole-App localization claim is added. The first run
  executed 2,026 tests with one newly authored assertion-scope failure; a test-only
  correction and complete rerun supplied the final pass. The failed log and all first-run
  JUnit XMLs remain preserved. Native first-frame language, active/paused continuity,
  channel/action/permission visibility and post-finish no-resurrection are NOT_RUN. See
  `docs/m11a/RECORDING_NOTIFICATION_LOCALIZATION_20261013.md` and
  `artifacts/ui/recording-notification-20261013/VERIFICATION.json`.
- Published editor destination-attempt acknowledgement `24bc104a`: Build2 terminated
  with exit 0 after
  **2,050 App tests / 24 new / 0 failures / 0 errors / 0 skips**. All ten requested tasks
  were forced and actually executed, including Debug/Release compile and lint; each lint
  retained **60 warnings/8 hints/0 errors**. Runtime admission reverified **90 artifacts**
  and the compiled-boundary checker passed **seven checks**. All **1,422 frozen inputs**
  remained unchanged. Other module tests were not rerun; this is not a full regression.
  Build1 terminated with exit 1 after 2,050 tests and one existing total-enum-occurrence
  assertion failure. Its assumption of 26 textual occurrences did not allow semantic
  reuse across the same 26 closed kinds. The independently reviewed test-only correction
  preserves 26 unique kinds, exhaustive enumeration/resource/localization coverage and
  exact reuse counts `LOCATION_UNAVAILABLE: 3` / `REMEMBERED_FOLDER_REQUIRED: 2`.
  All **226 first-attempt JUnit XMLs**, full failed log, census and input pin are preserved;
  the complete fresh build2 supplies the final result. No APK was built or installed.
  Actual Compose ASK dismissal, retry/editor interaction and lifecycle; native editing,
  media/provider behavior; emulator/device, accessibility, PPT/pixel and Windows fixture
  rerun remain **NOT_RUN**. Editor Back confirmation was outside that earlier repair. Evidence:
  `artifacts/ui/editor-destination-ack-20261013/VERIFICATION.json`, `BUILD_ATTEMPTS.json`,
  `TEST_ASSERTION_CORRECTION.json` and `FIRST_ATTEMPT_JUNIT.zip` in that same directory.
  Report: `docs/m11a/EDITOR_DESTINATION_ACKNOWLEDGEMENT_20261013.md`.
- Published Editor Back confirmation/publication feedback `c26df3f3`: **source/JVM tested**,
  with published parent `24bc104a`. The first and only build passed **2,077
  App tests / 27 new / 0 failures / 0 errors / 0 skips** across **230 fresh JUnit suites**.
  All ten requested tasks actually executed: full App unit tests, Debug/Release Kotlin,
  both lint analyses/reports/entry tasks and runtime admission. Each lint retains **60
  warnings/8 hints/0 errors**; complete issue-ID/severity/message multisets match the
  published baseline. Runtime admission verified **90 exact artifacts**, the unchanged
  boundary checker passed **seven checks**, and all **1,430 pinned inputs** stayed unchanged.
  PROCESSING/VERIFYING Back asks for stop confirmation and stays on the editor; PUBLISHING
  Back shows a status-only dialog. One-shot page/dialog/operation ownership blocks stale
  or duplicate confirmation; the current cancellation token decides accepted/already-stopped
  versus too-late publication feedback. Neither confirmation nor worker callbacks navigate.
  A fresh Back in other phases, including CANCELLING/WAITING_NATIVE_RELEASE, retains the
  existing exit behavior. Background/deadline cancellation and recording watchdog ownership
  remain unchanged. Other modules were not rerun; this is not a full thirteen-module regression.
  Native system/predictive Back, dialogs/accessibility, rotation/background/foreground,
  codec/publication timing, device/emulator, 200%/RTL/TalkBack, Windows fixture and PPT/pixel
  acceptance remain **NOT_RUN**. No APK was built or installed. The preceding group's
  first-failure/semantic-correction evidence is unchanged; this slice had no failed build.
  Report: `docs/m11a/EDITOR_BACK_CONFIRMATION_20261013.md`; evidence:
  `artifacts/ui/editor-back-20261013/VERIFICATION.json` and `COMPLETE_APP_EVIDENCE.zip`.
- Published A/B recovery `dbff3f4e`: first build **App2,100 / +23**, 233 fresh suites,
  zero failures/errors/skips. All ten App/dual compile/lint/runtime targets actually ran;
  1,435 pins stayed unchanged. Each lint: 60 warnings/8 hints/0 errors; runtime90 and seven
  boundaries passed. One-shot exact directory confirmation cannot replay accepted work;
  actual Library route exit applies the existing batch cancellation. Completed copies
  remain. JVM helper/copy-engine tests and source contracts do not execute NavController,
  Android lifecycle or SAF. See `LIBRARY_BATCH_RECOVERY_20261013.md`.
- Pending Picker29: first build **App2,120 / +20**, 234 suites; all ten requested targets
  executed, 1,437 pins unchanged, each lint 60 warnings/8 hints/0 errors, runtime90 and
  seven boundaries passed. Exact page/query/ref continuity is revalidated after the owned
  picker return; STALE_PAGE retains first-page fallback and stale selection rejection.
  See `LIBRARY_PICKER_PAGE_RETURN_20261013.md`.
- Pending Resume29: first full Gradle run **App2,156 / +36**, 237 suites; all ten targets
  executed, 1,441 pins unchanged, each lint 60 warnings/8 hints/0 errors, runtime90/seven
  boundaries passed. Standalone smoke run1 had eight fixture failures; corrected run2
  passed all 36, with both retained separately from the successful full Gradle run.
  Owned Resume/slot release is exact-once; Cancel and background retire command authority.
  The slice's disclosed post-RUNNING/pre-worker exception gap is retained historically;
  UnadmittedSave48 supplies the later bounded repair. See `SAVE_RESUME_REPLAY_20261013.md`.
- Pending ExportRecovery37: first full run **App2,201 / +45**, 240 suites; all ten targets
  executed, 1,447 pins unchanged, each lint 60 warnings/8 hints/0 errors, runtime90/seven
  boundaries passed. Unknown/failed recovery state remains truthful and explicitly retryable.
  A CHECKED pass need not mean zero backlog. Existing PREPARED recovery can perform the
  first accepted output copy, with unchanged journal/grant policy; this is not a new request
  or a promise that retry is read-only. See `EXPORT_RECOVERY_RETRY_20261013.md`.
- Pending UnadmittedSave48: final **third** attempt passed **App2,250 / +49** (242 suites)
  and **Save120 / +25** (6 suites); all eleven requested targets executed and 1,453 pins
  stayed unchanged. Runtime90/seven boundaries passed; Debug lint 54 warnings/8 hints and
  Release55/8, both zero errors. Six/five prior GradleDependency notices were not emitted;
  dependencies were unchanged, and those warnings are not claimed fixed. Attempt1 passed
  Save120 but failed App test compilation; attempt2 passed Save120 and ran App2,250 with
  two fixture-assertion failures. Complete failed evidence and reviewed fixture corrections
  remain retained; production/resource/build inputs stayed identical across the attempts.
  Repair authority exists only for the exact proven-unadmitted operation. Its explicit
  local state repair performs no media work; unknown worker admission and broader faults
  retain existing fallback. See `UNADMITTED_SAVE_RECOVERY_20261013.md`.
- Pending ReadingReturn25: first full run **App2,263 / +13**, 242 suites; all ten targets
  executed, 1,453 pins unchanged with two inputs changed from its predecessor. Debug lint
  54 warnings/8 hints, Release55/8, zero errors; runtime90/seven boundaries passed. The same
  live Library runtime keeps its read query/cursor and re-queries the actual repository;
  STALE_PAGE/missing-folder fallback and explicit Refresh/query/mutation semantics remain.
  Transient command targets/consents stay cleared, and no export grant, selected action or
  complete scroll anchor is restored. One legacy ordinary-resume assertion was intentionally
  changed with its full rationale/diff retained. **Save120 was not rerun here**; it belongs
  to the preceding UnadmittedSave stage. See `LIBRARY_READING_RETURN_20261013.md`.
  All final App/Save counts above have zero failures/errors/skips; added counts are included
  already. Other modules were not freshly tested in these slices. The separate Picker-chain
  13-module census cannot be extended to any later source, and all actual Android/Compose/
  Handler/provider/media/device/PPT acceptance remains NOT_RUN.
- Separate local-only APK: code8 / 0.8.0-ui-dev, min31/target36, 45,988,354 bytes, source c794ef
  before anchor work. Existing signer matched; APK v2 and static integrity passed. No key,
  version or project configuration change; no upload/install/release claim. The unchanged global
  cleartext setting is disclosed. Local source hashes match before/after; one existing metadata
  POM differs from the remote tree only by CRLF/LF. See
  `docs/m11a/DEBUG_CANDIDATE_STATIC_ACCEPTANCE_20261013.md`.
- Theme **17** ran in the semantic-token slice and in the separately dated b0c5647
  and Picker-chain full regressions, and freshly in the current integrated run. It did **not** run in the intermediate input-clear, authentication,
  destination/filter, candidate/permission, Save-notice, reading-anchor, authentication-origin,
  recording-notification, editor destination acknowledgement, Editor Back, A/B or any of
  the five pending production slices; no historical run is relabeled.
  Earlier App totals remain Browser copy 1,747, current storage contracts 1,761,
  Shell labels 1,774, Save locale 1,791, semantic tokens 1,801 and Discovery body 1,813.
  Their executed/cached task details remain in their individual reports.
- `testReleaseUnitTest` does not exist in this build; the historical failed task-selection
  attempt ran no tests. The new run does not create a Release unit-test claim.

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
  not rerun earlier in that Wi-Fi slice. They are not fresh R6 passes; the later full
  regression has its own current-source execution evidence above.
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
| Incognito and site permissions | Incognito guards exclude ordinary persisted session/history paths, but no user-facing incognito entry is implemented; current WebChromeClient explicitly denies camera/microphone and location, with typed localized denial notices | Shared CookieManager/storage is not isolated browsing. Permission selection/Android result correlation and granted-capture lifecycle are absent; do not call deny-only a completed permissions manager. `app/src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt`. |
| Discovery paging and results | Explicit one-shot next-page demand, bounded provider/local validation framework; truthful missing metadata; distinct empty/loading/waiting/error body presentation, explicit query Clear, shared inline/floating filter roles and published stable-result reading anchors with exact restore provenance (`55c51cd`) | Actual Compose saved-state/navigation/drag restoration remains NOT_RUN. No admitted real-image source/reader exists; Android decode, retained image ownership and viewport/UI binding are disconnected. Library MediaReadRef is not web-image authority and no mature public-image loader is admitted. Commons is only a candidate for bounded source/reader review, not a hard dependency; zero-provider behavior remains required. Fixture pixels, URLs and rights metadata do not establish real-image acceptance. `docs/ui/implementation/DISCOVERY_THUMBNAIL_FOUNDATION_20261012.md`. |
| Direct / Stream / Record | Real bounded save/capture pipelines, explicit user triggers, verified media publication and safe failure guidance; Wi-Fi-only save enforcement published in `172fa3c`, bounded live-original-page selection in `4f22d43`, and the existing recording foreground-notification text channel in `1f25214f`; pending Resume29 and UnadmittedSave48 provide bounded replay and exact local repair source/JVM evidence | Full native stream/codec/AV/background/recovery, live-WebView login/resume and notification continuity coverage pending. Published original-page selection performs no login, reload or task resume. A failed network save offers fresh explicit reinspection only; it does not auto-retry, broaden sources or silently start recording. |
| Save location, library and batch Files | App Library/ASK/album plus remembered ordinary SAF folder; batch export uses one selected folder and per-item verified results; fixed and typed dynamic destination display localized; originals retained for copies; published A/B safety and pending Picker29/ExportRecovery37/ReadingReturn25 cover strict picker return, recovery retry and ordinary read-page continuity | Native provider/revocation/uncertain cleanup matrix remains partial. Old “choose batch Files behavior” note was resolved by later one-folder implementation. `docs/m5a4b/REQUIREMENTS_DECISION.md`; `docs/ui/implementation/BATCH_DIRECTORY_COPY_20261012.md`. |
| Editing and default compression | Trim/merge/crop/compress with existing entitlement gates; default applies once to new edits, never overwrites active/restored drafts | Editor destination-attempt acknowledgement is published in `24bc104a` (2,050 App tests, 24 new). The separate Editor Back confirmation/publication-feedback slice is published in `c26df3f3` with 2,077 App tests (27 new): PROCESSING/VERIFYING confirms stop without navigation, PUBLISHING shows status only, and fresh Back in other phases retains existing behavior. Native dismissal/retry/Back/lifecycle acceptance is NOT_RUN. OPPO edit initialization failure remains unresolved. Default editor compression is not recording compression, successful encoding, or a paid unlock. `docs/ui/implementation/EDITOR_DEFAULT_COMPRESSION_20261013.md`. |
| Power/settings/notices | Real lifecycle OS battery/thermal/network observation and fail-closed policy; localized status; scrollable offline notices and responsive settings panes | Physical energy benefit, platform callback timing, full accessibility/large-font/phone/tablet acceptance absent. The old OS NOT_CONNECTED label is obsolete. `docs/m8b4/VERIFICATION.md`; `docs/ui/implementation/CLOUD_NOTICES_POWER_20261012.md`. |
| Diagnostics and sharing | Bounded typed initialization diagnostics and log-export implementation; no sensitive media/path/URL logging allowed | New diagnostics must be run on the failing device build before determining edit/share root cause. Existing failure enums alone do not prove codec failure or a repair. `docs/ui/implementation/INITIALIZATION_DIAGNOSTICS_CLOUD_20261012.md`; `docs/diagnostics-export/IMPLEMENTATION_AND_VALIDATION.md`. |
| Vault / dynamic rules / new local video import | Explicitly deferred; Vault/rules disabled or isolated | Do not reopen blocked reviews, substitute integrations or count these as implemented. Import is a deferred extension, not automatically an original requirement failure. `docs/ui/implementation/ESV_UI_FEATURE_INTEGRATION_LEDGER.md`; `AGENTS.md`. |
| Real payment and release | Store-neutral preparation and six channel identities | Payment choice, products, actual accounts/SDK/backend and channel acceptance unknown/unconfigured. Fresh release policy and actual release artifact/device gates still needed. `docs/m10b/CHANNEL_READINESS.md`. |
| Full visual design | Nine original UI Markdown documents recovered/published; visible Shell labels, locale-aware Save/candidate/destination display, semantic theme tokens, Discovery body/filter roles and input Clear implemented in bounded source slices | Original PPT binary still unavailable. Semantic tokens and localization remain partial; no complete slide/pixel parity, UI-E or accessibility acceptance. `docs/ui/implementation/ESV_UI_TOKEN_AND_COMPONENT_MAP.md`; the five dated slice reports listed below. |

Current original-UI repair reports (all read for this checkpoint):
`docs/ui/implementation/SHELL_VISIBLE_LABELS_20261013.md`,
`SAVE_DISPLAY_LOCALE_20261013.md`, `SEMANTIC_THEME_TOKENS_20261013.md`,
`DISCOVERY_BODY_STATE_20261013.md` and `INPUT_CLEAR_20261013.md`.
Their earlier “next work”/publication-pending statements are slice-time history where
superseded by the publication chain above, not instructions to repeat completed work.
Authentication-waiting return to the current Browser and 26-pair fixed destination
copy are published in `62b5f6d`. Typed destination notices/copy-state display and Discovery
filter roles are published in `795b6bb`; candidate labels and typed denied-permission
notices are published in `b0c5647`; the complete Save runtime notice channel is published
in `c794ef`. Reading anchors are published in `55c51cd`, explicit process-local same-origin
still-live original-page selection in `4f22d43`, and the current recording foreground-service
notification text channel in `1f25214f`; editor destination acknowledgement is published
in `24bc104a`, Editor Back in `c26df3f3` and A/B recovery in `dbff3f4e`. Full login/recovery/resume, other recording/runtime owners' raw notices and
whole-App localization remain incomplete/unverified; native acceptance remains NOT_RUN.
Picker29 → Resume29 → ExportRecovery37 → UnadmittedSave48 → ReadingReturn25 have separate
source/JVM evidence and await publication, as do the independent 18-file regression
evidence group and this two-ledger refresh. The current integrated regression completed
3,551/3,549/2; its new local APK is built/statically checked without device acceptance.

## 4. Older device evidence: useful, bounded, not overwritten

The delivery owner reports signed OPPO code8 / `0.8.0-ui-dev`, with bounded two-item
copy/move, recording/replay and nested-scroll/dialog correction checks accepted.
The same device still reported editor `STORAGE_UNAVAILABLE` and share
`INITIALIZATION_FAILED`. Known ANR/second-recording and Windows symlink/setup-guard
issues also remain open as retained by the later slice reports. None is cleared by cloud
tests or this documentation refresh. These are open failures, not a complete device pass.

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

## 5. Integration checkpoint and remaining functional source work

Published source scopes through Editor Back/A/B must not be relisted as unfinished
implementation. The five subsequent production slices have bounded tests and immutable
input manifests; the current integrated regression and local Debug build/static checks
now have separate results. Source publication and evidence-package finalization remain
separate from those completed runs. Keep one build owner and distinguish executed from
associated/cached/not-run evidence.

1. **Finalize the integrated evidence package without extending its acceptance scope.**
   The full local chain after dbff3f4e now has **3,551 registered / 3,549 passed / 2 skipped**
   with all thirteen module tests and runtime admission freshly executed. App2,263/Save120
   belong to this run; dual compile/lint remain associated from identical ReadingReturn25
   inputs. Current SQL14/344 and DOM22/11 passed, while historical failures remain intact.
   The new 47,803,784-byte Debug candidate has its exact local source/APK hashes, original
   signer match and zipalign evidence in §2. Complete the final independent report/freeze,
   preserving executed, associated, skipped and failed boundaries. No invented Git HEAD,
   remote publication, installation or native/PPT acceptance follows. Keep c794ef's older
   APK unchanged. Pending source groups, independent18 evidence and refreshed ledgers
   still need their separate verified publication receipts before being called published.
2. **Resolve real Discovery thumbnail admission before implementation.** Original UI03 §6
   and UI08 §§2/3/7 require Reader/thumbnail reuse and an admitted mature public-image loader,
   or a concrete justified/reviewed narrow platform-adapter exception. Inspection found no
   admitted real Discovery image-read path and no admitted Coil/Glide/Picasso/Fresco loader.
   The existing Library path reads READY ordinary local media via MediaReadRef and
   MediaMetadataRetriever; a web URL cannot be turned into that authority. The Discovery
   fixture factory supplies only caller-owned RGBA pixels, and its frame is borrowed only
   during the callback, so it cannot be retained directly by Compose.
   Commons is a possible bounded candidate for source/reader investigation, **not a mandatory
   provider or already approved thumbnail fetch path**. Its metadata transport is API/JSON-only;
   image metadata/host filtering, reported rights and API-only contact approval do not grant
   CDN image access or authorize forwarding contact details. SearXNG drops thumbnails and
   verification poster metadata likewise grants no fetch. Maintain zero-provider startup.
   Review trusted producer/source/config capability binding, exact endpoint/anonymous request
   authority, public-peer/TLS/cancellation invariants and loader admission (or justified
   platform exception) before real integration. Then choose explicit wire/pixel/retained-memory/
   viewport/time budgets and a retained image owner, reuse the existing scheduler/lifecycle
   gates and test stale/late/uncertain-close/oversized-image behavior with owned fixtures.
   No generic image download/decode/cache framework, arbitrary URL reads or fabricated grants
   are authorized by this plan. Fixture rendering is not REAL thumbnail acceptance. The
   read-only feasibility audit supports this gap; it does not admit a source or loader.
3. **Implement bounded per-request webpage audio consent.** Current `SystemBrowserHost`
   deny-only callback is a concrete gap. A native SkinKit confirmation may grant only
   AUDIO_CAPTURE to the exact current same-origin HTTPS request, after independent Android
   RECORD_AUDIO authorization/result correlation. Keep camera/location denied; no remembered
   site grants. Wire actual host/UI/Activity lifecycle cancellation and isolated request codes;
   test old callbacks, duplicate results, navigation, background, cancellation and origin ABA.
   Never reuse recording consent's automatic MediaProjection path. Pending-request completion
   does not prove already-granted capture stops; resolve the active WebView teardown/reload
   behavior and review it before claiming a full permission lifecycle. No new permission or
   personal-data transmission is authorized merely by listing this task.
4. **Review only the remaining authentication-recovery cases.** The `4f22d43` publication
   already supplies explicit selection of a same-origin, exact still-live original document
   for a newly accepted task; ordinary Browser fallback remains available. Recovered tasks,
   background/process loss and cross-origin/CDN cases intentionally remain unavailable.
   Full login and task resume need separate source/credential, freshness, ownership and
   lifecycle review and native evidence. Do not repeat the completed action, infer a safe
   origin from media URLs, expand authority from persisted metadata, or promise resume success.
5. **Review remaining original UI coverage after the functional repairs.** Use the recovered
   nine Markdown originals and `ESV_UI_TOKEN_AND_COMPONENT_MAP.md` to select actual remaining
   component/state gaps. Published Browser/destination/candidate/denial/filter/Save-notice,
   reading-anchor, original-page-selection and recording foreground-notification scopes
   are not pending implementation targets. Other owners' or recording runtime notices,
   success/warning/selected/loading/scrim/system-bar tokens and all-component localization
   remain partial. Copy-only expansion is lower priority than a confirmed functional gap.
   Source coverage does not substitute for the missing PPT or rendered-device evidence.

Item 1 has a completed integrated JVM/host run and built/statically checked local APK;
final evidence packaging is separate. Each preceding source slice retains its own bounded
result, and native acceptance remains NOT_RUN.
Items 2–5 remain bounded candidates requiring normal scope/admission review. All preserve existing
source-admission, privacy, entitlement and lifecycle gates. They can produce source/test
progress without selecting history retention, activating billing/Vault, or installing
an APK. They do not cure the unresolved OPPO root cause by assumption.

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
- **Thumbnail production source access:** no real Discovery read capability is admitted.
  Require source/reader authority, mature-loader admission or a justified narrow platform
  exception, retained image ownership and bounded budgets before replacing placeholders.
  Commons remains an optional candidate, with zero-provider support; no hard dependency or
  automatic image-fetch permission follows from its existing metadata integration.
- **Vault/rules/import:** deferred; do not silently prioritize or reopen them.
- **Commercial/release:** payment and actual channel/account readiness unresolved. Recheck
  then-current official requirements at release time; the 2026-10-07 market checklist is
  preparation, not a new legal verification performed by this document.
- **UI/device:** use the nine recovered original Markdown documents; obtain the original
  PPT binary/imagery, then perform the finite phone/tablet, large-font/accessibility/
  lifecycle/native-media matrix before full UI/M9/M11 acceptance. Markdown recovery
  does not supply screenshot comparison or close UI-E.

## 7. Documentation refresh and publication boundary

This refresh changes only this overview and MILESTONE_STATUS. Publication is current
through dbff3f4e (A/B34), preceded by c26df3f3 (EditorBack40) and 24bc104a (destination
acknowledgement). The latest two receipts verify 74 scoped changed-file readbacks;
main and private visibility remain unchanged. The five later production groups remain
pending in the exact ordered manifest chain in §1. The 18-file Picker-chain regression
bundle contains no production change and remains independent. This ledger refresh has
no publication receipt yet.

Each source/test/build stage keeps its exact evidence. The latest completed integrated
13-module census is **3,551 registered / 3,549 passed / 2 private-corpus skipped**, bound
to the complete 1,453-input local chain. App2,263, Save120 and Theme17 actually executed
in that run; compile/lint were associated from identical ReadingReturn25 inputs. The
older Picker run remains 3,383/3,381/2, and b0c5647 remains 3,149/3,147/2. Save120 did not
run in the ReadingReturn slice itself; it ran in UnadmittedSave and again in this new
integrated run. Overlapping totals are not summed. Historical missing-Git/evidence and
stale-anchor gates, private-corpus skips, failed attempts and OPPO/Windows issues remain.

The new local Debug APK is **built and statically checked**, with source/APK hashes,
matching existing signer, version/SDK facts and zipalign in §2. Its independent final
report/freeze packaging is separate and not presented here as already complete. The
candidate is identified by local input/chain manifests, not a remote commit; no upload,
installation, device or release acceptance is claimed. New evidence remains external at
`/workspace/shared/esv-integrated-candidate-scratch-20261013/`, with intended final freeze
`/workspace/shared/esv-integrated-candidate-frozen-20261013/`. No source artifacts or
immutable reports/freezes are edited. Host full-source hashes were finalized before this
results-only ledger update, while tested production/test/script/build/dependency inputs
remain unchanged. The old local 46 MB c794ef APK retains its own pre-anchor evidence.

Original Markdown recovery remains distinct from unavailable PPT imagery. Android12/API31,
Vault/interpreter disabled or isolated, deferred local import, undecided history TTL/payment
and all existing admission/privacy/entitlement gates remain in force.

This documentation refresh itself executes no build, test, native or device operation
and does not alter original requirements, package/version, device state or published commits.
