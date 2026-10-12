# Directory picker result correlation correction

Independent P2 correction after02bfec36685062ded705871fddfdb8350b52cb7e.
Code7 input/package and remote delivery remain fixed; no assembly/install/upload.
The prior20 tests did not model foreground=false during the real picker pause.
The prior result predicate was therefore incorrect for RESULT_OK delivered before
onResume. It could reject a legitimate Files result; code5 ANR remains unproven.

FolderPickerAsyncBridge requires a separate explicit result-correlation predicate.
Neither bridge nor Android host can silently default it to the launch predicate.
The Android host checks exact weak Activity identity and not-closed state for
both paths. Launch additionally retains RESUMED and the prelaunch30-second
window. Files launch additionally retains exact selection/source/foreground.

Files result correlation only checks original host, selection/ticket, not-closed,
not-cancelled and active request lifecycle. It does not check foreground or exact
source at result delivery. The existing callback receives into the original
owned ticket, stores URI/flags in memory and shows pending confirmation. It does
not persist a grant, build a batch, create an output or start copying.

The unchanged canConfirmBatchFolder and confirmBatchFolderCopy still require
selection.current() (MainActivity's foreground, Library/source generation and
not-finishing/destroyed checks), the staged owned URI and explicit confirmation.
The unchanged worker registry.accept/requireTree checks actual grant authority,
and the copy coordinator revalidates sources. No copy admission was relaxed.
Settings folder preference keeps its own explicit live-ticket association; its
existing behavior and grant validation are unchanged by this correction.

Eight new pure regressions model launch -> pause(foreground=false) -> RESULT_OK
-> resume -> explicit confirm. Result must stage while paused; no copying before
resume/confirm. They also cover cancellation before/after result, source changes
before result/after resume, original-owner recreation, revoked permission at
worker admission and foreground/source denial before launch. Permission checks
in these tests are a model of the unchanged worker gate, not Android permission
or OEM evidence. Existing20 async tests plus directory regressions also rerun.

Evidence: artifacts/ui/folder-picker-result-correlation-20261012. Final counts
and build/lint outcome are recorded after stable-source verification. The first
intermediate run covered59 old tests; it is not acceptance of the new8 regressions.
Android/OEM/SAF lifecycle acceptance remains NOT_RUN, performance NOT_MEASURED,
ANR UNKNOWN. This local increment requires independent re-review.

The unchanged LibraryExportStatus confirmation button is enabled only when
foreground && exports.canConfirmBatchFolder(). The ordering regression queries
canConfirm while paused and clicks confirm only after resume, matching this UI;
it does not fake clicking a disabled button. Direct invalid confirmation still
cancels rather than authorizing a copy.

Final67 actual tests PASS/0 failure/error/skipped, including8 new lifecycle tests
and prior20 async tests. Production Debug/Release Kotlin executed in the stable
source compile run15 executed/52 up-to-date; last test-only refinement rerun6
executed/61 up-to-date. Lint59 warnings/8 hints/0 errors, no internal exception.
Existing code7 input SHA unchanged. Pure results do not substitute for Android
callback/permission/OEM acceptance.
