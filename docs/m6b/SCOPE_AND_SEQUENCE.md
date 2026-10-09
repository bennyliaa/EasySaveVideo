# M6-B: region capture and owned recording segments

## Frozen implementation boundary (2026-10-06)

Baseline: `9b25083134221bb4a86d71e76601cc65b7d10937`, M6-A. The latest milestone update places advanced video tools in M6. PR-14/15 and TR-09 supply behavior. API31 minimum, phone/tablet and existing SkinKit remain. The user authorized M6 while Vault remains disabled; no Vault implementation or blocked review is part of this task.

1. Freeze pure geometry, capture scope and ordered effective-timeline contracts.
2. Add exact owned private segments, durable lifecycle and closed-artifact verification.
3. Add original pre-encoder crop with one projection/one virtual display, and segment rotation on pause/resume.
4. Bridge typed private segment capabilities into M6-A ordered editing and independent output verification; atomically publish one new media identity.
5. Wire honest UI, positive/negative host tests, independent ordinary-media source review, strict offline dual compile/lint, exact74 dependency admission and cold restoration.

## Geometry and capture-data route

- Coordinates are normalized `[0,1]` relative to the whole captured display-oriented image, including system bars when present. Insets are recorded geometry metadata, not a second coordinate origin. Source width/height, rotation, insets and transform version are frozen with the selection. No source screenshot or live preview is fabricated. The explicitly limited selector is a ratio schematic: the user agrees to apply these coordinates to the complete image they subsequently authorize in the system, not to a previous application, webpage or screen content. Matching geometry never authenticates that content identity.
- The region is rounded inward to even absolute pixel edges. Output is physically display-oriented, rotation zero. There is no implicit stretching or size downgrade: unsupported exact crop/codec dimensions are rejected. Initial advanced output must fit M6-A's 1920-edge/1920x1080-pixel limits.
- The system receives an explicit default-display capture request on API34+; API31–33 use the platform default display. System consent remains per session and single-use. A default-display request is not independent proof of application/content identity: OEM compatibility behavior can override the opt-out, and the app does not infer scope from private consent extras. The user must read the system scope choice. API34+ actual captured-content dimensions must match the frozen geometry before any selected frame is sent to the encoder. Mismatches fail closed. No single-app region support is promised.
- One full-size system projection surface feeds a SurfaceTexture. Original EGL/GLES draws only the selected pixel rectangle to the exact encoder surface. Full captured content can exist transiently in platform/GPU memory; the application does not write a full-screen temporary recording. Changing virtual-display or encoder dimensions alone is not cropping evidence.
- Geometry is rechecked at capture; changed dimensions, rotation or insets trigger safe stop, with no automatic adaptation/reuse of a stale rectangle. A new geometry/scope requires a newly selected recipe and new consent.
- Audio is optional system-allowed playback capture. Audio is not geometrically limited by the video rectangle and can include other permitted applications. No microphone or silent fallback follows permission/capture failure.

## Segment, timing and publication behavior

Advanced recording uses ordered app-private segments with random owned identities. Pause closes the current segment; resume starts a new encoder/muxer only after actual previous closure while retaining the same projection/virtual display. Initial plus 32 actual resumed segments is the maximum, preserving M4's actual-resume limit. Button taps and empty attempts do not fabricate recorded media.

The advanced session has a 30-minute effective output limit, a separate two-hour wall resource limit and 2-GiB total input budget to match the admitted editor. M4's existing raw single-session limits remain unchanged. A configured frame rate is only a request. Actual observed timestamps and native closure are checked; raw VFR or incompatible cadence can remain recoverable but cannot enter the strict M6-A CFR pipeline. No unverifiable normalization or weakened output policy is permitted.

Closed raw artifacts remain private and are independently verified before issuing editor input capabilities. They have no network URLs, Save receipts, temporary catalog rows, thumbnails, preview or share authority. The recorded-input type is separate from ordinary catalog MediaReadRef. Ordinary editor input count remains eight; only an ordered same-session recorded-input recipe admits up to 33 segments. Post-processing reuses the actual editor and full output verifier, not a successful capture callback.

Stop-and-save requests finalization and processing. Cancel/discard revokes publication and cleans only exact owned artifacts after native closure. Native uncertainty retains fences, files and recovery state. Post-processing failure preserves raw segments. Successful publication creates one new catalog item; original/temporary cleanup remains explicit and recoverable. File deletion is not represented as secure physical erasure. Process death cannot restore projection authority or automatically restart capture.

## Evidence limits

Only code, host tests, compile/lint and source review are authorized. MediaProjection consent, real recording, physical crop pixel accuracy, GPU/codec behavior, audio synchronization and API31–36 phone/tablet/OEM tests are NOT_RUN. No APK/AAB, device operation, new dependency, account, payment or publishing is authorized.

Official API references checked 2026-10-06 (design contracts only, no copied implementation):
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/reference/android/media/projection/MediaProjectionConfig
- https://developer.android.com/reference/android/graphics/SurfaceTexture

## Entitlement compatibility

Start uses the existing REGION_RECORDING feature gate (RECORD_SAVE + REGION_RECORDING). Pause/resume uses the existing SEGMENT_RECORDING gate (RECORD_SAVE + SEGMENT_RECORDING). Automatically assembling already admitted recording segments is part of safe stop/save; entitlement loss does not prevent safe stop, private-fragment cleanup, or retrying finalization of already owned segments. Retained retry never regains projection authority. Arbitrary later crop/merge/compression remains in the ordinary editor with its existing independent gates. No new SKU, release grant or price is introduced.

## Explicit later capability

Actual-content visual region selection using a bounded in-memory preview is not implemented in this checkpoint. The current capability is a proportion diagram with drag/edge/corner controls, explicitly applied to the image later authorized through the system. Device pixel correctness is still NOT_RUN.

## Final-publication cancellation boundary

Both ordinary editing and recorded assembly share one short in-memory cancellation/publication admission point. It occurs only after output verification, long file I/O, promotion and exact source checks, immediately before the final catalog-visibility transaction. Cancellation or an expired deadline before admission excludes publication and permits exact owned-candidate cleanup. Admission is one-way: cancellation afterward returns TOO_LATE and the UI says final submission is underway and its outcome must be checked. It does not claim cancellation, discard, non-publication or success until the catalog result is known. The token monitor is never held during SQLite, file or native calls, so Cancel does not block the UI behind a stuck commit. Uncertain admitted outcomes reconcile through the existing exact journal/catalog identity and cannot reuse the same token or silently republish.
