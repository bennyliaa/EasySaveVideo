# code7 device defects: source candidate

Base4c180deefff1f90957c62ce2d313737ed9173c4a preserves the independently reviewed
02bfec3/4c180de directory-picker fixes. Installed code7 is still9efc346 and does
not contain those fixes or this increment. No device/test-directory write,
assembly, installation or upload in this task. Original test media retained.

## Confirmed P1: batch confirmation double scroll

The completed OPPO-CODE7-DEVICE-RESULT.md reports the exact trigger: Library,
multi-select, current-page selection (one eligible48-second recording), Share.
The process crashes during the common batch-confirmation dialog, before chooser.
Device log marker10-12 21:11:44.521; its timezone is not inferred here. Redacted
framework stack: artifacts/ui/code7-device-defects-20261012/REDACTED-CRASH-STACK.txt.

LibraryBatchExportDialog nested a verticalScroll Column in LibraryDialog, whose
Column already has heightIn(max720dp) and verticalScroll. The outer scroll passes
unbounded content height to the inner scroll; Foundation rejects it. The dialog
source is byte-identical in code7 and main4c180de. Even one selected item fails;
it is not a size/entitlement/recipient permission failure. Both toolbar export
and share call LibraryScreen.batchAction and this same dialog. Gallery, Files
and Share all use it. A single-item detail export dialog is a separate route.

The source fix removes only the inner scroll/imports. All batch content, selected
names, warnings, eligibility conditions and onChoose callbacks remain identical.
The bounded outer viewport owns scrolling for all destinations. No fixed APK has
been installed; the device P1 remains awaiting candidate retest.

Real Foundation ScrollNode tests reproduce the old nested exception and measure
the fixed one-scroll hierarchy for81 combinations of3 destinations,1/50/100
items,1x/2x/3.2x synthetic font-scaled content and160/360/720px viewports. They
check actual measured viewport, scroll range and reaching the end. Content is a
synthetic plain measurable, not real glyph layout; no Android/OEM pixel, TalkBack,
large-font device or button hit-testing claim. A source-hierarchy guard connects
the common dialog to one bounded owner; direct Foundation constraint checks also
pass. These tests use existing admitted Compose1.8.3, no test dependency added.

## Editor detail entry: constructor availability, not entitlement

MainActivity supplies onEdit only when saves.editor exists; LibraryScreen hid the
button entirely when onEdit was null. That visibility condition does not consult
entitlements. With the ready media catalog in the device report, the optional
AndroidEditRuntime construction exception is the source branch that can leave
editor null. Its underlying exception was swallowed and cannot be recovered from
these UI summaries. No claim of a precise filesystem/native root cause.

The increment probes existing edit storage on the same control worker before
constructing the optional editor, and publishes one bounded enum: INITIALIZING,
READY, STORAGE_UNAVAILABLE or RUNTIME_UNAVAILABLE. Exception objects/text are not
retained. The detail always shows the edit-copy label; it is disabled without an
available ready constructor, with paired EN/ZH status text. It never fabricates
an editor, opens entitlements, ignores recovery, or starts native execution.
The original AndroidEditRuntime feature/resource/source/publication gates remain.
This repairs hidden availability information, not proof that device editing now
works. Actual edit construction/recovery/copy acceptance remains OPEN.

## Single share: unresolved worker preparation failure

Device report says chooser not observed and revoke/cleanup unconfirmed. Its UI
notice matches the unclassified exception branch of AndroidExportRuntime.submit,
not the distinct onChooser(startActivity) exception notice. Failure is within the
worker preparation pipeline (owner construction/prepare/chooser construction);
there is no exact exception/cause in the retained report. Sharing has no paid
entitlement gate; the eligible9-second input is far below copy byte limits.

Previously unclassified LibraryShareOwner constructor exceptions now become
ShareError.INITIALIZATION_FAILED with bounded UI text. Known cancellation,
capacity and release fences retain their original ShareFailure instances.
No exception text/path is exposed, no file lock/path/permission/sandbox guard is
relaxed and no chooser/sending success is invented. Revoke/cleanup success and
actual chooser compatibility remain OPEN; this diagnostic improvement does not
prove their root cause or functional recovery.

A canonical-path mismatch in strict storage constructors was considered only as
a candidate. No OEM path was captured. Stock Android12 init uses bind mounts
rather than assuming the old user0 symlink universally; see official AOSP mirror:
[AOSP Android12 init.rc](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/android-12.0.0_r1/rootdir/init.rc)
No speculative path normalization/security weakening was made.

## Verification and packaging boundary

65 actual JVM tests PASS/0 failure/error/skipped,12 new tests. Debug/Release Kotlin
executed; strict offline serial build29 executed/38 up-to-date. Lint59 warnings,
8 hints,0 errors. An initial test compilation failed on Files.readString under
the Android API classpath, then changed to compatible readAllBytes and passed.
Release compiled classes bind Store; FakeEntitlementSource absent (Debug has it).
No Release APK was assembled. Paired initialization strings3. No new dependency,
minSdk/version/signing or notice change. Existing code7 input SHA unchanged.

Evidence RESULT.json and sanitized test/build/lint files under
artifacts/ui/code7-device-defects-20261012. Historical ANR remains UNKNOWN; the
observed same-process second-recording limitation is not fixed or closed here.
Phone CPU/RAM/battery/network/time NOT_MEASURED. Source is ready for independent
review; current versionCode remains7, so any later approved update must use a
higher code and the original compatible signer. No candidate package created.
