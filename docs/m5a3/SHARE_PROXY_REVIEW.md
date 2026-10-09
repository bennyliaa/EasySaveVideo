# Share proxy design review

Date: 2026-10-06 UTC. Source/API research only, not device acceptance.

## Recommendation

For ordinary Android chooser/receiver use, bounded repeated read opens over a seekable read-only proxy are preferable to a one-use sequential pipe. Probes, previews, reopening and seeking can otherwise consume or exceed the single capability. This is a compatibility design inference, not a claim that any particular receiver has been tested.

Keep the already verified, generated-name private snapshot as the only data source. Return StorageManager.openProxyFileDescriptor(MODE_READ_ONLY, callback, dedicatedHandler), never its underlying snapshot descriptor. Keep independent limits on total copies/bytes, total opens per capability, active proxy owners, callback request bytes, aggregate delivered bytes and process-lifetime proxy issuances.

## Required details

- Use one dedicated callback handler for the process, separate from main, ordinary-library commands and native thumbnail/preview ownership. Do not create a replacement handler to evade a stalled owner.
- onRead must supply the full requested size unless actual EOF. A 64 KiB internal chunk is fine, but a 128 KiB request requires two chunks when sufficient bytes remain. Validate nonnegative offsets, size and buffer capacity; reject overflow, out-of-range reads and zero progress. Bound requests to a documented supported maximum.
- Source reads are positional and limited by the known verified snapshot length. Report read corruption or IO failure rather than returning a fabricated successful short read.
- onGetSize returns known verified size; reads/metadata check expiration and revocation. Deny all writes. URI grants remain read-only, exact and temporary.
- Exhausting the open-count budget denies new opens. It must not automatically revoke an already admitted reader.
- Expiry/revoke does not prove any descriptor closed. Retain callback-owned snapshot input and staging ownership until onRelease plus actual input-close proof. A failed/hung release keeps the capacity/cleanup fence.
- onRelease must clear owned references after successful close. Android12 framework source keeps callback table entries after normal release, so add a process-wide cumulative issuance cap rather than assuming live counts alone bound all retained framework objects.
- Process-death cleanup relies only on termination of the application's own callback/source resources; the recipient has a virtual proxy, not the private snapshot FD. Do not promise a recipient's descriptor itself was closed, cached/read bytes invalidated, or retained copies erased.
- Some receivers may still fail under finite read/open budgets. Expose an honest export-file fallback. Test chooser preview, real target reopen/seek, detached/duplicated handles, slow readers, callback close failure, TTL, revoke, process death and stale copies on devices before compatibility acceptance.

## Primary sources

- StorageManager API documents seekable proxy descriptors and ContentProvider use: https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler)
- ProxyFileDescriptorCallback API requires exact-size reads except EOF and documents onRelease after file close: https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback
- Android12 FuseAppLoop source shows callback dispatch, 128 KiB buffers and release bookkeeping: https://android.googlesource.com/platform/frameworks/base/+/android12-release/core/java/com/android/internal/os/FuseAppLoop.java
- AOSP native read-buffer bound: https://android.googlesource.com/platform/system/core/+/9591090a1/libappfuse/include/libappfuse/FuseBuffer.h

## Verification boundary

No proxy file descriptor, ContentResolver, chooser, device, real media, renderer or native API was executed. Implementation must receive a fresh source review after landing; this note is not approval of uninspected code.
