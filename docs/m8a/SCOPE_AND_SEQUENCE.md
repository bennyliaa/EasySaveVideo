# M8-A: local Discovery ordering and truthful progress

Baseline: M7-C `35dfacf03c38b6359719e184e74e1d2ce5880625`.
This is one independent M8 development slice, not full M8 or release acceptance.

## Included

- Stable local source-arrival, publication-date and duration sorting, applied after
  the selected history-policy priority, with unknown metadata last and stable ties.
- Preserve current materialized page while revising later pages. Store the selected
  local order in checkpoint v3; genuine v1/v2 inputs default to source order.
  Ordering does not change the upstream query key, history profile or cursor identity.
- Per-provider progress derived from committed progress and active bounded work.
  Do not invent a percentage, remote page count or reliable playback/save event.
- Ordinary coordinator correctness: productive admission at capacity, query/version
  scoped no-new cooldown, real source-wide rate-limit retention, reset behavior,
  current state presentation, cancelled resume fencing and pinned-capacity preflight.
- Existing frontmost-only, request/deadline/retry/concurrency limits remain intact.
  Polling status never creates demand, advances a cursor or resets a source.
- Invalid local UTF-16 query input must not strand the prior valid discovery session.

## Excluded and unchanged

M7-D's separately blocked rule-runtime candidate is not integrated, reviewed or
retested here. The compiled Commons schema1/parser stays unchanged. Vault remains
disabled; no substitute for the blocked independent Vault review is attempted.
There are no new providers, extraction rules, dependencies, production roots or
endpoints, operational services, real accounts or billing. Exact74 remains the
only admitted external artifact set. Current admitted SkinKit contract bytes remain.
No APK/AAB assembly, emulator/device installation, or device acceptance is performed.

## M8 work still open

Long-term history/profile capacity and explicit eviction disclosure; trustworthy
Browser/player/save-event correlation beyond existing SHOWN/OPENED integration;
explicit all-history management; actual Save/Record task-state priority wiring and
battery/network/thermal policy integration;
source-relevance normalization only if a future admitted provider supplies suitable
metadata. Existing local filtering and four policies are exercised, not claimed as
new live-provider breadth. Commons service/device tests remain unperformed.

## Verification sequence

Focused compile/test checkpoint and private recoverable source bundle first.
Then an ordinary independent source review; strict-offline full JVM suites, both
Android Kotlin compile/lint variants, exact74, unchanged provider/media host suites
and SQL/DOM regressions. Commit/tag, full bundle/source and two cold restore full
reruns are recorded in an external receipt. Each evidence document identifies
passed, failed and never-run gates separately.
