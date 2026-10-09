# Compilable M9-A checkpoint

2026-10-07 UTC. Baseline dfdddbb. Strict offline/dependency verification and
nonincremental focused rerun passed all 57 executed tasks. Discovery 205, Commons
238 and app 859 JVM tests passed with zero failures/errors/skips; both app Kotlin
variants compiled, exact74 runtime admission passed during the build.

Two ordinary coordinator defects were reproduced before correction: an old retry
attempt callback could complete a newer attempt, and a delayed storage transaction
could commit at/after the original absolute deadline. Callback and handle ownership
now use the launched operation identity; the final atomic commit rechecks deadline.
Cancellation still rolls back results, Seen aliases and cursor as one transaction.
No admitted transport/runtime/parser, dependency, source or Android UI was changed.

The new matrix adds 11 coordinator, 15 Commons cache/failure, 7 socket transport and
6 independent Browser/Save/codec tests. Standalone host and frozen review execution,
full aggregate/lint and two full cold restorations remain pending. This checkpoint
is not complete M9, device compatibility, power measurement or release acceptance.

The initial focused run covered 13 new Commons cases; two final ordinary body
progress/deadline cases were then added before the first snapshot. The final
focused rerun recompiles and executes the exact 15-case source now committed.
The earlier WIP text mistakenly stated Commons 269; recorded XML was 236 for
that earlier run and is 238 for this final focused checkpoint.

## Final aggregate after WIP

The subsequent frozen ordinary review and full 87-task aggregate passed with
1,798 JVM tests, both compile/lint variants and current exact74/source admission.
All inherited and new host checks passed; see IMPLEMENTATION_AND_ACCEPTANCE.md.
The external restore receipt records the later exact final commit/tag and two
complete cold restores, without changing this checkpoint's historical status.
