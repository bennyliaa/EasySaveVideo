# Vault implementation status — 2026-10-16

This page updates the earlier dependency-only/empty-catalog status without replacing historical dependency evidence. `AndroidVaultRuntimeEntry.ENABLED` remains false. Compiled source and JVM tests do not establish Android security, successful real authentication, real encrypted-media playback or a releasable Vault.

## Connected source paths

| Original requirement | Current code status | Verification boundary |
| --- | --- | --- |
| Password setup/unlock and protected Vault host | Runtime, encrypted catalog adapters, Main protected host and setup/unlock UI are connected behind the disabled entry | Fixed public vectors, fake ports, JVM and compilation; actual user keys, Keystore, Android SQL, authentication and protected pixels are not executed |
| Strong biometric enrollment, explicit unlock and revocation | Existing authentication owner, exact CryptoObject/slot-generation binding, staged authenticated readback and activation, protected host actions and password fallback are connected behind the disabled entry | Public fixed-key fixtures and fake platform callbacks; real prompt/Keystore/enrollment changes and protected focus behavior remain untested |
| Optional offline recovery code, chosen during password setup | Optional enable/display/acknowledge and reset/revocation source paths are connected; primary password remains independent | No generated user recovery credential or device execution |
| Immediately / 1 / 5 / 15 minute automatic lock | Existing reducer/runtime implement confirmed leave-Vault/background anchoring, pre-expiry return cancellation, and locked process restart; four-option Privacy settings and retained-owner persistence are connected behind the disabled entry | JVM/state/source and compilation evidence; real Settings rendering, Android preferences lifecycle and device timing remain untested |
| Move one committed ordinary-library item into Vault | Existing Library action is connected to same-owner media import and precise source-cleanup/recovery logic | Fake file/store/auth boundaries; real encrypted files, SQLite and actual user-data deletion are untested |
| Copy one item to a phone folder | Existing SAF export source uses the authenticated read owner and retains the Vault original | Fixed bytes/fake provider; real SAF/provider and device behavior are untested |
| Batch copy with one folder choice | Bounded sequential queue reuses single-item owner/SAF path; partial success, cancellation and explicit recovery are implemented | JVM/source contracts; real Android provider/physical interruptions remain untested |
| Controlled manual playback | Current slice connects same-owner read capability, Media3 DataSource/player, protected Surface slot and UI commands; revocation and actual retirement are accounted separately | Integrated compilation and JVM state-machine/source contracts; real codec, AudioTrack, Surface, Looper and capture/privacy behavior are not executed |
| Private in-memory visible thumbnails | Explicit visible-only loading uses the existing same-owner read reservation, shared single-frame MMR lane and opaque protected display handles; no plaintext file or ordinary shared cache | Fixed-input fake-port tests and integrated compilation; real Android decode, pixels/GPU, viewport/capture and physical release behavior remain untested |

The playback slice introduces no plaintext temporary media file, public file URI, network fallback, MediaSession or new dependency. Opening Vault while the existing capture exception is active is refused; ordinary continuous screen recording is not automatically stopped to permit Vault display. A playback stop does not silently become a new account auto-lock rule.

## Credential recovery and fault boundaries

Revision-bound catalog-keyset resealing is accepted by the credential mutation validator while the original same-owner registry, complete snapshot CAS, exact slot transition, installation marker, retry ledger and media-journal checks remain required. Fixed public-key tests exercise the real reseal/registry/validator/decrypt connection; they do not establish the full Android credential workflow or SQLite execution.

The runtime now preserves bounded device-key, installation-marker, retry-ledger and unreadable-store reasons through the protected error presentation. These failures retain ciphertext and remain fail-closed. Optional recovery codes currently support forgotten-password recovery only while the device key and authenticated installation/retry state remain healthy. They do not provide a supported repair for a missing or invalidated device key, damaged retry ledger, restored foreign installation or unreadable store. No automatic key recreation, ledger reset or destructive replacement is implemented by these diagnostics.

## Remaining code work

1. Missing/invalid device-key, damaged retry-ledger, foreign-installation and unreadable-store repair are not implemented. Accurate diagnostics retain ciphertext; they do not provide recovery.
2. Biometric enrollment/revocation with an uncertain exact-alias operation remains blocked in that retained process owner; no fresh budget or automatic key recreation is used. Healthy password/recovery paths remain independent where existing state and release gates permit them.

These are distinct from pending platform acceptance. Broader providers, permanent SAF grants, cross-install key-loss recovery and arbitrary backup behavior are not claimed by the limited media implementation.

## Required platform acceptance before enabling entry

- Exact admitted Tink/Android/Keystore credential and recovery-code workflows, failure throttling, key lifecycle and real authenticated stream behavior.
- Real strong-biometric two-prompt enrollment, committed-envelope activation, explicit unlock/revocation, negative/cancel/focus/Activity changes, enrollment invalidation and exact-alias retirement/readback. Fixed fake results do not establish those platform outcomes.
- Real SQLite transactional persistence/reopen and file identity/commit/recovery behavior, including interruptions and unknown release outcomes.
- SAF folder/child identity, returned grants, copy/readback and partial recovery on supported Android providers.
- Real Media3 prepare/play/pause/seek/error/release, renderer and AudioTrack retirement, loader/extractor return, reader/file close and owned Looper termination.
- Protected Window/Surface pixels and input during route changes, foreground/background, lock, Activity replacement, capture conflict and failure cover, on supported phone/tablet/API combinations.

Cloud reports must retain their fixed-data/fake-native/source-wiring labels. Neither successful compilation nor a high aggregate JVM count removes these gates. No APK delivery or device installation is part of this checkpoint.
