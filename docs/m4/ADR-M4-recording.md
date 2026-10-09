# M4 Record development scope and architecture

Frozen input: `1caa0d8d858ffbdb9144ad42f3cc266242008ce6`, tag `m3b-hls-eof-dev-2026-10-06`.

## Authority and scope

`03_Development_Milestones_update1.md` supersedes the old milestone sequence: M4 is basic single-session screen recording; M5 is the complete Library/free Vault; M6 is advanced editing and segment orchestration; M7 Provider maintenance, M8 discovery quality, M9 reliability, M10 real IAP, M11 Android release, M12 iOS. Historical reference bytes remain unchanged. Android minimum is API31, phones and tablets; user application remains closed source.

This development slice adds original recording contracts, a typed Capture input, system consent, playback-only audio choice, foreground service ownership, one AVC/AAC MP4, basic single-session pause/resume, independently verified publication, and read-only preview of an already published receipt. No full Library, Vault, export, sharing, scheduling, overlay, microphone, automatic download-to-record fallback, DRM bypass, APK/device execution, or app-store publication is part of this checkpoint.

## Existing entitlement contract retained

Start requires RECORD_SAVE. Pause and resume retain the existing SEGMENT_RECORDING feature gate, requiring RECORD_SAVE plus SEGMENT_RECORDING. Stop, system safe-stop, cancellation, and viewing existing output are not gated. Development all-grants enables full local tests; Release remains UNAVAILABLE, without FakeEntitlementSource or production unlock. Whether basic pause should move to the base recording entitlement is an unresolved product decision, not silently changed here.

## Media and authority boundaries

CaptureDescriptor is durable intention, not authority. Its local session identifier and generation never stand for system authorization. Android consent result stays in a 30-second, single-cell process-memory handoff. Service Intents contain only the local request ID. Process death forgets authority. The non-exported START_NOT_STICKY mediaProjection service promotes itself before any getMediaProjection call. Every accepted capture uses one projection and one VirtualDisplay; no automatic consent retry or scope inference from private extras.

The independent `recording` JVM module has no Android, Provider, Store, Media3, or path types. Pause admission and resume keyframe gating use a common monotonic media timeline. Stop-and-save and discard remain distinct. Native calls use a single bounded lane; a timeout fences late output and holds ownership until native release actually finishes. Muxer duplicate FD ownership is tracked independently of the Java file wrapper.

Save request input is a discriminator: Network or Capture. Record never creates a URL or network request lease and never charges recorded bytes as transferred network data. It joins the existing owned staging → independent verify → atomic promote → provisional index → exact receipt inspection → SUCCEEDED publication CAS. Capture expectations and terminal decode proof are separate from the existing strict HLS conservation/tail fields.

The actual frozen task codec was v3 (not v2 in the early plan) and owned evidence was already versioned. The initial WIP writer was v4. The bounded resume-proof writer is v5; genuine v1/v2/v3/v4 records remain readable. Owned evidence v3 retains unversioned/v1/v2 readers. Legacy RECORD without explicit resume-proof policy is retained but cannot newly publish. Unknown/mismatched discriminators are rejected, with no database destructive reset.

## Platform design limits

Initial canvas is bounded and capability checked. Output dimensions are actual encoder values. A subsequent captured-content size/rotation change safely stops the same session; no cross-size splice or second VirtualDisplay. System scope stays user chosen; it may be a whole screen or a single application. Internal audio scope is separate and may include allowed playback from other applications in the same profile. Audio permission or capture failure never causes microphone fallback or silent consent downgrade. Silent PCM does not prove DRM or capture-policy failure.

Playback-only audio currently uses a mediaProjection FGS, not microphone. This interpretation and OEM behavior require real-device validation; no claim is made that every OEM/API combination accepts it. The application requests no microphone capture source. Screen lock/system-stop terminates capture, with best-effort verification of the valid recorded portion. Activity pause only suspends network saves and browser/discovery activity; it does not cancel independent capture.

Engineering budgets: single file at most 2 GiB; one session at most 2 hours wall duration; pause at most 10 minutes; 64 MiB free-space reserve; bounded 8 MiB/128 compressed pending samples; 15-second preparation/finalization phases and 5-second resume keyframe limit. These are engineering bounds, not product pricing promises. Capture gets a separate 30-second closing/commit allowance and the same bounded 60-second verification window used by Direct/HLS, not an immediate deadline cancellation when the recording budget ends. Native performance is unmeasured; a valid long/high-resolution recording can time out rather than be published without required evidence. The 2-hour capture ceiling is not a proven end-to-end capacity promise. A session permits at most 32 actually muxed resume keyframes; a would-be 33rd effective resume safely stops and verifies the existing part. Button presses alone are not the count.

Preview accepts only an exact visible successful receipt and a storage-owned read lease. It uses system MediaPlayer, denies playback capture of the preview, closes on background/navigation, and fences a failed native release. There is no new external dependency.

## Official design references (checked 2026-10-06)

- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/media/MediaMuxer

These are API contracts, not copied implementation sources. Captord, Yue, NewPipe, Scrapling and unadmitted libraries remain excluded. Existing exact 72-artifact runtime admission remains mandatory.

## Evidence limits

This is a development checkpoint only. Compile, lint, JVM state/storage/verifier policy tests and source review cannot establish real MediaProjection consent, FGS eligibility, native encoder stability, hardware MP4 decode, audio capture availability, AV drift, or OEM behavior. All actual device/API31–36 phone/tablet tests, APK/AAB assembly and actual recording remain NOT_RUN until separately authorized.

## Selected bounded verification policy B

The desired outcome is a complete independent verification pipeline, not a guarantee that every video/audio frame has been decoded. RECORD scans every sample through actual EOF, checks tracks, PTS, stts timing, sample counts and first/last identities, hashes the complete file before/after verification, and really decodes the complete first, middle and final GOP plus every admitted resume-boundary GOP. Selected GOPs are deduplicated and their work is bounded. AAC readability is sampled at first/middle/end and near resume boundaries. Decoder EOF within selected GOPs is required; the final GOP additionally requires actual extractor EOF. Missing/late/duplicate outputs, missing boundary identity, format changes and native uncertainty never pass.

This deliberately avoids full-track AVC/AAC native decode latency. Structural scanning is still proportional to file size, and all work shares the same 60-second failure boundary and cancellation checks. Unknown performance is not converted into a supported-duration promise. Exact GOP limits and device throughput are recorded with final coverage evidence. Initial full-decode/10-minute experiments are superseded, not part of the shipped policy.
