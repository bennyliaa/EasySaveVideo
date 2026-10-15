# Actual loading implementation decisions

This addendum supersedes the earlier assessment's assumed StaticImageDecoder path. It records the implemented revision 04 route; it is not a device/render/network test result.

## User-visible route and authority

The existing configured/enabled Commons source registration issues the existing DiscoveryThumbnailCapability for an exact DiscoveryThumbnailBinding and current result metadata. The registration checks Commons namespace, provenance, page/item identity, matching attribution observation and current management generation. The controller checks the current page/results, source intent, foreground/route, Save/Record, power, exact published thumbnail epoch and admitted source descriptor. Settings/sort/page/direct-search intents also have requested/applied tokens: refresh captures its epoch at entry and uses -1 while a command is pending; each serialized command acknowledges only its own token. A same-valued settings notification creates no token. Only a subsequent fresh snapshot can become eligible. Partial attribution can still supply display-only metadata; no download or reuse authority is conferred.

The UI reports actual visible grid keys, retains at most 16 accepted software image handles, and draws them with Compose Image. Optional missing/failed preview stays a localized placeholder; that fallback is not claimed as proof of remote loading.

No new endpoint or contact decision is required: source availability reuses existing BuildConfig.COMMONS_ENABLED/COMMONS_CONTACT and Commons maintenance admission. The API contact is never sent to the image service. Image URLs are issued from the exact observed result, checked by existing CommonsUrlPolicy, and restricted to upload.wikimedia.org/thumb.wikimedia.org. The fixed image User-Agent contains app/version only. Browser cookies/authentication, API contact, private-mode stores and other source URLs are absent from this client.

## Actual bytes and decode

A dedicated anonymous OkHttp 4.12 client uses no cookie jar, cache, origin/proxy authentication or proxy; redirects and automatic connection retry are disabled. DNS permits only fixed image hosts and public addresses using the existing Commons public-address rule. Normal platform TLS/hostname verification remains intact.

200 response, exact request URL, permitted JPEG/PNG/WebP MIME and absent/identity Content-Encoding precede reading. Encoded body is at most 1,048,576 bytes with true EOF, including the extra one-byte probe at the cap. Known Content-Length must match the full actual body. Unknown-length/chunked true EOF is accepted. No truncated prefix is fed to the decoder.

The exact bounded ByteArray is first inspected by platform BitmapFactory.inJustDecodeBounds. Positive source dimensions, sides <=8192 and Long product <=16,777,216 are the engineering preview resource policy. This is header-only inspection, not a second pixel decode, custom parser, or image-format proof.

Coil core 3.3.0 receives this ByteArray. Its ByteArrayFetcher constructs ImageSource without ByteBufferMetadata, so the installed static path is BitmapFactoryDecoder, not StaticImageDecoder. size(256,144), Scale.FIT, Precision.EXACT and software/ARGB_8888 preferences are requested. The actual BitmapImage must be software, <=256x144, and allocationByteCount <=147456. A returned F16 image that exceeds budget is rejected without conversion/copy. Platform recovery or static first-frame output can be displayed; there is no custom partial/container/animation rejection policy.

The encoded buffer builder, final ByteArray, Coil/Okio source copy, codec metadata and platform native temporaries may coexist. Neither requested output size nor actual final allocation proves a hard native/GPU peak bound. No claim is made beyond the explicit source/encoded/output/retained budgets.

## Cancellation, retained handles and cache

DiscoveryCancellation supplies immediate thread-safe cancellation state and off-caller resource hooks (Call.cancel and decode Job.cancel). Capability read uses the existing releaseCompletedAttempt atomic hook retirement; cancellation that already claimed hooks must drain before read returns. Structured runBlocking/execute is awaited, and its parent Job is joined. A cancel signal or request completion facade is never used as evidence of physical platform return.

One process-wide worker lane survives controller/session recreation. Completion is delivered only after the real task returns. A dedicated main Handler owns completion/watchdog tasks, independent of the controller handler removed at close. Stalled/unconfirmed ownership poisons this process lane. If the independent main Handler refuses the final completion, the process is permanently poisoned and can retain the one bounded returned frame; that fail-stop branch does not certify frame release or admit replacement work. Source maintenance success increments its revision and signals current capabilities immediately rather than waiting for 300ms polling. Off-main withdrawal performs only generation changes/capability cancellation before posting UI-only Session/map cleanup.

OkHttp, Coil memory and Coil disk caches are disabled. Only current visible accepted bitmap handles remain, at most 16 and 2,359,296 allocation bytes. Route/background/source/page/viewport changes drop those handles. Frame close drops its borrowed reference; it does not recycle a bitmap that a separately retained Compose drawing handle may still use. Process 429/503 Retry-After cooldown persists across scroll/page/controller reconstruction. No automatic retry loop is introduced.

## Evidence boundary

Revision 04 contains executable host tests for bounded reads, actual OkHttp client configuration without requests, process-lane scheduling/poison/duplicate delivery, and real DiscoveryCancellation reader/hook ordering. Source-wiring assertions connect production registration/controller/Compose calls. Central final forced execution was independently recounted from frozen XML: 515 suites, 5,690 passed + 2 skipped, no failures/errors. Both lint variants completed with zero errors; the process reader applicationContext-retention warning and two state-autoboxing hints remain disclosed in REPORT.md. No platform Bitmap/Coil pixel decode, Compose screenshot, real HTTP/TLS interoperability, device execution or native-memory measurement was performed by this implementation worker.
