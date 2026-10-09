# Compilable M8-B2 checkpoint

2026-10-07 UTC. Development checkpoint before frozen independent review,
whole-workspace aggregate/lint and two complete cold restorations.

- Strict offline/dependency verification and nonincremental focused rerun: PASS,
  all 54 tasks executed. Debug/release Kotlin compilation passed.
- Discovery 165 tests and app 801 tests passed, no failures/errors/skips.
- New pure origin/handle/navigation/activity/core correlation tests are present;
  ordinary host evidence does not establish Android WebView/native lifecycle behavior.
- Exact-source B2 admission passes with 777 protected files unchanged, the admitted
  Commons main JAR retaining 127 classes. Runtime exact74 is rechecked by the pending
  full aggregate, not claimed newly rerun at this checkpoint.
- PLAYED remains unknown because no defensible native playback identity bridge exists.
- An initial build overlapped the final addition of pruneExpired and its tests;
  its unresolved-reference diagnostic is retained. The subsequent frozen 54-task
  nonincremental rerun is the passing checkpoint used here.

Media publication and Discovery history failures remain separate. M7-D/Vault,
new sources/dependencies, APK/AAB/device work and production service actions remain
excluded. No B3/M9 work is started. The parent receives full Git and source backups
privately; this WIP is not final M8-B2 or complete M8 acceptance.

Independent review is in progress. The initial snapshot still has two identified
activity-observation windows (initialization handoff and expired-task cleanup)
that are being corrected after this checkpoint. Page-origin attribution is proven;
identity equivalence between arbitrary same-page media candidates and the provider
video identity is not proven and remains a completion/acceptance decision. This
snapshot must not be represented as final review acceptance.

## Review-corrected compilable checkpoint

At 08:52 UTC the corrected strict offline/nonincremental 54-task rerun passed:
Discovery 165 and app 814 tests, zero failures/errors/skips, both Kotlin variants.
Startup handoff and due-cleanup ownership are corrected. READY-phase history
callbacks now fail closed. The app explicitly supplies UNKNOWN video identity;
SAVED and PLAYED emission remain deferred. The generic API's positive fixtures use
injected VERIFIED evidence only. Final independent review, complete post-correction
aggregate and two full cold restorations remain pending.
