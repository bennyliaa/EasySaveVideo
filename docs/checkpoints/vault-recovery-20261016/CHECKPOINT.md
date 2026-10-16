# Disabled Vault runtime, protected host and optional recovery checkpoint

2026-10-16. Baseline development commit f9bc079f740f91dc57afd58e152f45e852f87b4f.

The source now connects independent primary-password setup/unlock, exact protected Main/root/dialog/input lifecycle, optional offline recovery enrollment with persisted inactive candidate verification, protected code display and fresh re-entry, and atomic password reset/current recovery-slot revocation. Recovery reset ends locked. All production gates remain false.

Validation: final focused 437 suites / 4407 PASS; core-model7 reused, core-domain120/app4280 actually executed. Both Android variants compiled. Final full 13-module run: 558 suites / 6195 items = 6193 PASS + 2 pre-existing skipped fixture assumptions, zero failure/error. Full build exit0, 5m8s. Both lint variants: 77 warnings, 10 notes, zero errors. One new unused legacy recovery-label warning remains visible; no suppressions added.

History retained: first compile failed a cross-module smart cast; second passed compilation but one sequential-stage test reused the same public catalog bytes. Local-variable compile fix and test-only revision-varying fixture were independently reviewed and passed subsequent runs.

Testing executed only pure/fake-port workflows, fixed public vectors, compiled Android adapters and source contracts. No real persistent random credentials, AndroidKeyStore/biometric authentication, SQLite device operations or old denied probes were executed. No APK was installed or delivered.

Remaining: production admission and actual Android security/storage/IME lifecycle acceptance; healthy-ledger/current-installation recovery does not yet connect prior bad-ledger/device-key-loss repair; PIN/biometric user workflows; Vault media import, playback, export-as-copy and transactional recovery; standalone recovery management. No server or cross-device recovery promise, no historical-copy cryptographic revocation or whole-database rollback protection. See REMAINING_CONNECTED_GAPS.md for exact boundaries.
