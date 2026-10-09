# Independent A3 exports/share and bounded thumbnails source review

Status: final source re-review complete for the accompanying 32-file production SHA-256 snapshot. No unresolved critical/high source finding remains.
Review date: 2026-10-06 UTC.
Repository: EasySaveVideo. Base: b90194da23c6a6419b4d04d0f16cd5a6a2637fc5, ordinary-library development checkpoint. Final reviewed production commit: ebbca1997d02e4b67d9d0cef838b288a317d46c4. All 32 production hashes were rechecked after that commit was confirmed.

## Recommendation

The reviewed source is suitable for an **ordinary M5-A3 development checkpoint**, conditional on the build owner's final strict offline aggregate passing against the exact accompanying production hashes. This is not full M5 completion, Android/native/device acceptance, general recipient compatibility, security certification, or release approval.

The review identified or tracked nine material lifecycle/privacy findings, all source-fixed and re-reviewed below. Additional final checks covered paged persistent SAF responsibilities, action-scoped original-preservation language, bounded display-only provider labels, and bidirectional thumbnail/preview release waits. No production change was made by this reviewer.

## Scope and method

Read-only review of production changes and test sources for ordinary MediaStore/SAF exports, bounded share copies and read-only capability provider, and bounded ordinary thumbnails. Parent owns integration and all Gradle tasks. This reviewer makes no production edits and runs no Gradle, APK/AAB build, device operation, real-media mutation/export, native decode, account, payment, publishing, or deployment.

Actual ContentResolver, MediaStore, SAF provider, URI-grant, descriptor-transfer, Android native decode, OEM behavior, process-kill and phone/tablet accessibility acceptance: NOT_RUN. Host/JVM tests are distinct from these device gates. Vault is excluded.

## Review criteria

- Journal before output creation; insertion-to-journal crash reconciliation; exact operation-owned cleanup, idempotence and fair recovery.
- Exact catalog item/revision/generation authority with bounded expected bytes/hash; full EOF/digest verification and resource-release fences; originals retained.
- MediaStore pending-only ownership, publish/cancel commit point, recovery distinction between pending and published output.
- SAF exact create-document capability, no persisted grant or arbitrary path; provider limitations, partial output and cleanup uncertainty reported truthfully.
- Share namespace limited to randomly named ordinary export copies, read-only expiring grants, no broad file root or original media exposure; TTL/revocation deny future opens without claiming recipient-descriptor closure or external-copy erasure.
- Thumbnail one-owner native lane, bounded requests/cache/pixels, exact session+revision result identity, cancellable stale publication and safe release uncertainty; catalog leases fence rename/move/delete.
- UI retains SkinKit and accessibility semantics; ordinary plaintext exports/copies never described as Vault/private encryption.

## Primary Android API references inspected

- MediaStore.MediaColumns IS_PENDING, OWNER_PACKAGE_NAME and RELATIVE_PATH: https://developer.android.com/reference/android/provider/MediaStore.MediaColumns
- ContentResolver openOutputStream and openInputStream: https://developer.android.com/reference/android/content/ContentResolver
- ParcelFileDescriptor.OnCloseListener: https://developer.android.com/reference/android/os/ParcelFileDescriptor.OnCloseListener
- MediaMetadataRetriever scaled frame and release methods: https://developer.android.com/reference/android/media/MediaMetadataRetriever
- ParcelFileDescriptor lifecycle: https://developer.android.com/reference/android/os/ParcelFileDescriptor

Relevant documented limitations: pending media is owner-only until publication/expiration; owner identity can be null; provider output mode behavior varies; close listeners report detach without proving underlying recipient descriptor closed. A software TTL cannot revoke a copy already read by another app.

## Findings and disposition

### R1 — Share output not independently verified (medium, source fixed)

Initial `LibraryShareOwner.prepare` computes size/hash over source bytes while writing, then flushes/syncs/closes and marks its renamed copy ready. No independent staged-output readback establishes destination EOF/count/hash. A sink corruption/trailing-byte defect could issue a capability to an unverified copy. Requested bounded full readback and reader-close proof before ready, with regression coverage. Sent to Android export owner and parent. Re-review confirms `ShareCopyVerifier.verify` independently reads the staged output to exact EOF/count/SHA-256 and the caller proves reader close before rename/ready; mismatch and EOF tests added.

### R2 — PREPARED recovery holds cancellation gate across copy (medium, source fixed)

