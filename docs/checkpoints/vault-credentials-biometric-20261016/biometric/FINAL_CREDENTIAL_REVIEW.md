# Biometric credential/schema implementation: final slice

2026-10-16. Production entry remains false. This is source and fixed-data JVM acceptance, not real Android biometric/security acceptance.

## Delivered

11 final source/test files: 7 production and 4 test files. The final overlay exactly matches the build owner's second successful test run. BASE_TARGET_MANIFEST.json binds original pre-implementation G1/G2 baseline bytes and final bytes. CREDENTIALS_FINAL.patch consolidates first freeze and deltas 01–03. Earlier freezes and delta artifacts remain unchanged.

Implemented:
- Bounded authenticated NONE/PENDING/ACTIVE/REVOKED model, maximum one primary/biometric/recovery slot; legacy v1/v2/v3 bytes remain canonical and unchanged without biometric state.
- Snapshot/catalog v4 preserves media entries, wrapped media keys, source-cleanup flags and media journals; state, generation, slot identity and envelope digest participate in authenticated prefix/fingerprints.
- Real Authentication owner prepare→start→drain operations; exact attempt/root/workflow/cancellation lineage; current predicate outside registry lock; atomic successor and alias-permit consumption rechecks.
- PENDING stage requires exact codec/store-shape readback and a distinct decrypt before ACTIVE mutation. Verification never mints ordinary Root. Ordinary ACTIVE unlock registers same-owner Root only after real fixed-fixture keyset/catalog verification.
- Fresh-password provenance for enrollment/revocation/alias permissions. Root catalog binding never rewritten. Recovery enrollment/password reset preserve non-target biometric state/slots and all media/ledger evidence.
- ORPHAN_NONE generation absent and REVOKED exact generation retirement permits. Unknown deletion intent remains original and read-only absence reconciliation does not repeat delete/create.
- Fixed 395-second original workflow maximum; separate bounded 120-second prompts; each SQL child remains within issuing mutation's exact prompt deadline.

## Observed verification

Build owner second run succeeded with production Kotlin compilation and unit-test Kotlin compilation. Selected owned/relevant suites were independently read from actual JUnit XML and copied into evidence:

- New credential/schema: 10/10
- New actual-owner connections: 9/9
- Existing real credential reseal: 8/8
- Existing credential mutation validator: 8/8
- Existing recovery snapshot codec: 9/9
- Existing media snapshot codec: 5/5
- Existing recovery crypto/wire connection: 17/17
- Existing failure-wire compatibility: 3/3

Selected total: 69 tests, zero failures/errors/skips. Combined second run reported 5,167 tests across four modules, including 4,737 app tests, zero failures. That combined count includes other workers' work and is not this slice's new-test count. Subsequent full aggregate run belongs to the build owner and was still running when this slice was finalized.

First run correctly exposed a test-fixture error: repeated constant synthetic catalog ciphertext in a stage→activate→revoke shape test violated the existing changed-ciphertext invariant. Delta03 makes each synthetic revision distinct; production validation was not weakened. Deltas01/02 isolate Root provenance and close two owner-local atomic revocation windows.

## Evidence boundaries

Owner connection tests stage only the prerequisite password-authenticated Root by reflection using the repository's existing public AES256-GCM fixture. This does not exercise or claim password authentication, password KDF, or retry RESET completion. Thereafter actual new production prepareEnrollment/start, catalog reseal, mutation registry, canonical snapshot readback, persisted envelope decrypt, activation, ACTIVE unlock, revocation, and alias-permit methods execute. Biometric operation state and mutation registries are not fabricated.

The narrow fake leaf replaces platform work and performs actual Tink wrap/unwrap with fixed public key material. Device AEAD and alias/create/delete hooks use fixed fakes. Tests include cancellation/expiry, altered readback, foreign workflow/lineage/capabilities, provider-stage revocation, both retirement kinds, unknown intent retention and password-authority rejection after a healthy RESET ledger with a positive password-root control.

No real AndroidKeyStore, key creation/deletion, user credential, platform prompt, database crash, screen pixels, APK, device, system setting or production-enable action was executed. Physical alias cleanup, native prompt/resource lifetime, protected-screen behavior, actual password chain and crash/restart semantics still require their separate authorized platform acceptance. A fake success cannot establish those facts.

## Dependencies and scope

The existing Tink/Android versions, dependency admission bytes, core platform boundary and G1/G2 repair semantics are retained. New VaultBiometricCryptoLeaf is a narrow internal adapter seam implemented by the existing AndroidVaultBiometric; it owns no Root/mutation registry and introduces no independent authentication authority. Prompt/Keystore implementation and runtime/protected UI are companion slices reviewed separately.
