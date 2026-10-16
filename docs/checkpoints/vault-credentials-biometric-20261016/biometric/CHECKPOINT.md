# Vault biometric adapter checkpoint — 2026-10-16

Production entry remains false. No real user credentials, persistent key creation, device prompt, enrollment, security-setting change, APK or device execution occurred.

## Implemented code
- Same existing authentication owner and opaque registry bind biometric purpose, exact host/workflow/attempt, slot generation, actual CryptoObject/Cipher reference, catalog and snapshot.
- Explicit enrollment follows fresh-password workflow, PENDING commit/readback and a separate decrypt/verification operation before ACTIVE. Revoke preserves primary password/recovery/media state. Old password/recovery wire bytes remain compatible; biometric metadata uses a bounded authenticated version.
- Existing prompt leaf serializes submission/cancellation and accounts for actual callback/owned-work drain; negative dismissal is terminal, generic uncertain submission remains retained. Exact alias retirement cannot become broad cleanup or key recreation.
- Runtime and protected host/UI are connected. System prompt focus and Vault composition cover handoff use narrow exact gates; unrelated focus loss/background/route/capture still revoke. Ordinary continuous recording is not stopped to reveal Vault.
- Restarted PENDING can be explicitly revoked after fresh password, then registered again; it is never silently activated. Unknown alias retirement remains held in the retained process owner with no renewed budget.

## Corrections and evidence
- Independent review closed registry/runtime lock inversion, owner revocation recheck gaps, continuation-enqueue drain gap, and unlock cover handoff gap.
- First integrated run compiled both variants, then had two failures: a stale Main source-contract literal and a test fixture reusing the same catalog ciphertext. Production ciphertext-change validation remained intact. Failed log/XML retained.
- Corrected focused run: 505 suites/5167 tests, zero failure/error/skip, SUCCESS3m57. App4737 reran; unchanged core430 were up-to-date. Both Kotlin variants actually compiled.
- Seven new biometric suites contain84 actual passing tests: credential contract10, actual owner connection9, prompt lifecycle27, fixed cipher5, exact alias5, runtime21, host/source7. Owner connection stages only its prerequisite password Root by reflection from public fixed keysets; subsequent new Auth methods, registry, reseal, codec/readback and activation are real. It does not prove fresh password/KDF/RESET or Android SQL.
- Final forced13-module run:602 suites/6675 items =6673pass+2 existing corpus skips, zero failure/error, exit0/SUCCESS8m36. Both lint variants have0errors,86warnings+10notes, unchanged totals.
- FINAL32 source SHA25657e9c5fc0c1d6ccdcbc7e7ccce873e9386438b6d83c4846ea696fa97275e016c; complete source2801 files.

## Still unavailable or unverified
Real strong biometric/KeyStore/two-prompt/focus/Activity invalidation, SQLite persistence/crash recovery, protected pixels and physical hardware behavior remain NOT RUN. Production remains disabled regardless of JVM counts. Missing device-key/damaged-ledger/foreign-install/unreadable-store repair is still not implemented; G1/G2 diagnostics do not substitute for it. No cross-installation rescue is claimed.

## Recovery and synchronization
Local evidence: esv-runtime-copy-20261015/evidence/vault-biometric-adapter. Verified source/evidence archives are recorded in recovery/VAULT_BIOMETRIC_ADAPTER_VERIFIED_RECOVERY.json after independent final review. Remote baseline remains d33a1c0588b2c714fa91418363075e08071a0500 until the separate exact incremental synchronization completes. G1/G2 and biometric changes form this next combined source batch.
