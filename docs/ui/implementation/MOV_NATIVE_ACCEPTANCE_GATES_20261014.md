# Typed normalized verification acceptance gates

This is an execution checklist for the approved next layer, not a claim that any new test passed.

## Current source layer

- Legacy Transferred dispatch keeps the original TransferResult and behavior; exactly one native submission envelope.
- Missing normalized owner rejects before native allocation. Queued revocation never acquires a canonical descriptor.
- Owner acquire checks durable selectedOutputDescriptor, full proof/task/operation/adopted generation. The original output object is preserved, but reference identity alone is not authority.
- One immutable read-only RAF/FD, fixed captured extent, dev/inode identity and before/after full hash. Positional reads cannot follow a changing extent or reopen a path.
- Complete independent strict output scan and native sample/config/PTS/sync/count/EOF agreement.
- Original decoder checks plus exact final-GOP nonempty frame count/order and actual EOS. Empty EOS timestamps do not prove a terminal frame.
- Native resource cleanup is independent. A failed native release permanently retains the normalized mutation fence even if the RAF then closes. Ordinary timeout/draining may end after actual successful cleanup; do not poison all timeouts.
- No local normalized failure escapes as INCOMPLETE_TRANSFER. No HTTP retry, MIME rewriting, audio dropping or producer proof substituted for an independent observation.
- Fake owner/syscall/native tests remain explicitly fake observations, source contracts remain source contracts.

## Storage/runtime production gates still open

- Real adopted-generation owner implementation and authoritative record-selected descriptor handoff.
- Previously escaped File/FD call-order and native-release proof under the Direct local/execution gates.
- Temporary storage hash/fsync/open/close blocking: memory cancellation/release state responsive, no early claim/worker replacement, late actual retirement once; UI never waits synchronously.
- Full Coordinator phase, cancellation, proof CAS/adoption, recovery and final publication tests, retaining raw TransferResult and independent output evidence.

## Separately scheduled actual Android acceptance

Use only the authorized emulator-5556 with preserved data/signature and an increasing versionCode when the parent schedules device acceptance. This checklist does not initiate a device operation.

- Canonical fixtures through real remux then real Android extractor: exact framing, CSD, sample identity, PTS/sync/count and EOF.
- Two/three samples, terminal nonsync frame, all-IDR one-sample final GOP, admitted rate rounding phases, metadata/chunk/NAL variations.
- Actual decoder positive-size ByteBuffer output, crop/geometry/format, last frame, output EOS, MMR checks and unavailable-codec errors.
- Same-size mutation, length change, cancellation, native blockage and late actual release with publication fenced.
- Real Android SQLite, descriptor and fsync/adoption integration; ordinary MP4/HLS/Record and two-Direct sequencing regression.

No silent-only host pass establishes general MOV/WebM/AAC/transcoding support.
