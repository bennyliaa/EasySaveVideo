# Save task/selector locale display slice

## Original requirement

Exact original UI framework01 §7 lines148/150/152 requires resource-backed product text and
plurals, consistency with the old-system Compose resource context, and locale-aware numbers,
time and file sizes. UI milestone02 UI-B is the associated source scope. Original00 through06 were fully read, including03§7 centralized display formatting,05§3
allowing existing UI-layer resource IDs and04 separating JVM display checks from Android locale
acceptance. Original07/08 and the PPT/ZIP were not available to this review. User's later API31/payment/import/Vault/history decisions remain in force.

## Bounded implementation

SaveTaskScreen now routes verified-output metadata, recording duration, candidate metadata and
HLS budget/variant copy through14 paired string entries and two plural resources. English has
one/other; Chinese has its applicable other form. Unknown duration/resolution remains unknown.
No unavailable media metadata is guessed. Existing recording audio-outcome and stop-reason
resource mappers are reused rather than duplicating their states or translations.

The already-present successful-task discovery-history outcome is mapped to four distinct
resource IDs. Media success and discovery-history outcome remain separate: stale/missing/failed
history does not rewrite media success, and unverified video identity remains explicitly unknown.
The existing SUCCEEDED/evidence/disposition branches and mediaTaskHasCommittedSave are unchanged.

LocalConfiguration supplies the active resource Locale on every composition. This follows the
existing API31/32 EsvLocaleHost context, whereas the process default may be different. The empty
locale-list fallback is Locale.ROOT; no global locale is set and no stale formatted string cache
is added. Existing API33+ platform locale owner and settings persistence are untouched.

Pure display helpers retain binaryKiB/MiB/GiB thresholds and precision (sub-MiB integerKiB,
MiB1 decimal, GiB2 decimals), whole-second truncation, negative-recording-duration zero floor,
hours beyond24 and HLSbandwidth/1000 truncation. Actual numbers are rendered with the explicit
Locale. Android plural selection receives boundedInt; the displayed count remains the original
Long. This bounded plural selector supports the currently delivered EN/ZH forms and does not
claim a full additional-language/RTL release.

## Preserved behavior and remaining scope

Independent read-only comparison found16 onClick expressions,6 enabled conditions and9 targeted
runtime calls unchanged. Old business helpers in SaveTaskScreen remain byte-for-byte unchanged;
only the private old display formatter was replaced. URIhost, candidate.label and runtime.notice
remain original text. This is not full-app localization, and existing runtime-generated notices
or other screens' formatters are not claimed translated by this change.

No task command, state machine, success/validation gate, permission, entitlement, source URL,
recording consent, deadline, budget, settings owner, media read/write, dependency or product
policy changes. HLS selection's existing60-second maximum wording remains exactly the existing
policy; this display slice does not set or extend an expiry.

## Verification

App1791 JVM tests actually executedPASS, including17 new tests, with0 failures/errors/skips.
Debug/Release production Kotlin and both lint tasks actually executedPASS. Each lint report
contains60 warnings/8 hints/0 errors, unchanged. Runtime admission verifies90 exact artifacts.
Save module95 tests were UP-TO-DATE and are not counted as a fresh execution. Final build1
succeeded on its first run:77 tasks,33 executed/44 up-to-date. No build failure was suppressed.
Other471 original production/config files and the prior15-contract/16-Shell frozen files
matched their hashes. Detailed census, sanitized logs/XML and source pins are stored in
artifacts/ui/save-display-locale-20261013; independent static review is recorded separately.

New17 JVM tests cover byte thresholds/locale/fixed-point precision, elapsed-time equivalence,
localized digits, no process-locale mutation, Long plural bounds, resource keys/placeholders,
English plurals/Chinese quantities, uncertain-history wording, current-configuration wiring,
unchanged source metadata/actions and existing success branches. XML/source assertions and
pureJVM formatting are not Android locale-switch or rendering evidence.

NOT_RUN: Android language switch/recreation, Compose rendering, screenshots, TalkBack, font200,
real save/HLS/recording/verified media output, APK/device/emulator steps. UI-E and full UI-B
internationalization remain incomplete. Publication and remote verification are parent-owned.
