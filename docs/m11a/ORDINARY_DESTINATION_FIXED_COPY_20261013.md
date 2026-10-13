# Ordinary destination fixed-copy localization

Original UI 01 §7 and 03 §7 require product strings to resolve through locale resources.
This bounded slice moves OrdinaryDestinationUi's fixed labels, explanations, confirmations,
selected-prefix and short task-ID format into 26 paired English/Simplified Chinese resources.
A resource-ID mapper covers all four destination preferences; stringResource resolves values
in the current composition. It does not use a process-global locale or translate source data.

The actual Settings, selection/notice Dialog and task destination-history display remain
connected to their existing owners. The selected-prefix and task summary pass typed string
arguments. English retains the exact boundaries: App Library is verified first; album/folder
outputs are additional copies; originals survive failure/cancellation; providers may sync to
cloud or expose writing copies; changing/forgetting the folder does not silently revoke old
system grants or modify already accepted tasks.

Independent review reversed the text replacements to recover the entire original UI file.
All 12 click callbacks, 2 dismiss callbacks, 4 enabled expressions, internalSaved/take(8)
filtering, layout and destination-runtime bytes remain unchanged. No permission, persistence,
copy, revoke, cancel or reconciliation behavior was modified.

This is deliberately partial localization: runtime.notice and ordinaryCopyStatus(status)
remain their existing dynamic strings and require a separate typed-message slice. It does
not claim the full destination page or all app errors are bilingual.

Final build2: App 1857 JVM tests actually executed PASS, including 22 new tests
(authentication policy 10, source wiring 5, destination resource contract 7); zero failures,
errors or skips. Debug/Release Kotlin compilation and both lint tasks executed PASS.
Both lint reports retain 60 warnings / 8 hints / 0 errors; runtime admission verifies 90 exact
artifacts. Theme tests were not rerun in this group. All 478 other original production/config
files match pre-slice hashes. build1 also passed before the final independent-review refinements.
See artifacts/ui/auth-destination-copy-20261013 for sanitized logs, actual JVM census,
new-test XML, lint reports, source pins and independent review. Latest published baseline is
3176bcc0be84325be5b278823995761f17049dd8; this source-verified group awaits publication.

NOT_RUN: APK, emulator/phone, locale switching/restart, SAF/provider behavior, actual copy
results, screen rendering/200%/TalkBack/PPT. This change establishes source/resource wiring,
not native export or full UI acceptance.
