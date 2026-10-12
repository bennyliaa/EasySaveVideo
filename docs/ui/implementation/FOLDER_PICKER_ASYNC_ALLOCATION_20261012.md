# Directory picker durable allocation off the main thread

Independent increment after928b263 (code7 input/delivery remains immutable).
Scope is the confirmed synchronous SharedPreferences commit in the directory
picker, not a proven fix for code5 ANR. No package/version/dependency changes.

AndroidFolderPickerHost now uses one bounded process-owned worker lane for all
counter reads and synchronous commit acknowledgments. Only after success does
it post to the main thread to recheck the exact live Activity, RESUMED lifecycle,
30-second prelaunch deadline and caller's current ticket/selection, then launch.
Main-thread request state is confined to main; no monitor covers commit or
platform launch. No apply(), grants, network/security changes or new dependency.

The pure FolderPickerAsyncBridge reserves one slot before enqueue. Repeated
clicks cannot submit competing pickers. Cancellation/replacement/close invalidates
unlaunched posts; Activity ON_PAUSE cancels prelaunch work even if resumed before
the post. Once actually launched, its own picker pause does not cancel results.
Rotation abandons callbacks and releases all captured closures/owner; only the
launched code occupies the slot until its late result is consumed. Foreign,
duplicate, prelaunch and cold-process reserved results never become authority.
Stale source results reconcile only their own still-current UI, not a newer one.
Worker admission rejection and asynchronous failure cannot leave a live UI busy.
Existing folder grant validation, foreground confirmation and publication gates
remain in the existing runtimes. Results can arrive while Activity is STARTED:
RESUMED is required for launch, not artificially required for result dispatch.

DurableFolderPickerAllocator is confined to the serial allocation worker.
Failed/throwing commit (or corrupt counter) seals this process allocator: no
launch, no retry of the uncertain candidate. Successfully committed codes are
consumed even if the Activity cancels, times out or platform launch throws.
Across process death, no code ever passed to the OS can be reused: its successor
was committed first. For a disk-rejected commit there is no claim that the
unlaunched candidate gap was durably burned across restart; such a promise is
impossible without acknowledged persistence. No picker/result was issued for
that candidate, and this process refuses any further allocation after uncertainty.

Pure tests cover allocation/main separation, repeated-click admission, commit
failure/throw poisoning, cancellation between commit and main post, replacement,
rotation, late/foreign/duplicate/prelaunch results, missing Activity, platform
failure, pause/resume, picker-induced pause, deadline and stale-source UI fencing.
A real worker/latch test blocks persistence and verifies main-thread cancellation
returns without taking a persistence lock. Android lifecycle/OEM/SAF and real
performance remain NOT_RUN/NOT_MEASURED. No phone or emulator operations.

Final evidence is under artifacts/ui/folder-picker-async-20261012. Results are
recorded only after the final source's serial strict offline verification.
Intermediate first lint emitted an internal quickfix exception while source was
being refined, despite a successful build; it is not final lint acceptance.
Unmodified-source final verification logs retained externally. The increment is
for independent review; no APK assembly, install, upload or ANR-fix claim.

Final59 actual JVM tests PASS/0 fail/error/skipped (20 new), Debug and Release
Kotlin executed, lint59 warnings/8 hints/0 errors and no internal exception in
the final log. 67 tasks:15 executed/52 up-to-date. Existing code7 input SHA
6db2a62a7e4461747eb02ae40115353496adbbf006b61e10a7d3a4ed3bb2874b
rechecked unchanged. No claim of Android StrictMode or device reproduction.

## Independent P2 follow-up

02bfec3 was found to reuse its launch predicate for result correlation, which
wrongly rejected Files RESULT_OK received before onResume. Its59 tests did not
exercise foreground=false during result delivery. See
FOLDER_PICKER_RESULT_CORRELATION_20261012.md for the separate correlation fix
and new lifecycle-order regressions. Original results above are historical.
