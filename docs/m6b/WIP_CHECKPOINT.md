# M6-B compilable WIP, not acceptance

Baseline M6-A: 9b25083134221bb4a86d71e76601cc65b7d10937.

The first integrated region/segment route now compiles for debug and release under strict offline dependency verification. Focused recording/library/app JVM tests pass; see evidence/wip-dual-compile-r2.log and wip-junit-summary.json. The build also confirms exact74 admitted artifacts. Initial failures remain in earlier logs: the safe consent metadata field assertion was updated for the explicit default-display-request boolean, and the measured-tail generic was constrained to non-null sample values.

This WIP deliberately precedes final source-review corrections, dual lint, full aggregate/host regressions and cold restoration. Known next work includes a bounded AAC tail queue for independently closed segments, a jitter-safe input frame-admission policy (never fake CFR), late-cancel/retained-cleanup watchdog tests, and stale writer-capability/corrupt journal-tail hardening. No device, permission, actual recording or GPU acceptance is claimed. Actual-content preview selection is not implemented: selection is an explicitly disclosed ratio schematic applied to the later system-authorized image. Vault remains disabled and untouched.
