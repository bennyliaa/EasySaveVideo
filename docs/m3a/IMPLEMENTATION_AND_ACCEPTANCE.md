# M3-A: Direct HTTPS MP4 development slice

Status: verified development candidate, not complete M3 acceptance. The M2 base is c5ecd1f.

## Scope and boundaries

The original specification remains in `docs/reference`; update1 governs milestone order. The later user decision sets API31 minimum, phone/tablet, and a closed-source product. PR-09, PR-10, PR-16 are advanced here only for an original Direct implementation. PR-11 HLS/Stream is still unavailable. Record, transcoding/remux, Library playback, export, Vault, Media3, background services, WorkManager and wake locks are not implemented by this slice. No additional external artifact or license is admitted.

1. A native Save click reads only bounded current-main-document video/currentSrc/source data. There is no page observer, resource interceptor, privileged JavaScript bridge, pre-crawl or automatic download. Multiple resources require explicit selection. Blob/MSE, iframe interiors and unsupported containers remain explicit limitations.
2. Tab, browser session, navigation epoch, selected WebView identity, URL and interaction revision fence detection. Switching away and back invalidates old results. Selection expires after 60 seconds. Already accepted tasks are independent of tab/View lifetime.
3. Direct is gated at command entry and resume but remains free when store state is unknown. No Provider dependency enters save core. Unsupported Stream/Record ports cannot become available through fake entitlements.
4. HTTP work is system HTTPS with normal TLS trust and hostname verification. Every hop is checked, automatic redirects/caches are disabled, private/non-public peers are rejected, and no process-global CookieHandler is installed or bypassed. Requests are streamed with 64 KiB copy buffers. A process-global CookieHandler causes fail-closed behavior.
5. Cookies are captured only for the exact chosen resource after a current same-origin check. Request leases exist only in memory for 60 seconds. Idle source eligibility has an absolute 15-minute lifetime; ordinary resume does not extend it. An active transport keeps required source references until its resources close. Redirects carry no credentials, even same-origin redirects. User-Agent is bounded. Full source URLs, including signed queries, remain memory-only. The private journal stores safe origin plus source-identity digest, not path/query/header credentials. After process death an incomplete task requires the user to revisit and select a source; a newly selected task starts from zero. Completed local transfers can be reverified without a source URL.
6. Strong ETag/If-Range and exact Content-Range checks gate append. A 200 response starts a fresh owned staging inode. Weak/changed/malformed identity, 416 and short/oversized bodies never count as success. Restarts and retries consume the same cumulative byte budget.
7. The Android verifier opens only an owned local FD. A bounded ISO-BMFF preflight rejects QuickTime branding, malformed box lengths and missing moov/mdat. MediaExtractor checks real video/audio tracks, duration, dimensions, encryption and bounded samples. MediaCodec decoded video and present audio output timestamps must prove start/middle/end coverage; MediaMetadataRetriever additionally returns actual scaled frames. SHA-256 and byte count bind evidence to the staged file. A header marker or fake verifier cannot mark a real task successful.
8. Validation has a single process-wide native lane and bounded queue. A timeout/cancel abandons evidence, but does not falsely claim that native code stopped. A stuck call retains its FD/lane until actual finally cleanup, cannot publish late evidence, and blocks additional saves until resources exit. Network close uncertainty likewise retains exclusive ownership and requires process recovery. No substitute native worker is spawned to evade the bound.
9. Private storage uses a separate no-backup root and SQLite database, with random app-owned paths. Intent, fsync, non-overwriting same-filesystem rename under an exclusive process file lock, provisional index and terminal task CAS are distinct crash windows. SQL and files are not represented as one atomic transaction. Cleanup never deletes committed, uncertain-intent, unrelated or pre-existing media.
10. Task UI uses the existing SkinKit theme bridge. It shows actual phase, waits, failures, cancellation and verified success. Unknown progress stays unknown. Download budget reservation is not presented as downloaded bytes. Source selection and task history never trigger payment or publishing.

## Development budgets (injectable, not final product parameters)

- One transfer at a time; one native verification lane
- 2 GiB artifact cap; 4 GiB lifetime cumulative network budget per task
- Two hours per explicit execution/resume, at most two internal retries under that deadline
- 60-second local verification window; 24-hour user wait expiry
- 10-second connect and 30-second no-progress limits; at most five redirects
- 32 page candidates; 5-second detection callback window
- At most 100 retained task records in this development slice; capacity stops new saves without deleting media (task-retention management is not implemented)
- At most 16 media tracks, 4 MiB encoded sample buffer, 4096 sample advances, 64 MiB queued decode data per sample position, 16,777,216 decoded source pixels

These conservative limits can reject otherwise playable media. They are visible or documented engineering boundaries, not measured throughput or universal-site promises. Discovery's 15-second / 2 MiB Provider budget is unrelated.

## Lifecycle and recovery

The application-scoped runtime survives Activity recreation. Foreground/background state is separate from tab visibility; only actual app backgrounding pauses save. Backgrounding releases network work, preserves verified checkpoint facts, and requires manual resume. No automatic network recovery is started on application launch. A lightweight bounded journal pass runs off the main thread; unknown/corrupt storage fails closed without resetting M2 data or deleting media.

Runtime UI consumes memory snapshots. Control and transfer work do not run on the main thread. Cancellation that loses to an already committed result does not overwrite success. Device-level 1-second cancellation, background checkpoints, native stalls and storage durability remain pending acceptance checks.

## Verification result

Final strict offline aggregate verification passed: 227 JVM tests (30 core-domain, 9 ui-theme, 65 discovery, 31 save, 92 app), with zero failed/error/skipped cases. Both Debug and Release Kotlin compile and lint passed; each lint report contains zero errors, six warnings and two hints. The fixed 55-artifact runtime admission, original license bytes, source boundaries and SkinKit seven-getter ABI checks passed. The synthetic detector script passed 11 checks; the existing host-SQLite regression also passed.

Remaining lint warnings are existing SDK-version/KTX advisories and conservative File.usableSpace guidance; no rule was suppressed to obtain the result. The runtime only retains an application context and no longer triggers StaticFieldLeak. Exact verified source digests are in evidence/verification-summary.json.

## Evidence and limits

- `evidence/` contains compile, lint, JVM/contract and synthetic-DOM results as they are finalized.
- `scripts/test-save-detector.mjs` is a bounded synthetic DOM test, not Android/WebView validation.
- Network fixtures use scripted responses and generated streaming bytes. They are not real HTTPS, credentials or third-party site tests.
- Storage fixtures exercise real temporary files and fault windows with a fake catalog. They are not proof of Android SQLite / power-loss behavior.
- Android MediaExtractor/MediaCodec/MMR code compilation is not device playback validation.
- No APK/AAB, installation, emulator/device execution, real-site video request, credential use, purchase, publication or deployment occurred.

A development tag is not an accepted/full M3 tag. HLS-to-independent-MP4 and the device matrix remain explicit gates.

## Official API references

- https://developer.android.com/reference/java/net/HttpURLConnection
- https://developer.android.com/reference/android/webkit/WebView
- https://developer.android.com/reference/android/webkit/CookieManager
- https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.5
- https://developer.android.com/reference/android/media/MediaExtractor
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/media/MediaMetadataRetriever
- https://developer.android.com/reference/android/system/Os
