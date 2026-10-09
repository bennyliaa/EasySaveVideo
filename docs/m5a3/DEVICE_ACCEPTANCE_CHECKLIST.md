# Android/device acceptance: NOT_RUN

Compilation, JVM fixtures and host SQLite tests do not execute these platform contracts. Every checkbox below is pending. No APK/AAB assembly, installation, real-media operation or real permission grant is authorized by this development checkpoint.

## MediaStore and document providers

- [ ] API31/32, API33/34, API35/36; representative phone/tablet, OEM and low-RAM devices
- [ ] App-owned insert needs no broad storage permission; pending rows stay hidden from other apps until verified publication
- [ ] Pending-inclusive exact nonce/path/package recovery after insert-to-journal process death; null/changed owner and duplicate rows fail closed
- [ ] Full count/digest/EOF readback, large 2 GiB source limit, full storage, zero/short/error read/write, flush/close failure and publication failure
- [ ] Cancellation during copy, close and readback; cancel after durable publication decision reports too late rather than promising retraction
- [ ] CREATE_DOCUMENT provider cancellation before/after creating an empty document; lost Activity result, rotation, process death and source rename/move/delete while picker is open
- [ ] Local Downloads/removable/cloud providers, write-only grants, revoked permission, unknown length, unsupported readback, nontruncating modes, unsupported delete and remote partial-output persistence
- [ ] Unverified copy is never reported as verified success; explicit cleanup targets the exact selected operation and preserves original media
- [ ] Retry/recovery never creates a second uncertain output or resumes public writes to an unconfirmed SAF capability

## Read-only temporary sharing

- [ ] System chooser preview, metadata probes, sequential and repeated opens, recipient copy/upload, seeking near head/middle/tail and EOF
- [ ] Test several independently implemented recipients; supported source-level proxy descriptor APIs are not a blanket compatibility guarantee
- [ ] Proxy read callback fills the requested amount unless true EOF, exact known length, read-only mode, no write path, bounded repeated reads and limits
- [ ] URI grant applies only to the issued random capability; malformed paths, traversal, query/fragment, stale/expired tokens and other ordinary/Vault items fail
- [ ] Recipient duplicates/detaches descriptors, closes them in varying order, crashes, keeps them open across expiry, and retains buffered/already-copied bytes
- [ ] Expiry/revocation rejects future authorization; existing kernel or recipient buffers cannot be recalled. Cleanup waits for actual release of the app-owned source descriptor
- [ ] Process death invalidates issuance rather than resurrecting a token; startup cleanup never deletes originals or unknown files
- [ ] Failed proxy opening, blocked callback/release, descriptor-close uncertainty, bounded callback/maintenance threads and bounded platform-retained callback objects
- [ ] Per-copy/total staging limits, per-copy opens, live readers, per-read/total-read limits and expiry match UI; larger originals can use explicit MediaStore/SAF export

## Native thumbnails and UI

- [ ] Scaled native frame respects requested size on real codecs; corrupt/large video, allocation pressure, native hang and release failure remain safely fenced
- [ ] Cache eviction after hardware Canvas submission does not recycle a bitmap still used by renderer; memory claims exclude platform/GC/GPU retention
- [ ] Rapid scrolling, page/sort/search changes, rename/move/delete, new publication, background, rotation and rapid route replacement show no stale frame
- [ ] Thumbnail-to-preview navigation waits for actual native release; recording admission cannot race thumbnail/preview ownership
- [ ] Read/native leases prevent unlink until actual release, including duplicated native descriptors and stalled release
- [ ] SkinKit light/dark, phone/tablet, RTL, large font, accessibility labels, long names, keyboard navigation, repeated clicks, dialog cancel, Back and system picker return
- [ ] Save/Record consent and foreground-service flows, historical receipt authority, provider-free startup and existing Direct/HLS/Record behavior remain intact

Vault encryption, auth, private sharing and private thumbnails are not implemented by this slice. No private-media protection or full M5 completion is claimed.
