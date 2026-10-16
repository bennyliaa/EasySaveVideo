# Vault biometric owner/schema/CAS independent review

Final status at 2026-10-16 12:40 UTC: **PASS for the reviewed source, fixed-data JVM regression and compilation/lint boundary.** Production entry remains false. This is not approval to enable the feature or evidence of real Android authentication, KeyStore behavior, SQLite, protected pixels or physical prompt retirement.

## Exact source and review chain

- Design v2 manifest: `070dcb520b7c7d8620d312bf76488818039b4be9e0fa25cf86841fb63fb94c3d`.
- Credential first freeze: `ca127f8ce221636dc550f370f9e6295fd2f565ae388e5a47e3271c81c7098dca`, 7 production + 4 test files.
- Credential delta01: test-only isolated password provenance; patch `1c65e7744c330e11dafb447fb04a8995f5e47a94de5c746c2e3e2a083636c8bf`.
- Credential delta02: atomic successor/alias permission owner rechecks, plus two tests; patch `852f16d6ffda929ff1e02a81259b07a535b5cdc509967ebe251b86333b90f319`.
- Credential delta03: revision-scoped public opaque shape fixture; patch `c7bfc46bbb617498f8814b9269bc932fe4029b4659d5118260effbef4addcb5f`.
- Combined second production/test source, 31 targets: `1e44aaa194326e8e546c25d243299783a22fabfd90694df2072d64b07f2f3605`.
- Final 32-target manifest adds only independently reviewed status documentation: `57e9c5fc0c1d6ccdcbc7e7ccce873e9386438b6d83c4846ea696fa97275e016c`. All 32 target hashes and sizes independently match.

Individual verification records are `BIO_CREDENTIAL_FIRST_FREEZE_VERIFICATION.json`, `BIO_CREDENTIAL_DELTA01_VERIFICATION.json`, `BIO_CREDENTIAL_DELTA02_VERIFICATION.json`, `BIO_CREDENTIAL_DELTA03_VERIFICATION.json`, `BIO_SECOND_SOURCE_VERIFICATION.json`, `BIO_SECOND_EXECUTION_VERIFICATION.json`, and `BIO_STATUS_DOC_VERIFICATION.json`.

## Owner and durable-state conclusions

1. Existing authentication owner/registry remains authoritative. Opaque operation, root, mutation and alias permission reject foreign ownership. Neither a DTO, operation ID, UI predicate nor shape validator becomes a proof issuer.
2. Snapshot v4 adds finite biometric state and journal-presence framing; old v1/v2/v3 canonical representation remains selected for legacy states. Catalog v4 authenticates biometric state and exact slot generation/envelope while retaining media framing/entries. Password/recovery/biometric coexist within three slots. Credential operations preserve unrelated slots, recovery state, marker, ledger and journal.
3. STAGE_BIOMETRIC assigns the next catalog revision as generation and yields PENDING; actual decrypt of the exact committed staged envelope is required before ACTIVATE_BIOMETRIC. Activation/revocation retain exact slot bytes. PENDING/REVOKED cannot produce an ordinary unlock Root.
4. ACTIVE unlock creates the original catalog-bound Root capability with passwordAuthenticated=false. Its actual ledger/catalog-valid negative test rejects fresh-password authority, while a same-snapshot password fixture Root can revoke successfully. A biometric Root does not become fresh-password proof.
5. Fresh-password admission requires original owned Root, exact attempt, password provenance, shared cancellation lineage, healthy authenticated ledger/marker and RESET state, plus catalog verification. The completed 15s KDF child window is not incorrectly reused as the whole enrollment deadline.
6. Original workflow deadline is bounded once at 395s; each prompt gets a non-extending 120s child from the original workflow. SQL mutation use stays inside its original prompt deadline and existing 10s database bound. Native unlock retains existing reducer RESET semantics, with independent accepted RESET write budget and exact attempt identity; no new timeout renewal scheme was added.
7. Alias retirement uses ORPHAN_NONE with null generation or REVOKED_SLOT with its actual generation, original fresh-password Root, complete authenticated snapshot, exact persistent alias and original context. Consumption is one-shot and same-owner. Unknown outcome retains the exact intent and blocks another biometric workflow in the retained process owner.
8. G1 catalog-keyset resealing remains accepted under new-revision AAD; unrelated CAS and marker/ledger/journal checks remain. G2 accurate diagnostics do not repair lost device keys, corrupted ledger, foreign installation or unreadable store.

## Concrete findings closed before final source

