# Discovery thumbnail capability scheduling foundation

## Status and original requirements

This is an unconnected, pure-JVM foundation. DiscoveryScreen still renders its existing theme
placeholder. It is not a completed thumbnail feature or approval to read arbitrary image URLs.
No remote access, Android decoder/rendering, APK assembly, installation or device test was run.
The supplied reconstructed source directory has no local Git history; no Git repository was
created and this checkpoint itself does not publish or claim a full clone.

Original requirements remain unchanged:

- `docs/requirement and design/01_Product_Requirements.md` §5.2, lines 136–137:
  thumbnail/title/source/available duration, with honest missing-metadata placeholders.
- `docs/requirement and design/04_Technical_Requirements.md` §3.1, lines 56/59:
  independent thumbnail budgets, visible-region loading, cancellation with foreground work.
- `docs/ui/implementation/ESV_UI_FEATURE_INTEGRATION_LEDGER.md`, lines 105/294:
  an admitted reader is required before UI binding; owned local fixture contracts can be developed,
  but URL metadata and reference-slide photographs do not grant runtime-image rights.

Only two new production files and their JVM test file are added under Discovery. No existing
Library, app, Browser/history, Provider, build, dependency, resource or UI files are modified.
The separate browsing-history foundation is untouched and remains unwired.

## Reuse decision and ownership

The existing `library/.../thumbnail/BoundedThumbnailSession.kt` was inspected, including its JVM
suite. It owns a bounded page LRU and takes Library `MediaItem`/`MediaReadRef` authorities.
Discovery currently depends only on core-model. Pretending a webpage URL is a MediaReadRef,
adding a Discovery→Library dependency, extracting a cross-module generic cache, or copying that
LRU would enlarge this change beyond its authorized scope. None was done.

This scheduler has **no image cache**. It keeps at most one in-flight read and borrows a validated
frame to `onFrame` synchronously. It closes that frame when the callback returns, including when
the callback throws or changes the current page. Consumers must not retain the frame. A future
Android integration needs an admitted bounded image owner/renderer; this API does not by itself
provide a persistent drawable. `DELIVERED` means the borrowed callback ran, not that pixels were
rendered or accepted on a device.

The only public reader factory accepts caller-owned local RGBA fixture pixels and copies them.
Its output is a read-only pixel view; closing the frame zeroes/releases its backing pixels. The
caller owns capability lifetime. This factory has no path, URI, HTTP, content-provider or codec
access. An internal reader contract supports controlled JVM failure/resource fixtures; a future
Android reader factory requires separate admission. These types constrain trusted application
integration, not hostile code already executing inside the application process.

## Authority and lifecycle contract

- A capability binds to SessionStamp, page revision, result identity and the exact bounded result
  observation, including a detached encoded rights observation. Null and empty differ; malformed
  Unicode is rejected before hashing. Later mutation of input collections cannot rewrite a grant.
- Neither `thumbnailUrl`, attribution completeness nor ResultRights grants read permission.
  A URI change, rights change, session/epoch change, revision change or result change invalidates
  older capabilities and prevents older completion from publishing.
- The trusted UI owner supplies the authoritative current page. It must report visible result IDs
  separately. New-page/background transitions clear the viewport. Missing requests have explicit
  `NOT_REQUESTED` state; URL metadata alone never creates loading work.
- Leaving the viewport drops queued work and marks active work cancelled. Returning may queue one
  new read but cannot replace the still-active owner before real completion. Background/exit also
  cancels queued work immediately and rejects old results; no stopping-time measurement is claimed.
- There is one dedicated work lane, separate from discovery/provider and ordinary Library lanes.
  Deduplication prevents recomposition retry loops; full/rejected/unavailable states stay terminal
  until visibility or page changes. There is no automatic retry or external I/O.
- A timeout keeps active ownership, clears the queue, cancels the reader and permanently fences
  this session. Unconfirmed reader release, throwing frame close, and uncertain submit also fence.
  None licenses a replacement native worker. A stalled notification is not proof of termination.
- Result transfer has one atomic claimant. A failed UI post before transfer closes the frame on
  the worker and retains active/watchdog fencing. UI ownership already transferred is not cancelled
  by a later post exception. Duplicate worker or UI delivery cannot republish or double-own output.
- The future Android adapter must provide reliable owner-thread post/timer delivery (including
  after close), an idempotent timer removal, non-silent rejection, and process-wide reader admission
  fencing shared across sessions. The current per-session fence is not a native/process security gate.
  Closing drops the consumer callback reference while preserving the active owner's watchdog.

## Budgets

`DiscoveryThumbnailBudget` has no defaults and is not wired into product settings. Its defensive
ceilings are no larger than existing Library thumbnail ceilings: dimensions at most 256×256,
frame allocation at most 262,144 bytes, at most 16 visible results, at most 16 queued reads and
watchdog at most 10 seconds. These are constructor ceilings, not new approved product defaults or
performance claims. Page input is limited to the existing Discovery maximum of 100 results;
metadata uses existing normalizer/pool bounds before identity hashing. Actual test budgets use
1×1 pixels, 4-byte frames and explicit tiny queues. No byte cache budget is needed because no
frame survives its delivery callback.

## Verification and release boundary

See `evidence/discovery-thumbnail-foundation-20261012/verification.json` for exact commands,
executed versus UP-TO-DATE task outcomes, test counts, source hashes, lint result and exclusions.
A supplementary inherited boundary check initially failed on the annotations 23.0.0 POM:
30 LF versus the approved CRLF line endings. A separate release-owner operation restored the
exact approved 1,354 bytes without changing the manifest or dependency locks; all 99 payloads
then matched and all seven boundary checks passed. Both failure and successful recheck are
retained. No POM, backup, vendor, manifest or dependency change is in this checkpoint's whitelist.
Lint reports contain 0 errors, 60 warnings and 8 hints per variant, matching the release owner's
previous 60-warning/8-hint counts; this is not a claim of pixel or device acceptance.

The accompanying sanitized task logs omit machine paths and contain no result URLs, titles,
search terms, cookies, credentials or customer data. All test media is locally generated synthetic
pixel data; reserved `.invalid` values are functional test strings and are never fetched.

Independent review corrected malformed-Unicode identity collisions and both result-transfer race
orders, checked resource/timeout fences, and reviewed the final source and test hashes. The
concurrency regression uses latches rather than timing sleeps. The review itself was static;
Gradle execution is recorded separately and does not imply Android runtime acceptance.

NOT_RUN: remote thumbnail fetching and source licensing, Android image decoding/native release,
Compose image rendering, viewport integration, lifecycle/device timing, APK/package assembly,
emulator or phone tests. Future integration must revalidate these and preserve honest placeholders
until an admitted source/reader and bounded persistent image owner exist.
