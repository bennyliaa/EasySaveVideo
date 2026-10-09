# Compilable M8-B1 checkpoint

2026-10-07 UTC, before independent review and full aggregate/cold acceptance.

- All 54 focused Gradle tasks rerun offline, strict dependency verification,
  Kotlin incremental compilation disabled: PASS.
- Discovery 144 tests and app 739 tests: zero failures/errors/skips.
- Debug and release Kotlin compilation: PASS. No APK/AAB assembly or install.
- Historical1→2 rights host SQLite contracts and new1/2→3 history contracts: PASS.
- B1 exact source-scope guard: PASS, existing Commons main JAR127 classes unchanged.
- Earlier incremental diagnostic run hit stale anonymous Kotlin delegation classes
  (`AbstractMethodError` after a new transaction interface method). The complete
  nonincremental rerun passed; diagnostic log is retained. Subsequent aggregates
  explicitly disable incremental Kotlin to avoid relying on stale bytecode.

Independent frozen-source review, full JVM aggregate, both lint variants, exact74
runtime inventory and two full cold-restored reruns remain pending at this checkpoint.
The parent receives a full Git bundle and tracked-source archive for private backup;
this WIP is not the final development tag or milestone acceptance.
