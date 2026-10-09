# Compilable M7-C work in progress

2026-10-07 UTC, following baseline `13ab2b09e1ba33b1e8312b52ba42f78939071c3d`.

The third strict-offline focused run passed: 218 Commons JVM tests, 715 app JVM tests,
debug/release Android Kotlin compilation and the prerequisite exact74 admission. These are
focused counts, not a complete aggregate. New app coverage is 34 tests; Commons is 41 above M7-B.
The earlier diagnostic failures identified a boxed Int/Long default in a health-token comparison;
the final focused run includes the explicit Long correction and real three-observation promotion.

Work includes compiled fixed-vector self-test, bounded candidate observations, exact-authority
one-use tokens, atomic isolation and SkinKit management. Candidate publication rechecks its own
deadline; malformed-success parser shapes and non-fresh cooldown evidence have been tightened.

Independent review is still in progress, including callback reentrancy hardening. Full aggregate,
both lint variants, frozen independent-source review and two full cold-restored input-tree reruns
are not complete at this checkpoint. This WIP is recoverable development work, not acceptance.
Production roots/endpoint remain empty; no APK/AAB/device/production action occurred. Vault remains
disabled and untouched. Refer to the later final acceptance and external recovery receipt.
