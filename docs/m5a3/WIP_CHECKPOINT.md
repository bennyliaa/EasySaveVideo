# Recoverable A3 WIP checkpoint

This is an explicitly unaccepted ordinary-library development recovery point.

The integration has passed Debug/Release Kotlin compilation, the current library/app JVM suites, exact 72-artifact admission, the inherited 14 catalog SQLite contracts adapted to v3, the 12 new export SQLite/static contracts, and unchanged HLS host 81+15 fixtures. The latest interim run has 129 library and 397 app JVM tests with no failures, errors or skips. Full final aggregate/lint, final source review and cold-build acceptance remain pending.

## Open review and implementation work

- Finalize bounded repeated-open, seekable read-only platform proxy descriptors. The initial single-open pipe has been replaced, but the proxy open currently needs a lock-inversion fix (R7) and the callback lane needs failed-initialization thread-bound hardening (R8). This WIP does not claim general recipient compatibility.
- Move share expiry cleanup off the main thread and retire completed timer references.
- Re-review exact SAF picker creation/cancel/process-death gaps, unverified-output cleanup and verified-publication recovery against final adapter source.
- Rerun all tests, strict lint and source/privacy/dependency guards against frozen final production sources.
- Complete final documentation, restore verification and cold build.

Previously found full output-readback, recovery gate cancellation and pending MediaStore query issues have source fixes and interim tests; final review remains pending.

Android/OEM native decoder, MediaStore, document provider, URI grant, proxy descriptor, actual permissions, phone/tablet UI and recipient compatibility are NOT_RUN. No APK/AAB, install, real media action or release is performed. Vault and configurable save destinations remain outside this slice.
