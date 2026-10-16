# Vault batch export: independent crypto/runtime contract review

Date: 2026-10-16 UTC. Scope: final R4 design only, followed by source review when frozen implementation is available.

## Decision

**R4 is suitable for the bounded implementation described by this contract. No outstanding crypto/runtime design blocker.** This does not enable production or establish Android, SAF, real Streaming-AEAD, or integration acceptance.

The reviewer read the contract/API delta and independently checked all 7 design-manifest entries and all 31 baseline source entries for SHA-256 and byte count: zero mismatches. The extracted baseline has no locally verified Git HEAD; the declared baseline commit is `92627321c1c41af54504d83eedbb4e850407926e`.

## Exact reviewed identity

| Artifact under `work/vault-batch-export-design` | SHA-256 |
| --- | --- |
| DESIGN_MANIFEST.json | 1bc9b3afe08471868d90317fecba6f736ec80a85cf9488bc71f59cdc8c57d822 |
| CONTRACT.md | ed75abb162a03d9931b13348e084ba9edea9750f34d3a7ef94652931f03233a6 |
| API_FREEZE.md | 8afed55c1017e2bc2d9345850a7c3c6fd03f9fdb94df792fce7102015e6d0574 |
| API_TOUCH_MAP.md | c180694904ec2e84ca4613a43182ef3220f4d0fe89b4a14e79011df6d99c7035 |
| TEST_MATRIX.md | 3d0a433f02f78d10f1d7e6689272c217ab4be3ab17ae7ea182278a897fb6a596 |
| WIRE_BUDGET.md | 3a27b62629543b6123000e7f44883384ae054ca2a4a45a61b41386dd4749394d |
| SOURCES.md | 28d3564206dbc16628770c4dcaa53fbf1d188e2e3d74e6bc4ec4921dcf42713a |
| BASELINE_SHA256.json | 35cf443c9392c0d4f720013aaac40f76f9feaa27835898a59f2f3fccd148e45c |

## Accepted implementation boundary

- Reuse the same runtime/auth/store/files owners and existing scalar COPY_OUT transfer. One active item, one source reader/sink, the existing authenticated full-source discard pass followed by same-anchor copy and external readback. Vault originals remain unchanged.
- Freeze 1–8 original object/generation identities with unique planned operation IDs. Current catalog authentication may be refreshed after credentials change, but the original selection and successful/retained rows cannot be silently substituted.
- Each explicitly admitted run has one 30-minute monotonic deadline for all items; at most eight durably charged runs. Handover cannot refresh the context/deadline or inherit the prior item's final-decision acceptance.
- Bounded journal v2 uses the existing encrypted journal field. Legacy v1 stays byte-compatible and rejects appended phases. v2 also permits the explicitly bounded single-item OUTPUT_RETAINED form with no batch.
- Same-owner whole-snapshot/root/context proofs remain required. Plan digest and outcome DTOs are validation inputs, never authority. Old proofs cannot be rebased after credential reseal or an uncertain CAS.
- The proposed worst payload is 13,397 bytes against the existing 14,336-byte cap; the encrypted envelope remains capped at 16,384 bytes. This arithmetic is a design basis only. Actual canonical encoder and envelope fixtures must establish capacity before integration, including overflow rejection before allocation/IO.

## Review findings closed in R4

1. **Credential recovery deadlock:** inactive durable batch state does not block locked, drained password/recovery authentication and reset. Only live work, unresolved mutation and actual release obligations remain busy. Credential reseal preserves the complete encrypted batch journal; continuation authenticates the current catalog under a fresh context/root and revalidates unchanged original identities.
2. **Caller-certified target retirement:** the fixed store validates the actual durable exact operation/row/target/outcome with no pending or unknown mutation. The adapter separately requires actual handle drain before permanently revoking write/delete/publish authority. A caller outcome enum cannot certify retirement.
3. **Per-item final-decision state:** after a durable terminal result, true drain and required target retirement, same-gate handover resets decision state for the next operation while retaining the run context and budget. Parent stop remains separate from item failure/cancellation.
4. **Present-document incarnation ambiguity:** shared single/batch cleanup cannot delete a present document using URI, live token or old/fresh grant alone. Independently confirmed absence may be clean; otherwise the output remains/manual. No new native-identity framework is introduced.
5. **Single retained-output escape:** explicit OUTPUT_RETAINED is non-success and requires a known pre-final decision, actual drain and a fixed-store exact target fact. It cannot bypass PUBLISH_INTENT, an unknown final CAS or unknown close. Original files and previously won final decisions are preserved; write/delete/publish authority is permanently retired.

## Source and test review that remains

These are the frozen contract's acceptance conditions, not a new design scope:

- Exact v1/v2 encoding, finite explicit wire maps, strict shape/UTF-8/count/size checks and canonical plan/binding substitutions.
- Immutable plan/tree/prior results, monotonic active cursor and scalar sequence; no next-row authority before durable prior result and real drain.
- Item 1 success followed by item 2 cancellation; no inherited final-decision acceptance, later child creation or rollback of item 1.
- Stale callback, cancelled queued worker, lock and unknown create/open/close/CAS fail closed without manufacturing resource release.
- Fixed-owner target retirement cannot be forged, and PUBLISH_INTENT/unknown outcomes cannot become retained or skipped.
- Credential reseal preserves batch ciphertext and pending forgotten-password recovery remains available after actual drain.
- Exactly one current picker/action witness, explicit ordinary-copy confirmation and fresh same-tree authorization on continuation; stored tree IDs are not capabilities.

Storage/provider/direct-child/delete semantics are also subject to the separate storage review. Real Streaming-AEAD positive/tamper vectors and Android/SAF lifecycle/provider/device acceptance remain separately **NOT_RUN** in this design review. No new library, actual key, credential, platform operation, build or test was executed here. Existing production-disabled state and historical dependency-only gate remain unchanged.