- **Registry/runtime lock inversion:** binding predicates formerly ran under registryLock, opposite runtime-gate-to-registry drain queries. External current predicates now run outside registry; lock-held checks are owner-local. Platform reviewer separately owns prompt/Main thread and focus verification.
- **Successor capability after revoke:** ledger provider work could interleave old enrollment close before activation successor creation. Delta02 atomically rechecks original membership/revoked/one-shot claim/exact stage and registers the successor under the registry lock. The real production method is exercised with a decrypt hook that closes the old enrollment during ledger verification.
- **Alias permission close before consume:** final consumption now rechecks membership and consumed status under the same registry lock. The closed-permit test exercises post-close rejection; the exact mid-check race closure is established by the local atomic code, not overstated as a threaded test.
- **Source-provenance test ambiguity:** delta01 seals healthy RESET ledger bytes and proves ledger/catalog acceptance before the biometric-Root rejection, with same-snapshot password-Root positive control.
- **Shape fixture repeated ciphertext:** first-run failure was correct rejection of a reused opaque catalog blob at the next stage. Delta03 changes only the fixture bytes by revision and keeps the production inequality invariant.

## Independently inspected execution evidence

First central run failed two tests, exit1, after actual debug/release compilation. Four module test tasks ran: core-model:test, core-domain:test, library:test, app:testDebugUnitTest. Frozen XML totals: 505 suites / 5164 cases / 2 failures / 0 errors / 0 skips. OwnerConnection 7/7 passed. The schema fixture failure above and an unrelated legacy Main source-string assertion were corrected in separately frozen deltas. An initial reviewer grep incorrectly omitted JVM `:test` tasks; full-log lines and per-module XML timestamps corrected that scope in the saved record.

Second central run: exit0 / BUILD SUCCESSFUL in 3m57s; debug/release Kotlin actually compiled. Frozen XML: 505 suites / 5167 cases / 0 failures / 0 errors / 0 skips. App's 4737 cases actually reran; other three modules' 430 cases were UP-TO-DATE from the first run. All seven new biometric suites total 84 cases, including 9 actual owner connection and 10 credential schema/contract tests. Exact case names, timestamps, task lines and XML hashes are saved in `BIO_SECOND_EXECUTION_VERIFICATION.json` and `BIO_SECOND_XML_HASHES.json`.

### Final full execution

Independently recomputed `full-final` evidence: exit0 / BUILD SUCCESSFUL in 8m36s. All 13 module test tasks actually executed. Frozen XML contains 602 suites / 6675 cases = 6673 passed + 2 existing skipped, with 0 failures and 0 errors. The two skips are `FrozenRecoveryComponentTest.authored80MutationsActualJsoupComponent` and `FrozenCorpusHostTest.same100FrozenBodiesActualStaticB`; they remain the pre-existing optional owned-corpus fixture gate, not new biometric skips. New biometric suites still comprise 84 actually executed cases.

Both debug and release lint tasks actually executed. Recomputed SARIF effective levels (including each rule's default level) are 86 warnings + 10 notes and 0 errors per variant. Full-run debug/release Kotlin tasks are UP-TO-DATE, supported by the second run's actual double compilation of identical production source.

All 2801 files in `LOCAL_SOURCE_MANIFEST.json` were independently read and SHA-256/size verified, with 0 mismatches. Its manifest hash is `83ce6116b0ec42164d01b624d11e19311af6e0ea8fe427c31c9b4b0f57cff30b`. Relative to the 2792-file G1/G2 baseline, the exact source delta is 23 modified + 9 new, no deleted files, matching all 32 final targets. The 9 new files are 2 production files and 7 test classes. `AndroidVaultRuntimeEntry.kt:23` remains `ENABLED: Boolean = false`.

Final machine evidence is `BIO_FINAL_FULL_EXECUTION_VERIFICATION.json`; per-XML hashes are `BIO_FINAL_FULL_XML_HASHES.json`. These retain source identities, module counts, exact test task/compile/lint lines, skipped names and lint artifact hashes.

## Evidence limits and remaining acceptance

The 9 owner connection tests invoke actual new production prepare/start/stage/activation/unlock/revoke/retirement methods, actual owner registry, fixed public Tink wrap/reseal/decrypt, and actual shape/codec readback. They seed the already-password-authenticated Root as an explicitly labeled reflection fixture. They do not execute the prerequisite password KDF/RESET workflow, real KeyStore key creation/deletion, Android prompt, SQLite, or user credentials. No biometric operation, stageProposal or mutation registry is fabricated in that chain. Runtime tests use fake authentication/store boundaries; host tests are source contracts. These layers are complementary, not one end-to-end real-device workflow.

The production cancellation path cancels the original unknown-alias workflow context and retains the permission. No reachable UI reconciliation/retry with that original budget is implemented; report this as retained-process blocking, not successful repair. Entry is a retained singleton, so ordinary host replacement does not clear that state. Healthy password/recovery paths stay independent where existing state/release gates permit.

The final platform acceptance gates remain the real two-prompt enrollment and committed readback, strong authentication type and CryptoObject binding, negative/cancel/focus/Activity changes, enrollment invalidation, exact-alias retirement/readback, transactional reopen and protected pixels. No real device was operated by this reviewer; review actions were read-only source/hash/log/XML inspection and writing these review artifacts.
