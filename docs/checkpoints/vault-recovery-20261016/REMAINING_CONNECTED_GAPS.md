# Vault scope after optional recovery source connection

Production entry remains false. This block connects current-installation, healthy-ledger recovery enrollment and password reset, with protected display/re-entry and exact atomic credential mutation. It does not provide a user-operable enabled Vault yet.

## Connected source
- Password setup independently commits READY, retaining the explicit optional recovery choice.
- Fresh primary authentication stages a durable inactive recovery slot. Actual persisted code verification, protected display retirement and fresh re-entry precede ACTIVE CAS.
- ACTIVE recovery plus a new independent password stages and verifies persisted password material, then atomically replaces the primary credential, removes the old current recovery slot and resets the retry ledger. Completion is LOCKED, never an unlocked session.
- Unknown commits retain exact reconciliation and original deadlines. Ordinary primary unlock remains valid with pending enrollment or stale pending reset metadata.

## Remaining code / product integration
- Bad retry-ledger and invalid/lost device-key recovery from the earlier R2 design is not connected. The current flow requires a valid current installation and healthy authenticated retry ledger. This is an unfinished requirement, not a new product decision or a supported recovery claim.
- Standalone recovery-code rotation/revocation management is not exposed. Reset revokes the current code; re-enrollment is available through the protected enable flow. No historical-copy revocation or whole-database rollback protection is claimed.
- PIN and biometric user workflows and hardware admission remain incomplete; existing adapter methods do not imply a completed UI flow.
- The catalog is empty/read-only. Media import, authenticated playback streams, export-as-copy preserving the original, and corresponding library/save transactional recovery remain to connect.

## Device-dependent acceptance
- Real Android Keystore/JCA behavior, SQLite atomicity and process/power interruption, protected Compose/IME/dialog retirement, rotation/background/recents, and recording coexistence have not run.
- Keyguard timing can conceal a screen lock/unlock interval; source screen-off observation does not prove complete lock detection.
- Real credentials, persistent random keys, device authentication and historical denied probes were not executed in this cloud block. Tests use fixed public vectors and fake ports.

The historical dependency-only gate remains unchanged. The new disabled source scope and JVM evidence do not replace its original platform acceptance gate.
