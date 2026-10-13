# Independent review — current storage contracts

2026-10-13; reviewed test-only scope against source baseline 93a67ba.

Final result: no blocking findings. Independently executed host suite: 14/14, 344
host-model rollback locations. Codec and owner tests were independently reviewed;
actual complete App JVM rerun was separately performed by the build owner (1761/1761).

A P1 false-positive risk was found in the initial host model: Python unconditionally
supplied edit_source and FK rejection, even if the corresponding Kotlin delegation
or check was removed. In-memory mutations initially stayed green. The added exact
nested dispatch and rejection binding now makes both mutations fail; reviewer
independently confirmed the fix. No production source was mutated or changed.

Final follow-up independently confirmed transaction-internal FK validation and
post-commit/reopen quick_check timing, plus preservation of a seeded historical
COMPLETE library_operation row. Both follow-up changes retain 14/14 passing results.

Codec review: frozen fixture hashes and independently built wire oracle; real
production encoding/decoding, complete authority combinations and malformed-input
rejection. Owner review: late FD closure, multilevel nesting, original exceptions,
depth recovery, thread isolation and ownership lock availability; assertions do not
swallow expected failures. No obvious flaky scheduling dependency identified.

Limits: source binding plus synthetic host SQLite is not Android helper execution;
representative seed rows are not every historical state; v1 published-media backfill
is intentionally not simulated. FD callbacks model Windows late-close behavior;
they are not a Windows native or Android device test. Historical gates remain unchanged.