Initial `ExportCoordinator.recover` invokes `run(record.request)` from its outer `synchronized(gate)` block when phase is PREPARED. Reentrant locking does not release the outer monitor, so the complete copy/readback executes while cancellation cannot enter the gate. Requested dispatch outside the outer synchronized block and a concurrent blocked-read recovery/cancel test proving cancellation can persist and prevent publish. Sent to contracts owner and parent. Re-review confirms PREPARED dispatch occurs after leaving the gate; `recoverPreparedDoesNotHoldCommitGateAcrossSourceOpening` exercises the blocked-source/cancel case. Provider publish IO now also occurs after the durable commit decision outside the gate, with a concurrent unrelated-cancel test.

### R3 — MediaStore crash-gap lookup excluded pending rows (high, source fixed)

The initial unbound nonce recovery query used the legacy five-argument collection query. Android 12 MediaProvider defaults collection queries to excluding pending rows, while an exact VIDEO_MEDIA_ID lookup explicitly includes them. A crash after insert but before URI persistence could therefore falsely report absence and lose the pending output. Re-review confirms a Bundle query explicitly sets `QUERY_ARG_MATCH_PENDING=MATCH_INCLUDE`, requests at most two results, binds nonce filename/path selection and checks exact package ownership/MIME/identity. Null or ambiguous owner remains unknown. Actual provider behavior is NOT_RUN.

Primary platform source: https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/android12-release/src/com/android/providers/media/MediaProvider.java

### R4 — SAF creation precedes source copy (high, source fixed)

A returned CREATE_DOCUMENT URI already denotes an external document. Initially the source could fail/cancel while the export record still had no owned output, leading to terminal failure/cancellation without cleaning the known document. Re-review confirms `bindSafOutput` transactionally binds the URI and adopts the output into the PREPARED record. Core failure/cancel paths therefore attempt only that owned document's cleanup. Regression tests cover both pre-source cancellation and stale/unavailable source. Runtime now durably sets CREATE_INTENT before launching the picker. A crash with no returned URI therefore enters reconciliation rather than source-opening/absence inference. Cancellation with no URI explicitly warns about possible provider-created empty documents and does not claim verified cleanup.

### R5 — Verified SAF decision could be mistaken for a pending output (medium, source fixed)

SAF has no MediaStore-style publication flag. Initial recovery always reported a SAF document as unpublished and could clean a document with a durable verified publish decision after journal completion failed. Core now recognizes exact owned, resources-released SAF output with the durable destination-verified decision as completion, while MediaStore still requires actual published state. The test `safVerifiedCommitRecoveryDoesNotDeleteBecauseProviderHasNoPendingFlag` covers this difference. This does not promise atomic remote-provider durability.

### R6 — Thumbnail Canvas release is not renderer completion (medium, source fixed; parent-raised)

Synchronized draw/close prevented new Java drawing through a cleared handle but did not prove an asynchronous RenderThread/GPU had finished. The final handle recycles only frames never submitted to Canvas; once submitted, close drops its owned reference and late callbacks draw nothing. The cache-byte limit explicitly excludes GC/native/GPU transient memory. No renderer/device behavior was executed.

### Design clarification — Close-safe thumbnail frame ownership

A frame wrapper with private Bitmap and synchronized draw/close prevents stale Compose callbacks from drawing through a closed handle. Platform renderer references may outlive one draw; cache byte limits describe app-owned retained frame allocation, not a demonstrated bound on total native/GPU memory. Requested explicit bounds terminology and current-generation re-request handling.

### R7 — Proxy open could deadlock its release callback (high, source fixed)

Initial seekable-proxy rewrite holds the share owner lock across StorageManager.openProxyFileDescriptor. That framework call can synchronously await the dedicated callback handler. If an earlier onRelease is on that handler waiting for the same owner lock, the new open and handler block each other. Re-review confirms production uses the pure ShareProxyAdmission policy to reserve under the owner lock and invoke framework open after releasing it. A concurrent test verifies an older callback can acquire the lock while the framework-open fake waits. Failed-open callback cleanup likewise runs outside the owner lock.

### R8 — Failed share-owner construction could multiply callback threads (medium, source fixed)

The initial proxy rewrite eagerly starts an instance HandlerThread before fallible directory and file-owner initialization. A failed singleton constructor is never published and the next attempt starts another live thread. Re-review confirms callback and maintenance lanes are lazily initialized process singleton objects. Failed owner-lock acquisition closes the file, with a strong failure fence when close fails. TTL filesystem cleanup occurs off main and removes the scheduled expiry callback once deletion is confirmed.

