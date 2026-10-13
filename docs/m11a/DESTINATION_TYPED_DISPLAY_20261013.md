# Ordinary destination typed notices and copy-state display

Original UI 01 §7 requires localized product copy resolved through the current UI locale.
The earlier fixed-copy slice left runtime notice and copy-state strings deferred. Inspection
shows these are application-generated messages, not user filenames, webpage titles, URLs,
exception messages or provider-authored text. This slice closes that bounded display path.

The destination runtime now publishes one of 26 typed notice values at the same original
assignment sites. Null continues to mean no notice; existing modal/null gates and clearNotice
retain their semantics. The UI resolves the notice from English/Simplified Chinese resources
in its current Composition at both actual Settings and Dialog display sites. No resource
context is stored in the runtime and no storage format is changed.

Copy-status presentation keeps the original precedence: no additional copy selected; internal
save not yet verified; prepared/unstarted copy; verified success; failed/cancelled; otherwise
processing or unresolved. Folder/album kind is displayed through a complete localized format.
Only existing status facts are mapped; no inferred completion, retry, copy, cancellation,
cleanup or grant action is introduced. APP_LIBRARY's historical kind fallback remains intact.

The original 26 fixed-copy resource pairs remain. This slice adds 26 notice, two kind and six
copy-state pairs. User-owned values, task IDs, media data and all Save runtime notices remain
outside the migration. Exact callback/thread/queue/persistence logic and notice timing are
preserved; English text preserves originals, grants, cancellation and uncertain-copy limits.

App 1864 JVM tests actually executed PASS, including 7 new tests covering all 26 notice
codes and 72 choice/saved/phase combinations. Zero failures, errors or skips. Debug/Release
Kotlin passed build1 on unchanged production inputs; final build2 tests and both lint passed; each lint retains 60 warnings / 8 hints /
0 errors. Runtime admission verifies 90 exact artifacts. Theme was not rerun. All 484
other original production/config inputs match their pre-slice hashes. This source-verified
slice follows the 33-file authentication/fixed-copy freeze and awaits publication.
See artifacts/ui/destination-typed-display-20261013 for sanitized logs, actual JVM census,
new-test XML, lint reports, source pins, static reconstruction and independent review.

NOT_RUN: APK, device/emulator, live locale switching, Compose event ordering, SAF/provider
behavior, real output copies, 200% layout, TalkBack or PPT. Typed-message/JVM fixtures do not
establish native export acceptance. Original PPT binary remains unavailable.
