# Compilable M8-B3 checkpoint — before final review corrections

2026-10-07 UTC, baseline d20b1c9. Strict offline/dependency verification with
nonincremental focused rerun: all 54 tasks executed, both Kotlin variants passed.
Discovery 194 and app 842 JVM tests passed without failures/errors/skips.
Current B3 scoped protected-source and fixed Commons main-JAR admission passed.

Policy-only transient inputs, independent power/lifecycle generation fences,
non-refunding round cancellation and exact registry source selections are present.
Production is explicitly NOT_CONNECTED; no OS monitoring or measured energy claim.
Native SAVED/PLAYED remain UNKNOWN. Initial review corrected old signal-snapshot
acknowledgement, old Save/visibility resumes, repeated search/safety callbacks and
source-change/placeholder-state fencing. The first test diagnostic retains a legacy
round-count expectation; canceled attempts are now counted rather than refunded.

One independently identified UI edge remains to be corrected after this snapshot:
rapid same-field IME events before recomposition need field-specific rebasing on
the current draft, while retaining exact target checks for search/safety actions.
Independent host execution, complete aggregate/lint and two full cold restorations
are still pending. This is a recovery checkpoint, not complete M8 or final B3.

## Post-WIP review correction

The IME correction now rebases only the selected field under the same target and
applied identity; the published source-revision fence also closes the asynchronous
provider reinitialization window. The complete final 87-task aggregate passed with
1,759 JVM tests, both compile/lint variants and all inherited/new host regressions.
See IMPLEMENTATION_AND_ACCEPTANCE.md and the external final recovery receipt.
