# M6-A: bounded ordinary-library video editing

## Authority and separation

The 2026-10-06 user decision advances M6 while Vault remains disabled. The governing milestone order is `03_Development_Milestones_update1.md`; PR-14/15 and TR-09 retain the detailed behavior. API31 minimum supersedes the historical API30 text. No Vault implementation is copied, integrated, enabled or reviewed in this checkpoint. Its independent gate remains separate.

## Sequence

1. Platform-neutral ordered edit recipes, normalized display-to-source crop mathematics, input/output profile policies, lifecycle and bounds, with synthetic unit tests.
2. Real, pinned Media3 1.11.1 execution over catalog-issued current read capabilities. Trim, ordered merge, post-crop and optional space-saving compression preserve originals.
3. Independently inspect new owned MP4 output, then atomically publish a new catalog identity with typed edit provenance. No fake URL, Save request, HLS or recording evidence.
4. SkinKit editor controls, foreground-only admission, free cancellation/safe stop, original and successful-output preview. Existing FeatureGate/ProductMapping remains unchanged; only debug builds have fake paid grants.
5. Independent normal source review, strict offline debug/release compile/lint, JVM/SQLite/HLS regressions, exact 74-artifact runtime admission, source/bundle cold restoration.

A compilable WIP is an early backup checkpoint, not acceptance. Real hardware decoding/encoding, GPU rendering, audio/video synchronization, crop pixels and OEM behavior remain NOT_RUN until separately authorized device testing.

## M6-B remains a separate checkpoint

Pre-record region selection and recording-segment orchestration are not silently represented by the post-processing editor. A future full-screen-then-crop implementation must disclose its temporary full-screen capture/storage before recording, and may not publish the full-screen original as a successful region-only result when crop fails. Genuine live region capture requires a verified capture path. M6-A is not completion of all M6.

## Required invariants

- Immutable source identity/revision/representation generation; no arbitrary caller input paths or stale Save receipts.
- New outputs have owned random staging/final names, independent evidence, source provenance, and atomic catalog visibility.
- No cleanup or resource reuse while actual native closure is unconfirmed; cancellation/deadline is never proof of release.
- One bounded native editor owner; bounded source count, pixels, sample sizes, time, output bytes and queues.
- Explicit profile/trim/AAC/rotation/VFR policies and visible unsupported outcomes; no guaranteed compression ratio or original-quality losslessness claim.
- No APK/AAB, device operations, real capture/downloads, new dependencies, accounts, payment or publication.
