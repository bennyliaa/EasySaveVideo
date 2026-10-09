# Compilable M8-A work in progress

2026-10-07 UTC, baseline M7-C `35dfacf03c38b6359719e184e74e1d2ce5880625`.

The current-tree strict-offline focused run passed the Discovery and app JVM
suites, debug/release Android Kotlin compilation and prerequisite exact74
admission. See `evidence/focused-wip-current.log`. The earlier first run is
retained as diagnostic evidence; a subsequent run covered coordinator corrections.

Stable local ordering, checkpoint-v3 persistence with genuine v1/v2 migration,
per-provider committed progress and bounded coordinator correctness changes are
implemented. New ordinary regression coverage and independent source review are
still in progress. Both lint variants, the full aggregate, final frozen review
and two cold-restored full reruns are not yet complete at this checkpoint.

This is a recoverable development checkpoint, not full M8 acceptance. Unchanged
Commons schema1, empty production roots/endpoint, disabled Vault, exact74 and the
existing SkinKit contract stay in force. No ESV APK/AAB or device operation occurred.
Older v1/v2-only binaries cannot read newly written v3 checkpoints; unsupported
formats fail closed with original storage preserved, rather than being reset.