## Seekable sharing design change

The one-use pipe was replaced during review by a bounded repeated-open, read-only proxy over the verified private snapshot. The current rewrite has been re-reviewed after fixes. It has 8 opens per capability, 2 live proxy owners, 512 cumulative proxy issuances per process, 128 KiB maximum callback requests, 64 KiB underlying positional-read chunks, and an eight-times-snapshot byte budget plus 65,536 positive read calls per descriptor. It detaches callback payloads on release; failed source close retains the owner/fence. See SHARE_PROXY_REVIEW.md for primary API evidence and required lifecycle/read bounds. Neither proxy nor pipe has been exercised on Android.

### R9 — Provider exceptions could disclose private locators (low, source fixed; implementation-owner-raised)

The implementation owner identified that raw constructor/filesystem exceptions could cross ContentProvider read entrypoints. Re-review confirms query/openFile catch exceptions and expose only a constant technical FileNotFoundException without a nested cause; failed getType returns null. Query projection count is also bounded. Read-only mutation errors remain constant.

## Integration checks

Preview navigation now waits for actual ordinary native-owner release before creating its player; the sticky failure fence is respected. Thumbnail and preview admission share the process gate. Recording admission is main-thread serialized with thumbnail claim, and worker-side source acquisition checks active save/record admission again. SkinKit visual primitives and explicit accessible thumbnail descriptions remain in use. Export choice is exact-ref/foreground-bound; late chooser launches recheck the current route and Activity state. Unverified SAF cleanup has a separate exact-operation confirmation. The final 32-row paged list retains separate pending/unverified responsibilities across later exports and restarts. Cleanup binds the selected immutable operation/source snapshot; page, descriptor and foreground changes invalidate stale consent. Display-only provider labels are bounded to 80 UTF-16 code units without splitting surrogate pairs, strip control/format characters and never become destination authority. Export order is explanatory; the opaque operation ID remains authority.

Terminal history no longer consumes a lifetime export-count cap. Only 32 unfinished operations consume active capacity; retained idempotency and unverified records are not silently removed. In-memory live SAF grants are bounded to 32 and are never reconstructed from journal URI text. A lost grant leaves visible manual provider-check responsibility.

The UI states that export/cleanup does not delete the original, rather than falsely asserting the original must still exist after an independent user-confirmed deletion. Share chooser creation is not reported as delivery.

## Reviewed source manifest

- reviewed-production.sha256 / reviewed-production.json: all 32 changed production files in this slice, including Android manifest, UI/integration, storage authority changes, core policies, export adapters, provider and thumbnail paths.
- reviewed-tests-and-guards.sha256 / .json: 18 supporting test/guard files at the same snapshot. These hashes bind inspected supporting sources; they do not claim this reviewer executed the tests or independently reproduced the build owner's test count.
- reviewed-context.sha256 / .json: 8 unchanged authority/handoff/context files.
- snapshot.json: base, captured snapshot and frozen reviewed production commit. All production bytes match ebbca1997d02e4b67d9d0cef838b288a317d46c4; the final development tag and any later documentation-only commit are the build owner's responsibility.

Production manifest SHA-256: ea5cef5a80591f58958b6f9b5a132268e98af069412dfa40607c8ea39e4a408f.

A later production edit invalidates acceptance for that file until re-review and a new manifest. Documentation/commit-only changes do not alter the reviewed production bytes.

## Explicit remaining verification limits

- Gradle compilation, lint, unit tests and aggregate execution: **NOT_RUN by this reviewer**. Build-owner results must be bound to the same source snapshot.
- Android SQLiteOpenHelper transactions, CursorWindow behavior, real fsync/descriptor-close failures, MediaStore visibility/query semantics, SAF grant and remote-provider behavior: **NOT_RUN**.
- Actual system chooser/receiver probes, seeks, duplicate/detached proxy descriptors, FUSE lifecycle, process death, expiry/revoke and owner cleanup on OEM devices: **NOT_RUN**.
- MediaMetadataRetriever decode, hardware Canvas/RenderThread behavior, decoder/GPU memory, real preview/recording races, phone/tablet accessibility/layout and rotation: **NOT_RUN**.
- No APK/AAB assembly, install, real user media, real permission grant, account, payment, publication or deployment was performed.
- SAF remote durability/cleanup and recipient-retained data remain outside what this application can guarantee. Ordinary files and metadata are plaintext; this work does not implement Vault or secure erasure.
