# Bounded initialization diagnostics — cloud completion

## Scope and evidence boundary

Input is the verified source snapshot of private backup/cloud-handoff-20261013,
commit 20fddc9c33b399ab75f8b8eee0f21e41e6f5b06f. Its interrupted initialization
work is retained and completed; this workspace is a snapshot, not recovered Git
history. No device, install, APK assembly, source import, dependency change,
Vault, dynamic rules, entitlement relaxation, or payment is part of this change.

The handed-back OPPO code8 observation is editing STORAGE_UNAVAILABLE and single
share INITIALIZATION_FAILED. The first concrete failing stage and system error
were not captured. Its root cause remains UNKNOWN; this change is diagnostic
capability, not evidence that editing or sharing now works on that device.
Historical CODE8_INITIALIZATION_INVESTIGATION_20261012.md describes the prior
candidate; its statement that finer fields were not implemented is historical.

## Completed diagnostic path

- One worker-confined probe per initialization attempt retains the first failing
  fixed stage, closed exception category, and optional numeric errno only.
- Editing covers outer storage construction, staging/media symlink checks,
  directory creation, strict canonical validation, root fsync, and runtime
  construction. Failure still prevents runtime construction or admission.
- Sharing covers outer construction, private directory resolution, system service
  acquisition, directory check/create/canonical validation, owner symlink check,
  owner open and owner lock. Known ShareFailure resource fences preserve their
  original identity. Unclassified constructor failures become INITIALIZATION_FAILED.
- Directory and owner helpers use the existing guards, modes and ownership rules.
  An overlapping/failed lock closes the newly opened owner, leaves the prior lock
  untouched, retains an uncertain owner if close throws, and propagates the first
  failure through the sanitizer. A definite diagnostic-loss defect was fixed:
  if failed lock acquisition is followed by unconfirmed owner close, the retained
  owner fence now also retains its first bounded diagnostic. Subsequent gets
  still fail with RELEASE_UNCONFIRMED and cannot acquire a new owner. This repairs
  diagnosis of the fenced path, not an OPPO filesystem failure. Recovery/copy/
  chooser outcomes are unchanged.
- Android IO may wrap ErrnoException. Cause examination visits at most eight
  distinct objects including the outer exception, stops on identity cycles or
  accessors throwing Exception, never reads suppressed exceptions or messages, and accepts
  only errno 1..4095. The category remains that of the outer exception; IO with a
  causal errno is valid. Absent/out-of-budget values are UNKNOWN, never guessed.
- Editing detail and export/share status show fixed technical fields only behind
  BuildConfig.DEBUG. Release keeps ordinary unavailable/failure semantics without
  these diagnostic strings. Presentation also validates the numeric bound.
- A new admitted operation and explicit notice dismissal clear the share probe
  display. State holds a bounded value, not a Throwable, stack, path, filename,
  URL, media identifier/content, credentials, or arbitrary exception class name.
  No collector persistence, automatic upload, logging, or history is introduced.

## Local tests and remaining acceptance

Tests cover every stage's rethrow identity, first-error retention, category
closure, errno range/wrapping/depth/cycles/unknown/hostile getter, Debug/Release
presentation gates, unavailable edit admission, known sharing resource fences,
real temporary directory blockers and canonical rejection, symlink rejection,
owner-open failure, overlapping owner lock and subsequent reacquisition, and
successful directory/root-sync/lock paths. Fixtures are generated local files;
no user media is read.

“First failure” is scoped to each construction attempt, not the whole process.
Segment-recording initialization can make an earlier independent edit-storage
attempt. A transient failure there is not reconstructed by the editor probe;
persistent failure of the explicit editing attempt is captured. Startup ordering
and optional-component admission are unchanged.

The JVM errno extraction test injects a typed-number reader because the Android
framework is mocked in ordinary unit tests. It tests the production traversal and
bounds, not an actual Android Os failure. Android service lookup, OEM filesystem
behavior, Compose rendering, real sharing and editing remain NOT_RUN here.
The independent result file records actual build/test outcomes separately;
planned or source-reviewed checks must not be read as executed passes.

A later authorized device attempt should capture only version/source identity,
fixed stage/category/errno and existing status, stop at the first failure, and
preserve original files and all existing security/ownership gates. This work
neither authorizes that attempt nor labels a restart as same-process recovery.

## Executed slice verification — r2, 2026-10-12 UTC

This section records the earlier r2 run. Its XML hashes and 61-warning lint
counts are retained as historical evidence, not the final integrated result.

- PASS: 14 relevant JVM suites, 85 tests, zero failures/errors/skips. Of these,
  32 tests are the five initialization-specific suites. Exact suite counts and
  evidence hashes are in the companion result JSON.
- PASS: shared selected app run totals 44 suites / 468 tests with zero failures,
  errors or skips. This is a selected run, not the entire repository test suite.
- PASS: app Debug and Release Kotlin/Java compilation; exact runtime admission
  gate verifies 90 reviewed artifacts for both variants.
- PASS with warnings: app Debug and Release lint each report 0 errors, 61 warnings,
  and 8 hints. Warnings are not treated as cleared by this result.
- Independent read-only review found the retained-owner diagnostic loss and a
  test-fixture close re-entry defect; both were corrected and rereviewed before
  final verification. No remaining definite P1/P2 was identified in that scope.
- NOT_RUN: APK assembly, installation, emulator, phone, UI rendering/accessibility,
  actual Android service/errno failure injection and end-to-end edit/share.

The r2 shared serial invocation ended BUILD SUCCESSFUL (75 actionable tasks,
52 executed, 23 up-to-date). The corrected owner-close test source hash is
468090e0ee0ba7f91f9d434f1218ba98242b8793424d72d164a320307b0852ea and its update
preceded test compilation. No production source in this initialization slice changed after that verification.


## Final integrated verification — r3/r4

The authoritative final aggregate is
[cloud validation evidence](../../../artifacts/cloud-validation-20261012/README.md)
and its SUMMARY.json. Following the other slice's last English resource change,
the app tests were explicitly forced to rerun (r4): 468 app tests passed, including
this slice's same 85 relevant tests. Together with 47 core-model/core-domain and
29 discovery policy/coordinator tests, the selected aggregate is 544 passes,
zero failures/errors/skips. It remains a selected run, not the full repository.

Debug and Release Kotlin compilation passed in r2; the final resource-only r3
reused the unchanged compiled Kotlin outputs. Final Debug and Release lint each
report 0 errors, 60 warnings and 8 hints. These final 60-warning counts supersede
the historical r2 61-warning counts above. Exact runtime admission still verifies
90 artifacts for both variants. Device/emulator/real edit-share acceptance remains
NOT_RUN, and the OPPO root cause remains UNKNOWN.

The companion result keeps this slice's original r2 XML hashes under verification
and separately identifies finalIntegratedValidation, avoiding cross-round hashes.
