# Batch-copy acceptance matrix

Revision 4. Required tests; none executed in this design task. JVM fake/source checks do not substitute for actual Android/provider acceptance.

## Pure journal / crypto

- v1 single-item encode/decode compatibility; v2 single OUTPUT_RETAINED round trip, non-success display, explicit pre-final-only transition, no retry, and next-operation admission; canonical v2 round trip; unknown version, count/byte overflow, truncated/trailing bytes, duplicate identities, mutable aliases, malformed UTF-8, invalid ordinal, integer overflow all fail closed before allocations/IO.
- Worst permitted 8-row terminal shape plus active duplication fits unchanged 14 KiB payload/16 KiB envelope; exact boundary and overflow rejection. Worst generated ID sizes and 1,024-byte URI boundaries.
- Binding/AAD substitution across vault/root/batch/plan/current item/catalog/operation/revision rejected; no second registry or caller-created capability accepted.
- Plan/tree immutable, monotonic active cursor, immutable successful/retained results, no double-active row, scalar operation sequence monotonic, handover only after durable terminal result; new unrelated operation blocked despite active scalar terminal.
- Pending batch plus forgotten password: after lock and actual drain, existing recovery reset works, preserves batch bytes, and only a fresh auth/current-catalog check of unchanged original identities permits continuation.
- Retirement forged enum/DTO, other store instance, wrong row/target, pending CAS or unclosed handle cannot retire authority or advance.
- Same snapshot/context proof; old/current credential snapshot forks and unknown CAS retain exact proposal, never rebase or new create; credentials retain batch ciphertext intact.

## Queue and exact single-engine behavior

- One and eight items: one picker, one run deadline, N exact fresh children, max one source reader/sink, each child full authenticated discard + same-anchor copy + external readback; all originals/catalog entries unchanged.
- Ordinary single-copy and move-in paths use unchanged byte engine and pass prior tests. No ordinary export journal/source/schema use.
- Corruption in first/middle/final media before external write; invalid plaintext size/SHA/EOF/final segment/zero progress; later rows only run after clean failure and actual drain.
- Item 1 succeeds, item 2 is copying, then Cancel: per-operation decision reset lets cancellation stop item 2 and prevent item 3 without deleting item 1.
- Item 1 succeeds, item 2 fails/cleans, item 3 succeeds: correct partial counts; item 1 never removed or copied again. Shared grant/profile failure pauses rather than spinning.
- Cancel before picker dispatch, during launched picker, before first create, while create blocked, after returned URI before bind, on each transfer/readback boundary, and racing PUBLISH_INTENT: no later new child, no late plaintext publication, successful child retained.
- Repeated clicks, wrong/stale picker request, changed selection, queued stale UI callback, root/presentation/catalog change, cover ACK incomplete: no IO authority; awaiting picker remains target-only.
- Slow/throw/unknown close or in-flight create/open/write blocks actual-drain completion and next item; timeout never releases owner. Explicit skip cannot bypass unknown IO or unresolved CAS.
- Run-count reservation and deadline overflow; 30-minute run deadline never resets between items; at most eight explicit starts; process restart cannot reuse old context/deadline.
- Cleanup-confirmed failed row eligible for explicit new batch only; VERIFIED_COPY and unresolved rows excluded; no same-operation truncate, append, fresh create or automatic retry.

## SAF adapter / source boundary

- ACTION_OPEN_DOCUMENT_TREE once; no takePersistableUriPermission/ordinary defaults/persisted folder grant. Exact result shape/flags/effective grants, wrong authority/package/class/signature/protection/system status, nonprimary/root/virtual/nondirectory/no-create all fail closed.
- Tree grant ancestry is not direct-parent proof: reject returned cousin/grandchild, different tree, directory child, non-MP4, prior known ID, duplicate ID and insufficient effective child grants.
- Complete bounded before/after immediate-child enumeration; null cursor, extra rows, partial/loading flag, duplicate IDs, >1,000 children, >1 MiB ID bytes, >1,024-byte target URI reject safely; no writes/deletes of an unvalidated existing file.
- create call null/throw/no-return/crash, returned URI capture, validation failure, bind CAS old/exact/unknown: precise no-output/known-target/unknown status; no nonce search or guessed deletion; bind precedes writer.
- Child permissions revoked between validate/open/readback/publish/cleanup; real close tracking remains exact; no fallback provider, cloud, MediaStore, other directory or source delete.
- For BOTH existing single and batch targets, a present same-URI replacement cannot be deleted by live registry token/old grant/fresh recovery selection alone; tree-grant child ID reuse also fails closed. Independently confirmed absence is clean; present target is retained/manual. Existing single has an explicit drained OUTPUT_RETAINED exit, never mislabeled cleaned/success.
- External rename/delete/replacement after selection or return: unsupported/uncertain rather than overwrite/recreate; post-restart stored URI/name/regrant cannot establish deletion authority.

## Durable interruption / recovery

For every boundary PREPARED, picker dispatch/return, tree bind, CHILD_CREATE_INTENT, returned child before bind, TARGET_BOUND, SOURCE_VALIDATED, COPYING, VERIFYING, PUBLISH_INTENT, COMPLETE, result CAS and next-item handover:
- Restart locked, no automatic source read/create/retry; authenticate whole original batch and CAS outcome before action.
- Completed rows survive and never recopy/delete. Untouched rows remain identifiable. New same-tree regrant does not authorize old child reconstruction.
- Unknown create or partial child remains unresolved/manual. Current-process ownership is retained, but present deletion still requires independent sufficient current identity; URI/token alone is rejected. Confirmed absence remains usable. Explicit keep/skip may isolate old target only with actual IO drain and known pre-final CAS; next item uses fresh operation/child identity.
- Durably observed PUBLISH_INTENT finishes completion bookkeeping without decrypting/recreating source/target; unknown final CAS blocks. External post-success removal does not trigger automatic recreation.
- Exact same-tree recovery registers a fresh process-local witness while original durable tree/grant record remains byte-identical; old IDs alone never issue IO.
- Reauthorize a different tree, a same-URI new grant generation, stale callback, removed old file, or a reused document ID cannot delete an unrelated child.
- Secret/sensitive-field redaction scans cover UI state, logs, exceptions, database columns, saved state, crash output and toString.

## Later Android/device-only acceptance (NOT_RUN)

API31–36 emulator/platform profile support, initial picker and recovery picker lifecycle with process death/recreation, actual temporary grant behavior, app background/lock/recent-tasks cover, real SAF flush/readback/full disk/provider failure, local provider identity and direct-child behavior, and resource closure. Synthetic fixtures only unless separately authorized. Existing real Streaming-AEAD fixed-vector gate remains separately reported; this contract does not turn it into PASS.
